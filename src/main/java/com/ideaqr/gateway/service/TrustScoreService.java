package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.enums.InteractionStatus;
import com.ideaqr.gateway.repository.ComplaintRepository;
import com.ideaqr.gateway.repository.IdentityRepository;
import com.ideaqr.gateway.repository.InteractionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Computes the Trust Score (0–100) that belongs to an {@link Identity} (not the
 * account). This MVP uses a simple, explainable formula:
 *
 * <pre>
 *   score = base(trustLevel) + 2·confirmedInteractions − 8·complaints
 * </pre>
 *
 * clamped to [0, 100]. The brief lists interactions, confirmations, successful
 * events and complaints as inputs; a richer model can replace this later without
 * touching callers.
 */
@Service
@RequiredArgsConstructor
public class TrustScoreService {

    private final InteractionRepository interactionRepository;
    private final ComplaintRepository complaintRepository;
    private final IdentityRepository identityRepository;

    public int compute(Identity identity) {
        UUID id = identity.getIdentityUid();
        long confirmed = interactionRepository.countByIdentityUidAndStatus(id, InteractionStatus.CONFIRMED);
        long complaints = complaintRepository.findByIdentityUidOrderByCreatedAtDesc(id).size();

        int base = Math.min(60, Math.max(20, identity.getTrustLevel() / 2 + 15));
        long raw = base + 2L * confirmed - 8L * complaints;
        return (int) Math.max(0, Math.min(100, raw));
    }

    /** Recompute and persist the cached score on the identity. */
    @Transactional
    public int refresh(Identity identity) {
        int score = compute(identity);
        identity.setTrustScore(score);
        identityRepository.save(identity);
        return score;
    }
}
