package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.enums.DecisionOutcome;
import com.ideaqr.gateway.domain.enums.ObjectCategory;
import com.ideaqr.gateway.domain.enums.RequestType;
import com.ideaqr.gateway.domain.enums.RoleType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalTime;

/**
 * The rules / decision engine. This is the heart of "execution governance":
 * it does not merely check whether a subject <em>has</em> a right, it checks
 * whether the action is <em>admissible</em> in the current context — combining
 * role, request type, object category, working-hours policy, trust level and a
 * derived risk score into a single {@link DecisionResult}.
 *
 * <p>Every reason returned to the user is written in natural Russian; the
 * machine-readable {@code reasonCode} stays in English for logs and analytics.</p>
 */
@Service
public class ValidationService {

    @Value("${ideaqr.policy.working-hours.start:8}")
    private int workingHoursStart;

    @Value("${ideaqr.policy.working-hours.end:18}")
    private int workingHoursEnd;

    @Value("${ideaqr.policy.min-trust.medical:70}")
    private int minTrustMedical;

    @Value("${ideaqr.policy.min-trust.infrastructure:70}")
    private int minTrustInfrastructure;

    /**
     * Immutable outcome of a policy evaluation.
     *
     * @param outcome    APPROVED / REJECTED / REVIEW
     * @param reasonCode machine-readable code (English)
     * @param reason     human-readable explanation shown to the user (Russian)
     * @param riskLevel  LOW / MEDIUM / HIGH / CRITICAL
     */
    public record DecisionResult(DecisionOutcome outcome, String reasonCode, String reason, String riskLevel) {

        public static DecisionResult approved(String reasonCode, String reason, String riskLevel) {
            return new DecisionResult(DecisionOutcome.APPROVED, reasonCode, reason, riskLevel);
        }

        public static DecisionResult rejected(String reasonCode, String reason, String riskLevel) {
            return new DecisionResult(DecisionOutcome.REJECTED, reasonCode, reason, riskLevel);
        }

        public static DecisionResult review(String reasonCode, String reason, String riskLevel) {
            return new DecisionResult(DecisionOutcome.REVIEW, reasonCode, reason, riskLevel);
        }
    }

    // ------------------------------------------------------------------
    //  QR creation governance (admin panel)
    // ------------------------------------------------------------------

    /**
     * Decide whether an identity may govern (create) a new QR-coded object.
     * Only identities holding a governing role (ADMIN or RETAIL_ADMIN) pass.
     */
    public DecisionResult decideQrCreation(Identity admin) {
        if (admin != null && (admin.getRoles().contains(RoleType.ADMIN)
                || admin.getRoles().contains(RoleType.RETAIL_ADMIN))) {
            return DecisionResult.approved(
                    "QR_CREATION_GRANTED",
                    "Доступ к управлению объектами подтверждён. QR-код можно сгенерировать.",
                    "LOW");
        }
        return DecisionResult.rejected(
                "QR_CREATION_FORBIDDEN",
                "Недостаточно прав для создания QR-объектов. Требуется роль администратора.",
                "MEDIUM");
    }

    // ------------------------------------------------------------------
    //  Access governance (citizen / specialist scans)
    // ------------------------------------------------------------------

    /**
     * Evaluate an access request against the full rule set.
     *
     * @param identity    the acting subject
     * @param requestType the type of request (ACCESS / REPORT_ISSUE …)
     * @param objectUid   the scanned object identifier
     * @param category    the resolved object category
     * @param contextHour optional hour-of-day override (0–23) for demonstrating
     *                    the time gate; when {@code null}, the live clock is used
     */
    public DecisionResult decide(Identity identity,
                                 RequestType requestType,
                                 String objectUid,
                                 ObjectCategory category,
                                 Integer contextHour) {
        if (category == null || category == ObjectCategory.UNKNOWN) {
            return DecisionResult.review(
                    "OBJECT_UNKNOWN",
                    "Объект не найден в реестрах. Запрос направлен на ручную проверку.",
                    "MEDIUM");
        }

        return switch (category) {
            case MEDICAL -> decideMedical(identity, contextHour);
            case INFRASTRUCTURE -> decideInfrastructure(identity, contextHour);
            case RETAIL -> DecisionResult.approved(
                    "RETAIL_PUBLIC",
                    "Карточка товара общедоступна. Доступ разрешён.",
                    "LOW");
            case ECO -> DecisionResult.approved(
                    "ECO_PUBLIC",
                    "Данные об экологическом объекте общедоступны. Доступ разрешён.",
                    "LOW");
            default -> DecisionResult.approved(
                    "GENERAL_PUBLIC",
                    "Объект общего назначения. Доступ разрешён.",
                    "LOW");
        };
    }

