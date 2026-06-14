package com.ideaqr.gateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideaqr.gateway.entity.History;
import com.ideaqr.gateway.repository.HistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only audit writer. Every meaningful state change funnels through here and
 * lands in the immutable {@code histories} journal as a JSON payload.
 *
 * Each append runs in its own REQUIRES_NEW transaction so that an audit write is
 * never silently rolled back together with a failing business transaction.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AuditService {

    private final HistoryRepository historyRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public History append(String eventType, UUID interactionUid, Map<String, Object> payload) {
        History history = History.builder()
                .historyUid(UUID.randomUUID())
                .interactionUid(interactionUid)
                .eventType(eventType)
                .eventPayload(toJson(payload))
                .createdAt(LocalDateTime.now())
                .build();
        History saved = historyRepository.save(history);
        log.info("AUDIT event={} historyUid={} interactionUid={}",
                eventType, saved.getHistoryUid(), interactionUid);
        return saved;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize audit payload, storing fallback string: {}", e.getMessage());
            return String.valueOf(payload);
        }
    }
}
