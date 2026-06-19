package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.History;
import com.ideaqr.gateway.domain.enums.HistoryEventType;
import com.ideaqr.gateway.repository.HistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Writes to and reads from the append-only history journal. The service only ever
 * inserts rows — it offers no update or delete operation, which keeps the journal
 * immutable (the platform's "digital evidence" guarantee).
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final HistoryRepository historyRepository;

    /** Append a simple event (no chain links). */
    @Transactional
    public History record(UUID identityUid, String objectUid, HistoryEventType eventType, String description) {
        return record(identityUid, objectUid, eventType, description, null, null, null);
    }

    /** Append an event carrying the chain that produced it. */
    @Transactional
    public History record(UUID identityUid, String objectUid, HistoryEventType eventType, String description,
                          UUID requestUid, UUID decisionUid, UUID interactionUid) {
        History history = History.builder()
                .identityUid(identityUid)
                .objectUid(objectUid)
                .eventType(eventType)
                .description(description)
                .requestUid(requestUid)
                .decisionUid(decisionUid)
                .interactionUid(interactionUid)
                .build();
        return historyRepository.save(history);
    }

    /** Whole-system journal, newest first (admin view). */
    public List<History> globalJournal() {
        return historyRepository.findAllByOrderByCreatedAtDesc();
    }

    /** A single identity's journal, newest first (citizen view). */
    public List<History> journalFor(UUID identityUid) {
        return historyRepository.findByIdentityUidOrderByCreatedAtDesc(identityUid);
    }
}