    private DecisionResult decideMedical(Identity identity, Integer contextHour) {
        boolean isDoctor = identity != null && identity.getRoles().contains(RoleType.DOCTOR);
        if (!isDoctor) {
            return DecisionResult.rejected(
                    "MEDICAL_ROLE_REQUIRED",
                    "Доступ к медицинской карте разрешён только верифицированным врачам.",
                    "HIGH");
        }
        if (identity.getTrustLevel() < minTrustMedical) {
            return DecisionResult.rejected(
                    "MEDICAL_TRUST_TOO_LOW",
                    "Недостаточный уровень доверия личности для доступа к медицинским данным (требуется не менее "
                            + minTrustMedical + ").",
                    "HIGH");
        }
        if (!isWithinWorkingHours(contextHour)) {
            return DecisionResult.rejected(
                    "MEDICAL_OUTSIDE_HOURS",
                    "Запрос вне рабочего окна (" + formatWindow() + "). Доступ к медицинским данным запрещён по политике.",
                    "HIGH");
        }
        return DecisionResult.approved(
                "MEDICAL_GRANTED",
                "Личность врача подтверждена, контекст допустим. Доступ к медицинской карте разрешён.",
                "LOW");
    }

    private DecisionResult decideInfrastructure(Identity identity, Integer contextHour) {
        boolean isTechnician = identity != null
                && (identity.getRoles().contains(RoleType.ENGINEER)
                || identity.getRoles().contains(RoleType.INSPECTOR));
        if (!isTechnician) {
            return DecisionResult.rejected(
                    "INFRA_ROLE_REQUIRED",
                    "Доступ к инфраструктурному объекту разрешён только инженерам и инспекторам.",
                    "HIGH");
        }
        if (identity.getTrustLevel() < minTrustInfrastructure) {
            return DecisionResult.rejected(
                    "INFRA_TRUST_TOO_LOW",
                    "Недостаточный уровень доверия личности для доступа к инфраструктуре (требуется не менее "
                            + minTrustInfrastructure + ").",
                    "HIGH");
        }
        if (!isWithinWorkingHours(contextHour)) {
            return DecisionResult.rejected(
                    "INFRA_OUTSIDE_HOURS",
                    "Запрос вне рабочего окна (" + formatWindow() + "). Доступ к инфраструктуре запрещён по политике.",
                    "MEDIUM");
        }
        return DecisionResult.approved(
                "INFRA_GRANTED",
                "Личность технического специалиста подтверждена, контекст допустим. Доступ разрешён.",
                "LOW");
    }

    /**
     * Report-issue requests are intentionally open: any citizen may report a
     * broken or overfilled public object. This keeps the civic feedback loop
     * frictionless while still routing the action through Request → Decision.
     */
    public DecisionResult decideReport(Identity identity, ObjectCategory category) {
        return DecisionResult.approved(
                "REPORT_ACCEPTED",
                "Обращение принято в обработку. Спасибо за участие.",
                "LOW");
    }

    // ------------------------------------------------------------------
    //  Context helpers
    // ------------------------------------------------------------------

    private boolean isWithinWorkingHours(Integer contextHour) {
        int hour = (contextHour != null) ? Math.floorMod(contextHour, 24) : LocalTime.now().getHour();
        return hour >= workingHoursStart && hour < workingHoursEnd;
    }

    private String formatWindow() {
        return String.format("%02d:00–%02d:00", workingHoursStart, workingHoursEnd);
    }
}
