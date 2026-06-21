package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.domain.RequestRecord;
import com.ideaqr.gateway.domain.User;
import com.ideaqr.gateway.domain.enums.RequestStatus;
import com.ideaqr.gateway.domain.enums.RequestType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that the V2 foreign keys give real referential integrity (audit 3.6): the
 * database rejects a row that references a non-existent identity, and accepts one
 * that references a real seeded identity. Runs on the full Flyway (V1 + V2) schema.
 */
@SpringBootTest
@ActiveProfiles("test")
class ForeignKeyIntegrityTest {

    @Autowired
    private RequestRepository requestRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void danglingIdentityReferenceIsRejectedByTheForeignKey() {
        RequestRecord orphan = RequestRecord.builder()
                .identityUid(UUID.randomUUID()) // no such identity exists
                .objectUid("X_OBJECT")
                .requestType(RequestType.ACCESS)
                .status(RequestStatus.PENDING)
                .build();

        assertThatThrownBy(() -> requestRepository.saveAndFlush(orphan))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void validIdentityReferenceIsAccepted() {
        User citizen = userRepository.findByUsername("citizen").orElseThrow();

        RequestRecord ok = RequestRecord.builder()
                .identityUid(citizen.getIdentityUid())
                .objectUid("X_OBJECT")
                .requestType(RequestType.ACCESS)
                .status(RequestStatus.PENDING)
                .build();

        assertThat(requestRepository.saveAndFlush(ok).getRequestUid()).isNotNull();
    }
}
