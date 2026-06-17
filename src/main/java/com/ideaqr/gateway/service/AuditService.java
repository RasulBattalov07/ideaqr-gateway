package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.History;
import com.ideaqr.gateway.domain.enums.HistoryEventType;
import com.ideaqr.gateway.repository.HistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Writes events to the append-only History journal. This service only ever
 * inserts rows — it never updates or deletes — which keeps the audit trail
 * immutable, the core guarantee of the platform.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final HistoryRepository historyRepository;

    /**
     * Append a fully-linked event to the journal.
     *
     * @return the persisted History record (its UID is surfaced to the client).
     */
    public History record(UUID identityUid,
                          UUID requestUid,
                          UUID decisionUid,
                          UUID interactionUid,
                          String objectUid,
                          HistoryEventType eventType,
                          String description) {
        History history = History.builder()
                .identityUid(identityUid)
                .requestUid(requestUid)
                .decisionUid(decisionUid)
                .interactionUid(interactionUid)
                .objectUid(objectUid)
                .eventType(eventType)
                .description(description)
                .build();
        return historyRepository.save(history);
    }

    /** Convenience overload for events that are not tied to a full chain. */
    public History record(UUID identityUid, String objectUid, HistoryEventType eventType, String description) {
        return record(identityUid, null, null, null, objectUid, eventType, description);
    }

    public List<History> recentGlobal() {
        return historyRepository.findTop50ByOrderByCreatedAtDesc();
    }

    public List<History> forIdentity(UUID identityUid) {
        return historyRepository.findByIdentityUidOrderByCreatedAtDesc(identityUid);
    }
}
