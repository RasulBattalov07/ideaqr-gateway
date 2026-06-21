package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.History;
import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.enums.HistoryEventType;
import com.ideaqr.gateway.repository.HistoryRepository;
import com.ideaqr.gateway.repository.IdentityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Writes to and reads from the append-only history journal. The service only ever
 * inserts rows — it offers no update or delete operation — and every append is
 * linked into a SHA-256 hash chain ({@code prevHash} → {@code entryHash}), which
 * makes the journal tamper-evident: any later edit or deletion is detectable by
 * {@link #verifyChain()} (audit 4.5).
 *
 * <p>Reads are <i>alias-aware</i>: an identity's journal includes the history of any
 * guest identities merged into it ({@link Identity#getLinkedGuestUids()}), so the
 * merge never has to rewrite rows.</p>
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private static final String GENESIS = "GENESIS";
    private static final char SEP = '\u0001'; // unit-separator delimiter

    private final HistoryRepository historyRepository;
    private final IdentityRepository identityRepository;

    /** Append a simple event (no chain links). */
    @Transactional
    public History record(UUID identityUid, String objectUid, HistoryEventType eventType, String description) {
        return record(identityUid, objectUid, eventType, description, null, null, null);
    }

    /**
     * Append an event carrying the chain that produced it. Synchronized so the
     * hash chain stays linear on this instance; createdAt is forced strictly
     * monotonic so insert order is unambiguous for verification.
     */
    @Transactional
    public synchronized History record(UUID identityUid, String objectUid, HistoryEventType eventType,
                                       String description, UUID requestUid, UUID decisionUid, UUID interactionUid) {
        History tip = historyRepository.findTopByOrderByCreatedAtDescHistoryUidDesc();
        String prevHash = tip != null ? tip.getEntryHash() : GENESIS;

        UUID historyUid = UUID.randomUUID();
        LocalDateTime createdAt = LocalDateTime.now();
        if (tip != null && !createdAt.isAfter(tip.getCreatedAt())) {
            createdAt = tip.getCreatedAt().plusNanos(1);
        }
        String entryHash = computeHash(prevHash, historyUid, identityUid, objectUid, eventType,
                description, requestUid, decisionUid, interactionUid, createdAt);

        History history = History.builder()
                .historyUid(historyUid)
                .identityUid(identityUid)
                .objectUid(objectUid)
                .eventType(eventType)
                .description(description)
                .requestUid(requestUid)
                .decisionUid(decisionUid)
                .interactionUid(interactionUid)
                .prevHash(prevHash)
                .entryHash(entryHash)
                .createdAt(createdAt)
                .build();
        return historyRepository.save(history);
    }

    /** Whole-system journal, newest first (admin view). */
    public List<History> globalJournal() {
        return historyRepository.findAllByOrderByCreatedAtDesc();
    }

    /** Paginated whole-system journal (admin view) — audit 3.1. */
    public Page<History> globalJournal(Pageable pageable) {
        return historyRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    /** A single identity's journal, newest first — includes merged guest aliases. */
    public List<History> journalFor(UUID identityUid) {
        return historyRepository.findByIdentityUidInOrderByCreatedAtDesc(scope(identityUid));
    }

    /** Paginated alias-aware journal for one identity — audit 3.1. */
    public Page<History> journalFor(UUID identityUid, Pageable pageable) {
        return historyRepository.findByIdentityUidInOrderByCreatedAtDesc(scope(identityUid), pageable);
    }

    /** The set of identity UIDs whose history belongs to this caller (self + aliases). */
    private Set<UUID> scope(UUID identityUid) {
        Set<UUID> ids = new HashSet<>();
        ids.add(identityUid);
        identityRepository.findById(identityUid)
                .ifPresent(identity -> ids.addAll(identity.getLinkedGuestUids()));
        return ids;
    }

    // ------------------------------------------------------------------
    //  Tamper-evidence
    // ------------------------------------------------------------------

    /** Outcome of a chain integrity check. */
    public record ChainVerification(boolean valid, long entriesChecked, String brokenAtHistoryUid) {}

    /**
     * Recompute the whole chain from genesis and confirm every link. Returns the
     * first entry whose stored hash or back-link does not match — proof that the
     * "immutable journal" claim is real and checkable.
     */
    public ChainVerification verifyChain() {
        List<History> all = historyRepository.findAllByOrderByCreatedAtAscHistoryUidAsc();
        String expectedPrev = GENESIS;
        long checked = 0;
        for (History h : all) {
            String recomputed = computeHash(h.getPrevHash(), h.getHistoryUid(), h.getIdentityUid(),
                    h.getObjectUid(), h.getEventType(), h.getDescription(), h.getRequestUid(),
                    h.getDecisionUid(), h.getInteractionUid(), h.getCreatedAt());
            boolean linkOk = Objects.equals(expectedPrev, h.getPrevHash());
            boolean contentOk = Objects.equals(recomputed, h.getEntryHash());
            if (!linkOk || !contentOk) {
                return new ChainVerification(false, checked, String.valueOf(h.getHistoryUid()));
            }
            expectedPrev = h.getEntryHash();
            checked++;
        }
        return new ChainVerification(true, checked, null);
    }

    private String computeHash(String prevHash, UUID historyUid, UUID identityUid, String objectUid,
                               HistoryEventType eventType, String description, UUID requestUid,
                               UUID decisionUid, UUID interactionUid, LocalDateTime createdAt) {
        String canonical = String.valueOf(prevHash) + SEP + historyUid + SEP + identityUid + SEP
                + objectUid + SEP + eventType + SEP + description + SEP + requestUid + SEP
                + decisionUid + SEP + interactionUid + SEP + createdAt;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось вычислить хэш журнала", e);
        }
    }
}
