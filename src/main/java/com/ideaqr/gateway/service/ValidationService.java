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
    private static final int TRUST_LEGAL = 80; // government tier — police accounts are seeded at 90
    private static final int TRUST_RETAIL = 50; // кассир — гражданский уровень доверия

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
        return decideAccess(identity, category, known, organizationUid, true, null);
    }

    /**
     * Full entry point used by the live pipeline. Layers two MVP demo levers on top of the
     * pure policy:
     * <ul>
     *   <li><b>Working mode</b> — professional categories (medical / infrastructure) are only
     *       reachable when the actor is in WORKING mode, so the mode toggle actually gates
     *       something instead of being decorative;</li>
     *   <li><b>overrideHour</b> — an optional session-scoped "time machine" hour (dev panel),
     *       set server-side, so the working-hours gate can be demonstrated live.
     *       {@code null} ⇒ server clock (un-spoofable, audit 4.3).</li>
     * </ul>
     */
    public Verdict decideAccess(Identity identity, ObjectCategory category, boolean known,
                                UUID organizationUid, boolean workingMode, Integer overrideHour) {
        return coreDecide(identity, category, known, workingMode, overrideHour);
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
            case LEGAL -> "LEGAL_ACCESS";
            case INFRASTRUCTURE -> "INFRASTRUCTURE_ACCESS";
            case RETAIL, ECO, GENERAL -> "PUBLIC_ACCESS";
            default -> "OBJECT_EXISTENCE";
        };
    }

    public Verdict decideAccess(Identity identity, ObjectCategory category, boolean known) {
        return coreDecide(identity, category, known, true, null);
    }

    /**
     * УНИВЕРСАЛЬНОЕ ПРАВИЛО ОБЪЕКТОВ (BLOCK 2.4) — служебное раскрытие данных владельца
     * вещи уполномоченной роли <b>без согласия владельца</b>. Ворота сознательно те же,
     * что у правового досье (LEGAL): роль POLICE + государственный уровень доверия +
     * рабочий режим + рабочее время — полицейский вне смены остаётся просто гражданином.
     * Отказ здесь не «ошибка», а тихое понижение скана до обычной расширенной карточки.
     */
    public Verdict authorityDisclosure(Identity identity, boolean workingMode, Integer overrideHour) {
        Set<RoleType> roles = identity.getRoles();
        int hour = overrideHour != null ? overrideHour : LocalTime.now(clock).getHour();
        boolean workingHours = hour >= WORK_START && hour < WORK_END;
        if (!roles.contains(RoleType.POLICE)) {
            return new Verdict(REJECTED, "ROLE_REQUIRED_POLICE",
                    "Служебный доступ к данным владельца разрешён только уполномоченным органам.", "HIGH");
        }
        if (identity.getTrustLevel() < TRUST_LEGAL) {
            return new Verdict(REJECTED, "TRUST_TOO_LOW",
                    "Недостаточный уровень доверия для служебного доступа.", "HIGH");
        }
        if (!workingMode) {
            return new Verdict(REJECTED, "WORKING_MODE_REQUIRED",
                    "Служебный доступ возможен только при исполнении (рабочий режим).", "MEDIUM");
        }
        if (!workingHours) {
            return new Verdict(REJECTED, "OUTSIDE_WORKING_HOURS",
                    "Служебный доступ возможен только в рабочее время (08:00–18:00).", "MEDIUM");
        }
        return new Verdict(APPROVED, "AUTHORITY_DISCLOSURE",
                "Служебный доступ: роль (полиция), доверие, рабочий режим и время подтверждены.", "HIGH");
    }

    /**
     * СЦЕНАРИЙ «БИЗНЕС И МАГАЗИНЫ» — кассовый доступ: кассир при исполнении сканирует
     * личный QR покупателя и видит ТОЛЬКО его открытые покупки (корзина + «оплачен, не
     * выдан») — ни профиля, ни истории. Те же четыре классических ворот, что и у других
     * профессиональных представлений: роль + доверие + рабочий режим + рабочее время;
     * кассир вне смены — просто гражданин (скан уйдёт в обычную визитку).
     */
    public Verdict retailCheckout(Identity identity, boolean workingMode, Integer overrideHour) {
        Set<RoleType> roles = identity.getRoles();
        int hour = overrideHour != null ? overrideHour : LocalTime.now(clock).getHour();
        boolean workingHours = hour >= WORK_START && hour < WORK_END;
        if (!roles.contains(RoleType.CASHIER)) {
            return new Verdict(REJECTED, "ROLE_REQUIRED_CASHIER",
                    "Кассовый доступ к покупкам клиента разрешён только кассирам.", "MEDIUM");
        }
        if (identity.getTrustLevel() < TRUST_RETAIL) {
            return new Verdict(REJECTED, "TRUST_TOO_LOW",
                    "Недостаточный уровень доверия для кассовых операций.", "MEDIUM");
        }
        if (!workingMode) {
            return new Verdict(REJECTED, "WORKING_MODE_REQUIRED",
                    "Касса доступна только в рабочем режиме. Перейдите в рабочий режим.", "MEDIUM");
        }
        if (!workingHours) {
            return new Verdict(REJECTED, "OUTSIDE_WORKING_HOURS",
                    "Кассовые операции возможны только в рабочее время (08:00–18:00).", "MEDIUM");
        }
        return new Verdict(APPROVED, "RETAIL_CHECKOUT",
                "Касса: роль (кассир), доверие, рабочий режим и время подтверждены.", "LOW");
    }

    private Verdict coreDecide(Identity identity, ObjectCategory category, boolean known,
                               boolean workingMode, Integer overrideHour) {
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
        // Security (audit 4.3): the time gate uses the SERVER clock by default and there is no
        // client-supplied hour on the request, so the policy cannot be spoofed. overrideHour is
        // an optional session-scoped demo lever (dev "time machine"), itself set server-side.
        int hour = overrideHour != null ? overrideHour : LocalTime.now(clock).getHour();
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
                // The mode toggle now gates professional access: a specialist must be ON the
                // clock (working mode) to open a patient's card — not in personal mode.
                if (!workingMode) {
                    yield new Verdict(REJECTED, "WORKING_MODE_REQUIRED",
                            "Медицинская карта доступна только в рабочем режиме. Перейдите в рабочий режим.", "MEDIUM");
                }
                if (!workingHours) {
                    yield new Verdict(REJECTED, "OUTSIDE_WORKING_HOURS",
                            "Доступ к медицинской карте возможен только в рабочее время (08:00–18:00).", "MEDIUM");
                }
                yield new Verdict(APPROVED, "ACCESS_GRANTED",
                        "Проверка пройдена: роль (врач/фармацевт), доверие, рабочий режим и время.", "MEDIUM");
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
                if (!workingMode) {
                    yield new Verdict(REJECTED, "WORKING_MODE_REQUIRED",
                            "Инфраструктурный объект доступен только в рабочем режиме. Перейдите в рабочий режим.", "MEDIUM");
                }
                if (!workingHours) {
                    yield new Verdict(REJECTED, "OUTSIDE_WORKING_HOURS",
                            "Доступ к инфраструктурному объекту возможен только в рабочее время (08:00–18:00).", "MEDIUM");
                }
                yield new Verdict(APPROVED, "ACCESS_GRANTED",
                        "Проверка пройдена: роль, доверие, рабочий режим и время.", "MEDIUM");
            }
            case LEGAL -> {
                // Правовое досье (справка о несудимости, штрафы, розыск) — уровень SECRET.
                // Доступно только полиции при исполнении: роль + доверие + рабочий режим + время.
                if (!roles.contains(RoleType.POLICE)) {
                    yield new Verdict(REJECTED, "ROLE_REQUIRED_POLICE",
                            "Доступ к правовым данным разрешён только сотрудникам полиции.", "HIGH");
                }
                if (trust < TRUST_LEGAL) {
                    yield new Verdict(REJECTED, "TRUST_TOO_LOW",
                            "Недостаточный уровень доверия для доступа к правовым данным.", "HIGH");
                }
                if (!workingMode) {
                    yield new Verdict(REJECTED, "WORKING_MODE_REQUIRED",
                            "Правовое досье доступно только при исполнении (рабочий режим).", "MEDIUM");
                }
                if (!workingHours) {
                    yield new Verdict(REJECTED, "OUTSIDE_WORKING_HOURS",
                            "Доступ к правовому досье возможен только в рабочее время (08:00–18:00).", "MEDIUM");
                }
                yield new Verdict(APPROVED, "ACCESS_GRANTED",
                        "Проверка пройдена: роль (полиция), доверие, рабочий режим и время.", "HIGH");
            }
            case RETAIL, ECO, GENERAL -> new Verdict(APPROVED, "PUBLIC_OBJECT",
                    "Объект общедоступен. Данные предоставлены.", "LOW");
            default -> new Verdict(REJECTED, "OBJECT_NOT_FOUND", "Объект не найден в реестре.", "MEDIUM");
        };
    }
}
