package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.enums.HistoryEventType;
import com.ideaqr.gateway.repository.HistoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the "immutable journal" claim is real, not a comment (audit 4.5): appends
 * form an intact SHA-256 chain, and an out-of-band edit that bypasses the
 * application is detected by {@code verifyChain()}.
 */
@DataJpaTest
@Import(AuditService.class)
class AuditServiceChainTest {

    @Autowired
    private AuditService auditService;

    @Autowired
    private HistoryRepository historyRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void chainIsIntactAfterAppends() {
        auditService.record(UUID.randomUUID(), "OBJ_1", HistoryEventType.ACCESS_GRANTED, "first");
        auditService.record(UUID.randomUUID(), "OBJ_2", HistoryEventType.ACCESS_DENIED, "second");
        auditService.record(UUID.randomUUID(), "OBJ_3", HistoryEventType.QR_CREATED, "third");

        AuditService.ChainVerification result = auditService.verifyChain();

        assertThat(result.valid()).isTrue();
        assertThat(result.entriesChecked()).isEqualTo(3);
        assertThat(result.brokenAtHistoryUid()).isNull();
    }

    @Test
    void tamperingWithAStoredRowBreaksTheChain() {
        auditService.record(UUID.randomUUID(), "OBJ_1", HistoryEventType.ACCESS_GRANTED, "original");
        auditService.record(UUID.randomUUID(), "OBJ_2", HistoryEventType.ACCESS_GRANTED, "second");

        // Simulate a direct DB edit (e.g. via a leaked console) that bypasses the app.
        entityManager.getEntityManager()
                .createNativeQuery("UPDATE histories SET description = 'TAMPERED'")
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        AuditService.ChainVerification result = auditService.verifyChain();

        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAtHistoryUid()).isNotNull();
    }
}
