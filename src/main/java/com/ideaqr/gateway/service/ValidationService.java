package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.enums.DecisionOutcome;
import com.ideaqr.gateway.domain.enums.ObjectCategory;
import com.ideaqr.gateway.domain.enums.RoleType;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalTime;
import java.util.Set;

import static com.ideaqr.gateway.domain.enums.DecisionOutcome.APPROVED;
import static com.ideaqr.gateway.domain.enums.DecisionOutcome.REJECTED;

/**
 * The policy engine. Evaluates an access request into a verdict using a small set
 * of named policies: object-existence, role requirement, trust threshold and the
 * working-hours window. Restricted categories (medical, infrastructure) are gated;
 * retail / eco / general objects are public.
 */
@Service
public class ValidationService {

    private static final int WORK_START = 8;   // inclusive
    private static final int WORK_END = 18;    // exclusive
    private static final int TRUST_MEDICAL = 70;
    private static final int TRUST_INFRA = 60;

    /**
     * Server clock used for the working-hours gate. Injected (not {@code LocalTime.now()})
     * so the policy stays server-side and un-spoofable (audit 4.3) yet remains
     * deterministically testable with a fixed clock.
     */
    private final Clock clock;

    public ValidationService(Clock clock) {
        this.clock = clock;
    }

    /** Immutable verdict carrying everything a {@code Decision} needs. */
    public record Verdict(DecisionOutcome outcome, String reasonCode, String reason, String riskLevel) {}

    public Verdict decideAccess(Identity identity, ObjectCategory category, boolean known) {
        if (category == null || category == ObjectCategory.UNKNOWN) {
            return new Verdict(REJECTED, "OBJECT_NOT_FOUND", "Объект не найден в реестре.", "MEDIUM");
        }

        Set<RoleType> roles = identity.getRoles();
        int trust = identity.getTrustLevel();
        // Security (audit 4.3): the time gate uses the SERVER clock exclusively. There
        // is no client-supplied hour, so the working-hours policy cannot be spoofed.
        int hour = LocalTime.now(clock).getHour();
        boolean workingHours = hour >= WORK_START && hour < WORK_END;

        return switch (category) {
            case MEDICAL -> {
                // Medical data is accessible to doctors and pharmacists (the Role Access
                // Matrix: a pharmacist may view prescriptions / appointments).
                if (!(roles.contains(RoleType.DOCTOR) || roles.contains(RoleType.PHARMACIST))) {
                    yield new Verdict(REJECTED, "ROLE_REQUIRED_DOCTOR",
                            "Доступ к медицинским данным разрешён только врачам и фармацевтам.", "HIGH");
                }
                if (trust < TRUST_MEDICAL) {
                    yield new Verdict(REJECTED, "TRUST_TOO_LOW",
                            "Недостаточный уровень доверия для доступа к медицинским данным.", "HIGH");
                }
                if (!workingHours) {
                    yield new Verdict(REJECTED, "OUTSIDE_WORKING_HOURS",
                            "Доступ к медицинской карте возможен только в рабочее время (08:00–18:00).", "MEDIUM");
                }
                yield new Verdict(APPROVED, "ACCESS_GRANTED",
                        "Проверка пройдена: роль (врач/фармацевт), уровень доверия и рабочее время.", "MEDIUM");
            }
            case INFRASTRUCTURE -> {
                if (!(roles.contains(RoleType.INSPECTOR) || roles.contains(RoleType.ENGINEER))) {
                    yield new Verdict(REJECTED, "ROLE_REQUIRED_INSPECTOR",
                            "Доступ к инфраструктурному объекту разрешён только инспекторам и инженерам.", "HIGH");
                }
                if (trust < TRUST_INFRA) {
                    yield new Verdict(REJECTED, "TRUST_TOO_LOW",
                            "Недостаточный уровень доверия для доступа к объекту.", "HIGH");
                }
                if (!workingHours) {
                    yield new Verdict(REJECTED, "OUTSIDE_WORKING_HOURS",
                            "Доступ к инфраструктурному объекту возможен только в рабочее время (08:00–18:00).", "MEDIUM");
                }
                yield new Verdict(APPROVED, "ACCESS_GRANTED",
                        "Проверка пройдена: роль, уровень доверия и рабочее время.", "MEDIUM");
            }
            case RETAIL, ECO, GENERAL -> new Verdict(APPROVED, "PUBLIC_OBJECT",
                    "Объект общедоступен. Данные предоставлены.", "LOW");
            default -> new Verdict(REJECTED, "OBJECT_NOT_FOUND", "Объект не найден в реестре.", "MEDIUM");
        };
    }
}
