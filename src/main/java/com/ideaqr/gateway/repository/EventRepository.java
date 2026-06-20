package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.domain.Event;
import com.ideaqr.gateway.domain.enums.EventType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Append-only access to the unified event log. */
public interface EventRepository extends JpaRepository<Event, UUID> {

    List<Event> findAllByOrderByCreatedAtDesc();

    List<Event> findByIdentityUidOrderByCreatedAtDesc(UUID identityUid);

    /** Used by the guest-merge flow to re-point a guest identity's events. */
    List<Event> findByIdentityUid(UUID identityUid);

    long countByEventType(EventType eventType);
}
