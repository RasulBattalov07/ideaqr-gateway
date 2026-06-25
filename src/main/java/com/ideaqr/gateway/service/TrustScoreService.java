package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.Complaint;
import com.ideaqr.gateway.domain.History;
import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.enums.ComplaintStatus;
import com.ideaqr.gateway.domain.enums.HistoryEventType;
import com.ideaqr.gateway.domain.enums.InteractionStatus;
import com.ideaqr.gateway.repository.ComplaintRepository;
import com.ideaqr.gateway.repository.HistoryRepository;
import com.ideaqr.gateway.repository.IdentityRepository;
import com.ideaqr.gateway.repository.InteractionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Computes the Trust Score (0–100) that belongs to an {@link Identity} (not the
 * account). The MVP uses a simple, explainable formula tied to real interactions so
 * the score visibly moves during a demo:
 *
 * <pre>
 *   score = base(trustLevel)
 *         + 2·confirmedInteractions      // successful, confirmed actions
 *         + 5·resolvedComplaints         // a complaint the admin resolved in your favour
 *         − 4·openComplaints             // still-open / rejected complaints you filed
 *         − 2·deniedScans                // access attempts the policy engine refused
 * </pre>
 *
 * clamped to [0, 100]. The score is recomputed on every state change (scan, profile
 * confirmation, prescription, complaint resolution) so the number is never stale.
 */
@Service
@RequiredArgsConstructor
public class TrustScoreService {

    private final InteractionRepository interactionRepository;
    private final ComplaintRepository complaintRepository;
    private final HistoryRepository historyRepository;
    private final IdentityRepository identityRepository;

    public int compute(Identity identity) {
        // Alias-aware (audit 4.5/4.6): count across the identity and any guest identities
        // merged into it, since the merge no longer rewrites the guest's interactions.
        Set<UUID> ids = new LinkedHashSet<>();
        ids.add(identity.getIdentityUid());
        ids.addAll(identity.getLinkedGuestUids());

        long confirmed = 0;
        long resolvedComplaints = 0;
        long openComplaints = 0;
        long deniedScans = 0;
        for (UUID id : ids) {
            confirmed += interactionRepository.countByIdentityUidAndStatus(id, InteractionStatus.CONFIRMED);
            for (Complaint c : complaintRepository.findByIdentityUidOrderByCreatedAtDesc(id)) {
                if (c.getStatus() == ComplaintStatus.RESOLVED) {
                    resolvedComplaints++;
                } else {
                    openComplaints++;
                }
            }
            for (History h : historyRepository.findByIdentityUid(id)) {
                if (h.getEventType() == HistoryEventType.ACCESS_DENIED) {
                    deniedScans++;
                }
            }
        }

        int base = Math.min(60, Math.max(20, identity.getTrustLevel() / 2 + 15));
        long raw = base + 2L * confirmed + 5L * resolvedComplaints - 4L * openComplaints - 2L * deniedScans;
        return (int) Math.max(0, Math.min(100, raw));
    }

    /**
     * Read-only accessor for read paths (e.g. {@code GET /api/auth/me}). Returns the
     * last persisted score without writing; only falls back to an in-memory compute
     * when no score has ever been cached. Never persists — see audit 3.3.
     */
    public int cachedOrCompute(Identity identity) {
        Integer cached = identity.getTrustScore();
        return cached != null ? cached : compute(identity);
    }

    /** Recompute and persist the cached score on the identity (state-change paths only). */
    @Transactional
    public int refresh(Identity identity) {
        int score = compute(identity);
        identity.setTrustScore(score);
        identityRepository.save(identity);
        return score;
    }
}
