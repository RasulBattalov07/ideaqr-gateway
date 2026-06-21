package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.enums.DecisionOutcome;
import com.ideaqr.gateway.domain.enums.IdentityStatus;
import com.ideaqr.gateway.domain.enums.IdentityType;
import com.ideaqr.gateway.domain.enums.ObjectCategory;
import com.ideaqr.gateway.domain.enums.RoleType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure policy-engine tests. Time is driven by a fixed {@link Clock} so the
 * working-hours branch is deterministic regardless of when the suite runs — and the
 * gate stays server-side, never client-supplied (audit 4.3).
 */
class ValidationServiceTest {

    private static final Clock DAYTIME = Clock.fixed(Instant.parse("2026-06-21T10:00:00Z"), ZoneOffset.UTC);
    private static final Clock NIGHT = Clock.fixed(Instant.parse("2026-06-21T03:00:00Z"), ZoneOffset.UTC);

    private Identity identity(Set<RoleType> roles, int trust) {
        return Identity.builder()
                .identityType(IdentityType.PRIMARY)
                .status(IdentityStatus.ACTIVE)
                .roles(new LinkedHashSet<>(roles))
                .trustLevel(trust)
                .build();
    }

    @Test
    void unknownObjectIsRejected() {
        var verdict = new ValidationService(DAYTIME)
                .decideAccess(identity(Set.of(RoleType.CITIZEN), 50), ObjectCategory.UNKNOWN, false);
        assertThat(verdict.outcome()).isEqualTo(DecisionOutcome.REJECTED);
        assertThat(verdict.reasonCode()).isEqualTo("OBJECT_NOT_FOUND");
    }

    @Test
    void retailIsPublicEvenAtNight() {
        var verdict = new ValidationService(NIGHT)
                .decideAccess(identity(Set.of(RoleType.CITIZEN), 10), ObjectCategory.RETAIL, true);
        assertThat(verdict.outcome()).isEqualTo(DecisionOutcome.APPROVED);
        assertThat(verdict.reasonCode()).isEqualTo("PUBLIC_OBJECT");
    }

    @Test
    void medicalRequiresDoctorRole() {
        var verdict = new ValidationService(DAYTIME)
                .decideAccess(identity(Set.of(RoleType.CITIZEN), 90), ObjectCategory.MEDICAL, true);
        assertThat(verdict.outcome()).isEqualTo(DecisionOutcome.REJECTED);
        assertThat(verdict.reasonCode()).isEqualTo("ROLE_REQUIRED_DOCTOR");
    }

    @Test
    void medicalRequiresSufficientTrust() {
        var verdict = new ValidationService(DAYTIME)
                .decideAccess(identity(Set.of(RoleType.DOCTOR, RoleType.CITIZEN), 50), ObjectCategory.MEDICAL, true);
        assertThat(verdict.outcome()).isEqualTo(DecisionOutcome.REJECTED);
        assertThat(verdict.reasonCode()).isEqualTo("TRUST_TOO_LOW");
    }

    @Test
    void medicalApprovedForDoctorDuringWorkingHours() {
        var verdict = new ValidationService(DAYTIME)
                .decideAccess(identity(Set.of(RoleType.DOCTOR, RoleType.CITIZEN), 85), ObjectCategory.MEDICAL, true);
        assertThat(verdict.outcome()).isEqualTo(DecisionOutcome.APPROVED);
    }

    @Test
    void medicalRejectedOutsideWorkingHours() {
        var verdict = new ValidationService(NIGHT)
                .decideAccess(identity(Set.of(RoleType.DOCTOR, RoleType.CITIZEN), 85), ObjectCategory.MEDICAL, true);
        assertThat(verdict.outcome()).isEqualTo(DecisionOutcome.REJECTED);
        assertThat(verdict.reasonCode()).isEqualTo("OUTSIDE_WORKING_HOURS");
    }

    @Test
    void infrastructureRequiresInspectorOrEngineer() {
        var verdict = new ValidationService(DAYTIME)
                .decideAccess(identity(Set.of(RoleType.CITIZEN), 90), ObjectCategory.INFRASTRUCTURE, true);
        assertThat(verdict.outcome()).isEqualTo(DecisionOutcome.REJECTED);
        assertThat(verdict.reasonCode()).isEqualTo("ROLE_REQUIRED_INSPECTOR");
    }
}
