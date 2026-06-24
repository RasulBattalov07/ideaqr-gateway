package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.enums.DecisionOutcome;
import com.ideaqr.gateway.domain.enums.ObjectCategory;
import com.ideaqr.gateway.domain.enums.RoleType;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalTime;
import java.util.Set;
import java.util.UUID;

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

    /**
     * Org-aware entry point for the golden pipeline
     * (… → Role → <b>Organization</b> → Request → Decision → …). The organisation the actor
     * is acting under is threaded into the decision so a future Policy/Consent engine can
     * consult it; the MVP records the context (on the Request, Decision reason and audit)
     * without introducing a new org-based denial — the stable core is unchanged.
     */
    public Verdict decideAccess(Identity identity, ObjectCategory category, boolean known, UUID organizationUid) {
        return decideAccess(identity, category, known);
    }

    /**
     * The {@code Policy} (Document 22 catalog) that governs access to a category. Names the
     * rule that drives the decision; the data-driven Policy engine is a future phase.
     * Extensible: a new category maps here in one place.
     */
    public String governingPolicy(ObjectCategory category) {
        if (category == null) {
            return "OBJECT_EXISTENCE";
        }
        return switch (category) {
            case MEDICAL -> "MEDICAL_ACCESS";
            case INFRASTRUCTURE -> "INFRASTRUCTURE_ACCESS";
            case RETAIL, ECO, GENERAL -> "PUBLIC_ACCESS";
            default -> "OBJECT_EXISTENCE";
        };
    }

    public Verdict decideAccess(Identity identity, ObjectCategory category, boolean known) {
        if (category == null || category == ObjectCategory.UNKNOWN) {
            return new Verdict(REJECTED, "OBJECT_NOT_FOUND", "Объект не найден в реестре.", "MEDIUM");
        }
        // Audit L-3: a category guessed from a prefix is NOT a real object. Only grant access
        // to objects actually backed by data (DB row or curated registry); otherwise the
        // platform would "approve" access to phantom identifiers that don't exist.
        if (!known) {
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
