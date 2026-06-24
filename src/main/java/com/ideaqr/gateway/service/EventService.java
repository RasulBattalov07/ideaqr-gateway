package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.Event;
import com.ideaqr.gateway.domain.enums.EventType;
import com.ideaqr.gateway.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Writes the unified event log (the "Event" stage of the architecture). Like the
 * history journal it is append-only — events are facts and are never mutated or
 * deleted. Kept deliberately small so the rest of the pipeline can emit an event
 * with a single call.
 */
@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;

    @Transactional
    public Event record(EventType type, UUID identityUid, String objectUid, UUID interactionUid, String summary) {
        return eventRepository.save(Event.builder()
                .eventType(type)
                .identityUid(identityUid)
                .objectUid(objectUid)
                .interactionUid(interactionUid)
                .summary(summary)
                .build());
    }

    @Transactional
    public Event record(EventType type, UUID identityUid, String summary) {
        return record(type, identityUid, null, null, summary);
    }

    public List<Event> globalLog() {
        return eventRepository.findAllByOrderByCreatedAtDesc();
    }

    /** Server-paginated global event log (admin view) — audit M-2. */
    public Page<Event> globalLog(Pageable pageable) {
        return eventRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    public List<Event> logFor(UUID identityUid) {
        return eventRepository.findByIdentityUidOrderByCreatedAtDesc(identityUid);
    }
}
