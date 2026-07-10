package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.Decision;
import com.ideaqr.gateway.domain.History;
import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.Interaction;
import com.ideaqr.gateway.domain.Organization;
import com.ideaqr.gateway.domain.RequestRecord;
import com.ideaqr.gateway.domain.User;
import com.ideaqr.gateway.domain.Workflow;
import com.ideaqr.gateway.domain.enums.DataClassification;
import com.ideaqr.gateway.domain.enums.DecisionOutcome;
import com.ideaqr.gateway.domain.enums.EventSource;
import com.ideaqr.gateway.domain.enums.EventType;
import com.ideaqr.gateway.domain.enums.HistoryEventType;
import com.ideaqr.gateway.domain.enums.IdentityType;
import com.ideaqr.gateway.domain.enums.InteractionStatus;
import com.ideaqr.gateway.domain.enums.ObjectCategory;
import com.ideaqr.gateway.domain.enums.RequestStatus;
import com.ideaqr.gateway.domain.enums.RequestType;
import com.ideaqr.gateway.domain.enums.SessionMode;
import com.ideaqr.gateway.dto.GatewayResponse;
import com.ideaqr.gateway.dto.ReportRequest;
import com.ideaqr.gateway.dto.ScanRequest;
import com.ideaqr.gateway.util.PublicCard;
import com.ideaqr.gateway.domain.RegistryObject;
import com.ideaqr.gateway.repository.DecisionRepository;
import com.ideaqr.gateway.repository.IdentityRepository;
import com.ideaqr.gateway.repository.InteractionRepository;
import com.ideaqr.gateway.repository.RequestRepository;
import com.ideaqr.gateway.repository.UserRepository;
import com.ideaqr.gateway.repository.WorkflowRepository;
import com.ideaqr.gateway.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Heart of the platform. Drives every scan and report through the governance
 * pipeline (Identity → Request → Decision → Interaction → History) and returns
 * the verdict, the localized reason, the risk level and the full chain of UUIDs.
 * Denied requests still return a populated response (HTTP 200, {@code success:false}).
 */
@Service
@RequiredArgsConstructor
public class GatewayService {

    /** QR value prefix that identifies a person's primary identity QR. */
    private static final String IDENTITY_PREFIX = "IDENTITY:";

    /** Interaction type for a doctor/pharmacist's pending request for a patient's consent. */
    private static final String MEDICAL_SCAN_TYPE = "MEDICAL_SCAN";

    /**
     * Interaction type for the P2P owner-consent flow of the УНИВЕРСАЛЬНОЕ ПРАВИЛО ОБЪЕКТОВ:
     * a registered user pressed «Профиль владельца» on an object card; the owner must
     * confirm before their profile opens (same Owner-Approval machinery as PROFILE_SCAN).
     */
    private static final String OWNER_PROFILE_TYPE = "OWNER_PROFILE";

    /** Card-payload key carrying the patient of record whose consent governs a medical card. */
    private static final String PATIENT_KEY = "patientIdentityUid";

    private final RegistryClient registryClient;
    private final ValidationService validationService;
    private final AuditService auditService;
    private final EventService eventService;
    private final RequestRepository requestRepository;
    private final DecisionRepository decisionRepository;
    private final InteractionRepository interactionRepository;
    private final IdentityRepository identityRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final WorkflowRepository workflowRepository;
    private final OrganizationService organizationService;
    private final SessionService sessionService;
    private final DevTimeService devTimeService;
    private final MedicalService medicalService;
    private final CitizenDossierService citizenDossierService;
    private final AiCardService aiCardService;

    @Transactional
    public GatewayResponse scan(Identity identity, ScanRequest request) {
        String objectUid = normalizeIdentifier(request.getObjectUid());

        // Person-to-person: scanning another user's primary QR is a profile-access
        // request that the owner must confirm — not a direct object read.
        if (objectUid.toUpperCase().startsWith(IDENTITY_PREFIX)) {
            return scanIdentityProfile(identity, objectUid);
        }

        RegistryClient.Resolved resolved = registryClient.resolve(objectUid);

        // Golden pipeline: Identifier → Identity/Object → Role → ORGANIZATION → Request → …
        // Resolve and stamp the organisation the actor is governed under (Doc 22 / pipeline).
        Organization actingOrg = organizationService.resolveActingOrganization(identity.getIdentityUid());
        UUID organizationUid = actingOrg != null ? actingOrg.getOrganizationUid() : null;
        boolean guest = identity.getIdentityType() == IdentityType.GUEST;

        // Two MVP demo levers now feed the policy engine, so the mode toggle and the
        // working-hours window actually gate something live (not decorative buttons):
        //   • working mode — professional categories require the actor to be ON the clock;
        //   • mock hour    — the session "time machine" (dev panel), server-side only.
        boolean workingMode = sessionService.current(identity.getIdentityUid()).getMode() == SessionMode.WORKING;
        Integer overrideHour = devTimeService.currentMockHour();
        ValidationService.Verdict verdict = validationService.decideAccess(
                identity, resolved.category(), resolved.known(), organizationUid, workingMode, overrideHour);
        boolean approved = verdict.outcome() == DecisionOutcome.APPROVED;

        // OWNER OVERRIDE (Phase 2 — единый QR): субъект данных всегда вправе открыть СВОЮ
        // медкарту и СВОЁ правовое досье (справка о несудимости о самом себе) из личного
        // кабинета. Профессиональные ворота (роль врача/полиции) охраняют ЧУЖИЕ данные;
        // для владельца отказ по роли заменяется решением OWNER_ACCESS — с полной записью
        // в журнал, как и любое другое решение.
        UUID subjectUid = subjectOfRecord(resolved.data());
        boolean ownData = subjectUid != null && subjectUid.equals(identity.getIdentityUid());
        if (!approved && !guest && ownData
                && (resolved.category() == ObjectCategory.MEDICAL || resolved.category() == ObjectCategory.LEGAL)) {
            verdict = new ValidationService.Verdict(DecisionOutcome.APPROVED, "OWNER_ACCESS",
                    "Вы — субъект этих данных: доступ владельцу предоставлен.", "LOW");
            approved = true;
        }

        // PATIENT CONSENT (P0 — securing medical records): passing the professional gates
        // (role + trust + working mode + hours) is necessary but NOT sufficient to open a
        // medical card. A medical record carries a patient of record; before it is revealed,
        // that patient must give explicit consent — the same Owner-Approval model as a
        // personal-profile QR. This makes the MOST sensitive category the MOST consented and
        // closes the audit's "consent paradox" (a business card asked permission, a medical
        // card did not). The scanner gets REVIEW and polls until the patient decides.
        if (approved && !guest && resolved.category() == ObjectCategory.MEDICAL) {
            UUID patient = patientOfRecord(resolved.data());
            if (patient != null && !patient.equals(identity.getIdentityUid())) {
                return requestMedicalConsent(identity, objectUid, resolved.displayName(), patient, organizationUid);
            }
        }

        RequestRecord req = requestRepository.save(RequestRecord.builder()
                .identityUid(identity.getIdentityUid())
                .organizationUid(organizationUid)
                .objectUid(objectUid)
                .requestType(RequestType.ACCESS)
                .status(RequestStatus.PENDING)
                .build());

        // Tiered visibility (Scenario #1 / ПУБЛИЧНАЯ СТРАНИЦА): the access *decision* is
        // identical for everyone, but a GUEST only ever receives the public projection of
        // the card (name / image / short description / rating). A registered identity gets
        // the full payload; sensitive fields (price, reviews, history, supplier) are
        // stripped for guests by PublicCard's default-deny whitelist.
        Object payload = null;
        String accessTier = null;
        boolean registrationRequired = false;
        String cta = null;
        if (approved) {
            if (guest) {
                payload = PublicCard.project(resolved.data());
                accessTier = "PUBLIC";
                registrationRequired = true;
                cta = "Для продолжения взаимодействия необходимо зарегистрироваться.";
            } else {
                payload = resolved.data();
                accessTier = "FULL";
            }
        }

        // Doctor ↔ Pharmacist: surface live prescriptions on an approved (full-access) medical
        // card so the therapist-writes / pharmacist-dispenses flow is visible on the scan result.
        if (approved && !guest && resolved.category() == ObjectCategory.MEDICAL && payload instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked")
            Map<String, Object> card = (Map<String, Object>) payload;
            card.put("prescriptions", medicalService.listForObject(objectUid));
        }

        // ==============================================================
        // УНИВЕРСАЛЬНОЕ ПРАВИЛО ОБЪЕКТОВ (Object Governance): у каждой вещи — один
        // неизменяемый QR, а КАРТОЧКА зависит от того, КТО сканирует:
        //   • гость            → только публичная карточка, ноль сведений о владельце;
        //   • пользователь     → расширенная карточка + кнопка «Профиль владельца»
        //                        (P2P-согласие через Request → Decision) + AI-карточка;
        //   • владелец         → мгновенный полный доступ и управление, без запросов;
        //   • полиция при исп. → данные владельца БЕЗ согласия, с жёсткой фиксацией
        //                        в History/Audit и уведомлением владельца.
        // ==============================================================
        ObjectRouting routing = null;
        if (approved && AiCardService.ITEM_CATEGORIES.contains(resolved.category())) {
            routing = routeObjectView(identity, resolved, objectUid, guest, workingMode, overrideHour);
        }

        Decision decision = decisionRepository.save(Decision.builder()
                .requestUid(req.getRequestUid())
                .identityUid(identity.getIdentityUid())
                .outcome(verdict.outcome())
                .reasonCode(verdict.reasonCode())
                .reason(verdict.reason())
                .riskLevel(verdict.riskLevel())
                .build());

        // Служебный скан помечает владельца целью взаимодействия: раскрытие его данных
        // появляется у него в «кто сканировал меня» — прозрачность по умолчанию.
        boolean authorityView = routing != null && routing.authority();
        Interaction interaction = interactionRepository.save(Interaction.builder()
                .identityUid(identity.getIdentityUid())
                .requestUid(req.getRequestUid())
                .objectUid(objectUid)
                .targetIdentityUid(authorityView ? routing.ownerUid() : null)
                .interactionType("SCAN")
                .detail(authorityView
                        ? "СЛУЖЕБНЫЙ СКАН объекта " + objectUid + ": раскрыты данные владельца (полиция при исполнении)"
                        : "Сканирование объекта " + objectUid + " → " + verdict.outcome().name()
                        + (approved ? " [" + accessTier + "]" : ""))
                .build());

        req.setStatus(approved ? RequestStatus.PROCESSED : RequestStatus.FAILED);
        requestRepository.save(req);

        HistoryEventType eventType = switch (verdict.outcome()) {
            case APPROVED -> HistoryEventType.ACCESS_GRANTED;
            case REJECTED -> HistoryEventType.ACCESS_DENIED;
            case REVIEW -> HistoryEventType.ACCESS_REVIEW;
        };
        History history = auditService.record(identity.getIdentityUid(), objectUid, eventType,
                verdict.reason(), req.getRequestUid(), decision.getDecisionUid(), interaction.getInteractionUid());

        EventType evt = switch (verdict.outcome()) {
            case APPROVED -> EventType.DECISION_APPROVED;
            case REJECTED -> EventType.DECISION_REJECTED;
            case REVIEW -> EventType.DECISION_REVIEW;
        };
        eventService.record(evt, identity.getIdentityUid(), objectUid, interaction.getInteractionUid(),
                "Скан объекта " + objectUid + " → " + verdict.outcome().name(), EventSource.QR_SCAN);

        // СТРОГОЕ ТРЕБОВАНИЕ (BLOCK 2.4): служебное раскрытие данных владельца фиксируется
        // НЕЗАВИСИМОЙ записью в неизменяемом hash-chain журнале НА ИМЯ ВЛАДЕЛЬЦА (владелец
        // видит её в своей истории), плюс машинное событие и уведомление. Полицейский след
        // невозможно оставить «незаметно»: его собственный скан уже записан выше.
        if (authorityView) {
            // Запись адресована ВЛАДЕЛЬЦУ — стемпим её его тенантом (bug №2 прод-аудита):
            // иначе строка получала тенант полицейского и tenant-фильтр прятал её из
            // личного журнала владельца, хотя она существовала в глобальной цепочке.
            auditService.record(routing.ownerUid(), objectUid, HistoryEventType.ACCESS_GRANTED,
                    "СЛУЖЕБНЫЙ ДОСТУП: " + displayName(identity.getIdentityUid())
                            + " (полиция, при исполнении) получил данные владельца объекта «"
                            + resolved.displayName() + "» без согласия — в объёме, установленном законом.",
                    req.getRequestUid(), decision.getDecisionUid(), interaction.getInteractionUid(),
                    tenantOf(routing.ownerUid()));
            eventService.record(EventType.PROFILE_OPENED, identity.getIdentityUid(), objectUid,
                    interaction.getInteractionUid(),
                    "Служебное раскрытие данных владельца объекта (уполномоченный орган)");
            notificationService.notify(routing.ownerUid(), "AUTHORITY_ACCESS",
                    "Уполномоченный орган (полиция) получил служебный доступ к вашим данным как владельца объекта «"
                            + resolved.displayName() + "». Действие зафиксировано в журнале.");
        }

        return GatewayResponse.builder()
                .success(approved)
                .outcome(verdict.outcome().name())
                .reason(verdict.reason())
                .riskLevel(verdict.riskLevel())
                .category(resolved.category().name())
                .dataClassification(DataClassification.forCategory(resolved.category()).name())
                .policy(validationService.governingPolicy(resolved.category()))
                .objectUid(objectUid)
                .data(payload)
                .identityUid(identity.getIdentityUid().toString())
                .organizationUid(organizationUid != null ? organizationUid.toString() : null)
                .organizationName(actingOrg != null ? actingOrg.getName() : null)
                .requestUid(req.getRequestUid().toString())
                .decisionUid(decision.getDecisionUid().toString())
                .interactionUid(interaction.getInteractionUid().toString())
                .historyUid(history.getHistoryUid().toString())
                .accessTier(accessTier)
                .registrationRequired(registrationRequired)
                .cta(cta)
                .contextView(routing != null ? routing.view() : null)
                .ownership(routing != null ? routing.ownership() : null)
                .ownerDisclosure(routing != null ? routing.ownerDisclosure() : null)
                .aiCard(routing != null ? routing.aiCard() : null)
                .build();
    }

    // ------------------------------------------------------------------
    //  УНИВЕРСАЛЬНОЕ ПРАВИЛО ОБЪЕКТОВ — контекстная маршрутизация карточки вещи
    // ------------------------------------------------------------------

    /**
     * Какое представление объекта получает сканирующий. {@code ownership} никогда не несёт
     * идентификаторов/имени владельца — только доступные действия; сведения о владельце
     * существуют лишь в {@code ownerDisclosure} (уполномоченные роли) либо открываются через
     * P2P-согласие ({@link #requestOwnerProfile}).
     */
    private record ObjectRouting(String view, Map<String, Object> ownership,
                                 Map<String, Object> ownerDisclosure, Map<String, Object> aiCard,
                                 UUID ownerUid) {
        boolean authority() {
            return "OBJECT_AUTHORITY".equals(view);
        }
    }

    private ObjectRouting routeObjectView(Identity scanner, RegistryClient.Resolved resolved,
                                          String objectUid, boolean guest,
                                          boolean workingMode, Integer overrideHour) {
        // 1. ГОСТЬ: публичная карточка. КАТЕГОРИЧЕСКИ никакой информации о владельце —
        //    ни имени, ни идентификаторов, ни даже факта, что владелец существует.
        if (guest) {
            return new ObjectRouting("OBJECT_PUBLIC", null, null, null, null);
        }

        RegistryObject db = resolved.dbObject();
        UUID ownerUid = db != null ? db.getOwnerIdentityUid() : null;

        // 3. ВЛАДЕЛЕЦ: без запросов и согласий — мгновенный полный доступ к управлению.
        if (ownerUid != null && ownerUid.equals(scanner.getIdentityUid())) {
            Map<String, Object> ownership = new LinkedHashMap<>();
            ownership.put("state", "OWNED");
            ownership.put("isOwner", true);
            ownership.put("transferAvailable", !CitizenDossierService.isDossierObject(objectUid));
            ownership.put("objectStatus", db.getStatus() != null ? db.getStatus().name() : null);
            ownership.put("objectTrustScore", db.getTrustScore());
            ownership.put("note", "Вы — владелец. QR объекта постоянен: при передаче прав меняется только владелец.");
            return new ObjectRouting("OBJECT_OWNER", ownership, null,
                    aiCardService.generate(scanner, objectUid, resolved.category(), resolved.data(), true),
                    ownerUid);
        }

        // 4. УПОЛНОМОЧЕННЫЙ ОРГАН (полиция при исполнении): установленный законом объём
        //    данных владельца БЕЗ его согласия. Отказ по воротам тихо понижает скан до
        //    обычной расширенной карточки — полицейский вне смены просто гражданин.
        if (ownerUid != null && validationService.authorityDisclosure(scanner, workingMode, overrideHour)
                .outcome() == DecisionOutcome.APPROVED) {
            Map<String, Object> ownership = new LinkedHashMap<>();
            ownership.put("state", "OWNED");
            ownership.put("isOwner", false);
            ownership.put("note", "Служебный доступ: данные владельца раскрыты по полномочию, действие зафиксировано.");
            return new ObjectRouting("OBJECT_AUTHORITY", ownership, buildOwnerDisclosure(ownerUid), null, ownerUid);
        }

        // 2. ЗАРЕГИСТРИРОВАННЫЙ ПОЛЬЗОВАТЕЛЬ: расширенная карточка + AI. Владелец скрыт;
        //    доступна лишь кнопка «Профиль владельца» (P2P-согласие) либо, для бесхозной
        //    вещи (pre-ownership), оформление владения — QR при этом не меняется.
        Map<String, Object> ownership = new LinkedHashMap<>();
        ownership.put("state", ownerUid != null ? "OWNED" : "UNOWNED");
        ownership.put("isOwner", false);
        ownership.put("ownerRequestAvailable", ownerUid != null);
        boolean claimable = ownerUid == null
                && !CitizenDossierService.isDossierObject(objectUid)
                && (resolved.category() == ObjectCategory.RETAIL
                        || (resolved.data() != null && resolved.data().get("itemType") != null));
        ownership.put("claimAvailable", claimable);
        ownership.put("note", ownerUid != null
                ? "Профиль владельца откроется только с его согласия (Request → Decision → History)."
                : "Объект пока никому не принадлежит (pre-ownership): открыта стандартная карточка.");
        return new ObjectRouting("OBJECT_EXTENDED", ownership, null,
                aiCardService.generate(scanner, objectUid, resolved.category(), resolved.data(), false),
                ownerUid);
    }

    /**
     * Установленный законом объём сведений о владельце для уполномоченной роли: ФИО, ИИН,
     * телефон, адрес регистрации, документы (из правового досье и визитки владельца).
     * Вызывается ТОЛЬКО из ветки OBJECT_AUTHORITY — каждое построение сопровождается
     * журнальными записями в {@link #scan}.
     */
    private Map<String, Object> buildOwnerDisclosure(UUID ownerUid) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("fullName", displayName(ownerUid));
        d.put("identityUid", ownerUid.toString());
        identityRepository.findById(ownerUid).ifPresent(owner -> {
            ensureDossier(owner);
            d.put("trustLevel", owner.getTrustLevel());
        });
        citizenDossierService.find(CitizenDossierService.vcardUidFor(ownerUid))
                .map(citizenDossierService::payload)
                .ifPresent(v -> {
                    if (v.get("phone") != null) d.put("phone", v.get("phone"));
                    if (v.get("city") != null) d.put("city", v.get("city"));
                });
        citizenDossierService.find(CitizenDossierService.legalUidFor(ownerUid))
                .map(citizenDossierService::payload)
                .ifPresent(l -> {
                    if (l.get("iin") != null) d.put("iin", l.get("iin"));
                    if (l.get("birthDate") != null) d.put("birthDate", l.get("birthDate"));
                    if (l.get("address") != null) d.put("registrationAddress", l.get("address"));
                    if (l.get("drivingLicense") != null) d.put("drivingLicense", l.get("drivingLicense"));
                });
        d.put("legalBasis", "Служебный доступ уполномоченного органа — объём, установленный законом (демо-режим).");
        d.put("auditNotice", "Раскрытие необратимо зафиксировано в журнале History/Audit; владелец уведомлён.");
        return d;
    }

    /**
     * BLOCK 2.2 — кнопка «Профиль владельца» на карточке объекта. Информация НЕ открывается
     * сразу: создаётся Request в REVIEW, владелец получает уведомление и решает (Decision).
     * Профиль раскрывается сканирующему только после подтверждения — через тот же
     * Owner-Approval-конвейер, что и личный QR ({@code /api/v2/access/{id}/result}).
     */
    @Transactional
    public GatewayResponse requestOwnerProfile(Identity requester, String rawObjectUid) {
        if (requester.getIdentityType() == IdentityType.GUEST) {
            throw new AccessDeniedException("Профиль владельца доступен только зарегистрированным пользователям.");
        }
        String objectUid = normalizeIdentifier(rawObjectUid);
        RegistryClient.Resolved resolved = registryClient.resolve(objectUid);
        RegistryObject db = resolved.dbObject();
        UUID ownerUid = db != null ? db.getOwnerIdentityUid() : null;
        if (ownerUid == null) {
            throw new IllegalArgumentException("У этого объекта нет зарегистрированного владельца.");
        }
        if (ownerUid.equals(requester.getIdentityUid())) {
            throw new IllegalArgumentException("Вы и есть владелец этого объекта.");
        }

        String reason = "Запрос доступа к профилю владельца отправлен. Ожидается решение владельца.";
        Organization requesterOrg = organizationService.resolveActingOrganization(requester.getIdentityUid());
        RequestRecord req = requestRepository.save(RequestRecord.builder()
                .identityUid(requester.getIdentityUid())
                .organizationUid(requesterOrg != null ? requesterOrg.getOrganizationUid() : null)
                .objectUid(objectUid)
                .requestType(RequestType.ACCESS)
                .status(RequestStatus.REVIEW)
                .build());
        Decision decision = decisionRepository.save(Decision.builder()
                .requestUid(req.getRequestUid())
                .identityUid(requester.getIdentityUid())
                .outcome(DecisionOutcome.REVIEW)
                .reasonCode("OWNER_CONFIRMATION_REQUIRED")
                .reason(reason)
                .riskLevel("MEDIUM")
                .build());
        Interaction interaction = interactionRepository.save(Interaction.builder()
                .identityUid(requester.getIdentityUid())
                .requestUid(req.getRequestUid())
                .objectUid(objectUid)
                .targetIdentityUid(ownerUid)
                .interactionType(OWNER_PROFILE_TYPE)
                .status(InteractionStatus.PENDING)
                .detail("Запрос профиля владельца объекта «" + resolved.displayName() + "» от "
                        + displayName(requester.getIdentityUid()))
                .build());
        History history = auditService.record(requester.getIdentityUid(), objectUid,
                HistoryEventType.PROFILE_ACCESS_REQUESTED, reason,
                req.getRequestUid(), decision.getDecisionUid(), interaction.getInteractionUid());
        eventService.record(EventType.ACCESS_REQUESTED, requester.getIdentityUid(), objectUid,
                interaction.getInteractionUid(), "Запрос доступа к профилю владельца объекта");
        notificationService.notify(ownerUid, "ACCESS_REQUEST",
                "Пользователь " + displayName(requester.getIdentityUid())
                        + " запрашивает доступ к вашему профилю как владельца объекта «"
                        + resolved.displayName() + "». Подтвердите или отклоните.");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", resolved.displayName());
        data.put("description", "Нажатие кнопки — это только запрос. Профиль владельца откроется лишь после его согласия.");
        data.put("note", "Ожидается решение владельца…");
        return GatewayResponse.builder()
                .success(false)
                .outcome(DecisionOutcome.REVIEW.name())
                .reason(reason)
                .riskLevel("MEDIUM")
                .category("IDENTITY")
                .objectUid(objectUid)
                .data(data)
                .identityUid(requester.getIdentityUid().toString())
                .requestUid(req.getRequestUid().toString())
                .decisionUid(decision.getDecisionUid().toString())
                .interactionUid(interaction.getInteractionUid().toString())
                .historyUid(history.getHistoryUid().toString())
                .build();
    }

    @Transactional
    public GatewayResponse report(Identity identity, ReportRequest request) {
        String objectUid = request.getObjectUid() != null ? request.getObjectUid().trim() : "";
        RegistryClient.Resolved resolved = registryClient.resolve(objectUid);
        String reason = "Обращение по объекту принято и зарегистрировано.";

        Organization actingOrg = organizationService.resolveActingOrganization(identity.getIdentityUid());
        UUID organizationUid = actingOrg != null ? actingOrg.getOrganizationUid() : null;

        RequestRecord req = requestRepository.save(RequestRecord.builder()
                .identityUid(identity.getIdentityUid())
                .organizationUid(organizationUid)
                .objectUid(objectUid)
                .requestType(RequestType.REPORT_ISSUE)
                .status(RequestStatus.PENDING)
                .build());

        Decision decision = decisionRepository.save(Decision.builder()
                .requestUid(req.getRequestUid())
                .identityUid(identity.getIdentityUid())
                .outcome(DecisionOutcome.APPROVED)
                .reasonCode("REPORT_ACCEPTED")
                .reason(reason)
                .riskLevel("LOW")
                .build());

        String message = request.getMessage() != null && !request.getMessage().isBlank()
                ? request.getMessage().trim() : "(без описания)";
        String detail = "Обращение: " + message;
        Interaction interaction = interactionRepository.save(Interaction.builder()
                .identityUid(identity.getIdentityUid())
                .requestUid(req.getRequestUid())
                .objectUid(objectUid)
                .interactionType("REPORT")
                .detail(detail.length() > 380 ? detail.substring(0, 380) : detail)
                .build());

        req.setStatus(RequestStatus.PROCESSED);
        requestRepository.save(req);

        History history = auditService.record(identity.getIdentityUid(), objectUid, HistoryEventType.ISSUE_REPORTED,
                reason, req.getRequestUid(), decision.getDecisionUid(), interaction.getInteractionUid());

        return GatewayResponse.builder()
                .success(true)
                .outcome(DecisionOutcome.APPROVED.name())
                .reason(reason)
                .riskLevel("LOW")
                .category(resolved.category().name())
                .objectUid(objectUid)
                .identityUid(identity.getIdentityUid().toString())
                .organizationUid(organizationUid != null ? organizationUid.toString() : null)
                .organizationName(actingOrg != null ? actingOrg.getName() : null)
                .requestUid(req.getRequestUid().toString())
                .decisionUid(decision.getDecisionUid().toString())
                .interactionUid(interaction.getInteractionUid().toString())
                .historyUid(history.getHistoryUid().toString())
                .build();
    }

    @Transactional
    public GatewayResponse sos(Identity identity, String objectUid, String message) {
        String obj = objectUid != null && !objectUid.isBlank() ? objectUid.trim() : null;
        // Честная формулировка (инвест-аудит 2.6): пуш админам не уходит — сигнал попадает
        // в кросс-тенантную очередь «Тревоги (SOS)» админ-панели, где его видит администратор.
        String reason = "SOS-сигнал зафиксирован в неизменяемом журнале и передан в панель «Тревоги» администратора.";

        Organization actingOrg = organizationService.resolveActingOrganization(identity.getIdentityUid());
        UUID organizationUid = actingOrg != null ? actingOrg.getOrganizationUid() : null;

        RequestRecord req = requestRepository.save(RequestRecord.builder()
                .identityUid(identity.getIdentityUid())
                .organizationUid(organizationUid)
                .objectUid(obj)
                .requestType(RequestType.SOS)
                .status(RequestStatus.PROCESSED)
                .build());

        // Scaffolding: a critical SOS request seeds a workflow for future multi-step handling.
        workflowRepository.save(Workflow.builder()
                .requestUid(req.getRequestUid())
                .workflowType("SOS_ESCALATION")
                .status("PENDING")
                .build());

        Decision decision = decisionRepository.save(Decision.builder()
                .requestUid(req.getRequestUid())
                .identityUid(identity.getIdentityUid())
                .outcome(DecisionOutcome.APPROVED)
                .reasonCode("SOS_ESCALATED")
                .reason(reason)
                .riskLevel("CRITICAL")
                .build());

        String detail = message != null && !message.isBlank() ? "SOS: " + message.trim() : "SOS-сигнал";
        Interaction interaction = interactionRepository.save(Interaction.builder()
                .identityUid(identity.getIdentityUid())
                .requestUid(req.getRequestUid())
                .objectUid(obj)
                .interactionType("SOS")
                .detail(detail.length() > 380 ? detail.substring(0, 380) : detail)
                .build());

        History history = auditService.record(identity.getIdentityUid(), obj, HistoryEventType.SOS_CREATED,
                reason, req.getRequestUid(), decision.getDecisionUid(), interaction.getInteractionUid());

        eventService.record(EventType.SOS_CREATED, identity.getIdentityUid(), obj,
                interaction.getInteractionUid(), "SOS-запрос эскалирован");

        // The sender gets a confirmation. The REAL escalation (P0) is the Workflow row created
        // above: it is the queue item that surfaces — across every tenant — in the admin panel's
        // "Тревоги (SOS)" tab (with an unresolved-count badge), where an administrator sees the
        // emergency and marks it resolved. No more dead-end self-notification with nobody behind it.
        notificationService.notify(identity.getIdentityUid(), "SOS",
                "SOS-запрос зарегистрирован. Администраторы видят его в панели управления.");

        return GatewayResponse.builder()
                .success(true)
                .outcome(DecisionOutcome.APPROVED.name())
                .reason(reason)
                .riskLevel("CRITICAL")
                .objectUid(obj)
                .identityUid(identity.getIdentityUid().toString())
                .organizationUid(organizationUid != null ? organizationUid.toString() : null)
                .organizationName(actingOrg != null ? actingOrg.getName() : null)
                .requestUid(req.getRequestUid().toString())
                .decisionUid(decision.getDecisionUid().toString())
                .interactionUid(interaction.getInteractionUid().toString())
                .historyUid(history.getHistoryUid().toString())
                .build();
    }

    // ------------------------------------------------------------------
    //  Person-to-person profile access (scanning another user's primary QR)
    // ------------------------------------------------------------------

    /**
     * User A scanned User B's primary QR. Scanning is only a "view" — it never
     * authorises anything. We create a profile-access Request that stays in REVIEW
     * until B confirms it; B is notified and can approve or reject.
     */
    @Transactional
    public GatewayResponse scanIdentityProfile(Identity scanner, String qrValue) {
        String raw = qrValue.substring(IDENTITY_PREFIX.length()).trim();
        Identity owner = null;
        try {
            owner = identityRepository.findById(UUID.fromString(raw)).orElse(null);
        } catch (IllegalArgumentException ignored) {
            // not a UUID → handled as "not found" below
        }
        // Tenant safety net (audit H-2): findById bypasses the Hibernate @Filter, so re-check
        // the resolved identity's tenant by hand. A non-admin caller (tenant in context) may
        // only resolve identities in their own tenant or the shared PUBLIC tenant; anything
        // else is reported as "not found" so cross-tenant identities can't be enumerated.
        if (owner != null && isCrossTenant(owner)) {
            owner = null;
        }
        if (owner == null) {
            return GatewayResponse.builder()
                    .success(false).outcome(DecisionOutcome.REJECTED.name())
                    .reason("Личность по этому QR не найдена в системе.")
                    .riskLevel("MEDIUM").category("IDENTITY").objectUid(qrValue)
                    .identityUid(scanner.getIdentityUid().toString())
                    .build();
        }
        if (owner.getIdentityUid().equals(scanner.getIdentityUid())) {
            return GatewayResponse.builder()
                    .success(false).outcome(DecisionOutcome.REVIEW.name())
                    .reason("Это ваш собственный основной QR.")
                    .riskLevel("LOW").category("IDENTITY").objectUid(qrValue)
                    .identityUid(scanner.getIdentityUid().toString())
                    .build();
        }

        // ==============================================================
        // PHASE 2 — КОНТЕКСТНЫЙ QR (единый национальный QR). Один и тот же личный QR
        // раскрывает РАЗНЫЕ данные в зависимости от роли и режима сканирующего:
        //   • врач при исполнении       → медкарта (через согласие пациента);
        //   • фармацевт при исполнении  → только срез рецептов (минимальный доступ);
        //   • полицейский при исполнении→ правовое досье (без согласия — властное полномочие);
        //   • все остальные (и любой специалист в ЛИЧНОМ режиме) → публичная визитка
        //     + Owner-Approval-запрос на полный профиль.
        // Контекст = роль × рабочий режим: тот же врач вне смены — просто гражданин.
        // ==============================================================
        boolean scannerWorking = sessionService.current(scanner.getIdentityUid()).getMode() == SessionMode.WORKING;
        java.util.Set<com.ideaqr.gateway.domain.enums.RoleType> scannerRoles = scanner.getRoles();
        if (scannerWorking && scannerRoles.contains(com.ideaqr.gateway.domain.enums.RoleType.DOCTOR)) {
            return scanRoutedToDossier(scanner, owner,
                    CitizenDossierService.medicalUidFor(owner.getIdentityUid()), "MEDICAL");
        }
        if (scannerWorking && scannerRoles.contains(com.ideaqr.gateway.domain.enums.RoleType.PHARMACIST)) {
            return scanPrescriptionsView(scanner, owner);
        }
        if (scannerWorking && scannerRoles.contains(com.ideaqr.gateway.domain.enums.RoleType.POLICE)) {
            return scanRoutedToDossier(scanner, owner,
                    CitizenDossierService.legalUidFor(owner.getIdentityUid()), "LEGAL");
        }

        String reason = "Визитка открыта. Запрос на полный профиль отправлен владельцу.";
        Organization scannerOrg = organizationService.resolveActingOrganization(scanner.getIdentityUid());
        RequestRecord req = requestRepository.save(RequestRecord.builder()
                .identityUid(scanner.getIdentityUid())
                .organizationUid(scannerOrg != null ? scannerOrg.getOrganizationUid() : null)
                .objectUid(qrValue)
                .requestType(RequestType.ACCESS)
                .status(RequestStatus.REVIEW)
                .build());

        Decision decision = decisionRepository.save(Decision.builder()
                .requestUid(req.getRequestUid())
                .identityUid(scanner.getIdentityUid())
                .outcome(DecisionOutcome.REVIEW)
                .reasonCode("OWNER_CONFIRMATION_REQUIRED")
                .reason(reason)
                .riskLevel("MEDIUM")
                .build());

        Interaction interaction = interactionRepository.save(Interaction.builder()
                .identityUid(scanner.getIdentityUid())
                .requestUid(req.getRequestUid())
                .objectUid(qrValue)
                .targetIdentityUid(owner.getIdentityUid())
                .interactionType("PROFILE_SCAN")
                .status(InteractionStatus.PENDING)
                .detail("Запрос доступа к профилю от " + displayName(scanner.getIdentityUid()))
                .build());

        History history = auditService.record(scanner.getIdentityUid(), qrValue,
                HistoryEventType.PROFILE_ACCESS_REQUESTED, reason,
                req.getRequestUid(), decision.getDecisionUid(), interaction.getInteractionUid());

        eventService.record(EventType.QR_VIEWED, scanner.getIdentityUid(), qrValue,
                interaction.getInteractionUid(), "Просмотр QR личности");
        eventService.record(EventType.ACCESS_REQUESTED, scanner.getIdentityUid(), qrValue,
                interaction.getInteractionUid(), "Запрос доступа к профилю");

        notificationService.notify(owner.getIdentityUid(), "ACCESS_REQUEST",
                "Запрос доступа к профилю: " + displayName(scanner.getIdentityUid()) + ". Подтвердите или отклоните.");

        // Публичная визитка открывается СРАЗУ (это и есть «цифровая визитка» гражданина);
        // Owner-Approval при этом продолжает охранять ПОЛНЫЙ профиль — сканер полирует
        // /access/{id}/result до решения владельца, как и раньше.
        Map<String, Object> data = publicBusinessCard(owner);
        data.put("note", "Полный профиль (доверие, организация, статус) откроется после подтверждения владельцем.");

        return GatewayResponse.builder()
                .success(false)
                .outcome(DecisionOutcome.REVIEW.name())
                .reason(reason)
                .riskLevel("MEDIUM")
                .category("IDENTITY")
                .objectUid(qrValue)
                .data(data)
                .contextView("BUSINESS_CARD")
                .subjectName(displayName(owner.getIdentityUid()))
                .identityUid(scanner.getIdentityUid().toString())
                .requestUid(req.getRequestUid().toString())
                .decisionUid(decision.getDecisionUid().toString())
                .interactionUid(interaction.getInteractionUid().toString())
                .historyUid(history.getHistoryUid().toString())
                .build();
    }

    // ------------------------------------------------------------------
    //  Phase 2 — контекстные представления единого личного QR
    // ------------------------------------------------------------------

    /**
     * Специалист при исполнении отсканировал личный QR → маршрутизируем на профильный
     * объект досье (медкарта для врача, правовое досье для полиции) и прогоняем его через
     * СТАНДАРТНЫЙ конвейер {@link #scan}: те же ворота (роль, доверие, режим, время), тот же
     * консент для медкарты, те же записи в журнал. Досье лениво доусоздаётся для аккаунтов,
     * появившихся до Phase 2.
     */
    private GatewayResponse scanRoutedToDossier(Identity scanner, Identity owner,
                                                String dossierObjectUid, String contextView) {
        ensureDossier(owner);
        ScanRequest routed = new ScanRequest();
        routed.setObjectUid(dossierObjectUid);
        GatewayResponse res = scan(scanner, routed);
        res.setContextView(contextView);
        res.setSubjectName(displayName(owner.getIdentityUid()));
        return res;
    }

    /**
     * Фармацевт при исполнении видит НЕ карту, а только срез рецептов (принцип минимального
     * доступа: пациент предъявил свой QR у аптечной стойки — это и есть согласие на выдачу).
     * Профессиональные ворота проверяются полностью; полная медкарта фармацевту не
     * раскрывается ни при каком исходе.
     */
    @Transactional
    public GatewayResponse scanPrescriptionsView(Identity scanner, Identity owner) {
        ensureDossier(owner);
        String medUid = CitizenDossierService.medicalUidFor(owner.getIdentityUid());
        RegistryClient.Resolved resolved = registryClient.resolve(medUid);
        Organization actingOrg = organizationService.resolveActingOrganization(scanner.getIdentityUid());
        UUID organizationUid = actingOrg != null ? actingOrg.getOrganizationUid() : null;
        ValidationService.Verdict verdict = validationService.decideAccess(scanner,
                ObjectCategory.MEDICAL, resolved.known(), organizationUid, true, devTimeService.currentMockHour());
        boolean approved = verdict.outcome() == DecisionOutcome.APPROVED;
        if (approved) {
            verdict = new ValidationService.Verdict(DecisionOutcome.APPROVED, "RX_SLICE_GRANTED",
                    "Роль «Фармацевт»: предоставлен ограниченный срез — только назначения и рецепты.",
                    "MEDIUM");
        }

        RequestRecord req = requestRepository.save(RequestRecord.builder()
                .identityUid(scanner.getIdentityUid())
                .organizationUid(organizationUid)
                .objectUid(medUid)
                .requestType(RequestType.ACCESS)
                .status(RequestStatus.PENDING)
                .build());
        Decision decision = decisionRepository.save(Decision.builder()
                .requestUid(req.getRequestUid())
                .identityUid(scanner.getIdentityUid())
                .outcome(verdict.outcome())
                .reasonCode(verdict.reasonCode())
                .reason(verdict.reason())
                .riskLevel(verdict.riskLevel())
                .build());
        Interaction interaction = interactionRepository.save(Interaction.builder()
                .identityUid(scanner.getIdentityUid())
                .requestUid(req.getRequestUid())
                .objectUid(medUid)
                .targetIdentityUid(owner.getIdentityUid())
                .interactionType("SCAN")
                .detail("Фармацевт: просмотр рецептов пациента " + displayName(owner.getIdentityUid())
                        + " → " + verdict.outcome().name())
                .build());
        req.setStatus(approved ? RequestStatus.PROCESSED : RequestStatus.FAILED);
        requestRepository.save(req);

        History history = auditService.record(scanner.getIdentityUid(), medUid,
                approved ? HistoryEventType.ACCESS_GRANTED : HistoryEventType.ACCESS_DENIED,
                verdict.reason(), req.getRequestUid(), decision.getDecisionUid(), interaction.getInteractionUid());
        eventService.record(approved ? EventType.DECISION_APPROVED : EventType.DECISION_REJECTED,
                scanner.getIdentityUid(), medUid, interaction.getInteractionUid(),
                "Срез рецептов по личному QR → " + verdict.outcome().name(), EventSource.QR_SCAN);

        Map<String, Object> data = null;
        if (approved) {
            data = new LinkedHashMap<>();
            data.put("patientName", displayName(owner.getIdentityUid()));
            data.put("patientId", medUid);
            data.put("scope", "Минимальный доступ по роли: только назначения и рецепты. Полная карта не раскрывается.");
            data.put("prescriptions", medicalService.listForObject(medUid));
        }

        return GatewayResponse.builder()
                .success(approved)
                .outcome(verdict.outcome().name())
                .reason(verdict.reason())
                .riskLevel(verdict.riskLevel())
                .category(ObjectCategory.MEDICAL.name())
                .dataClassification(DataClassification.forCategory(ObjectCategory.MEDICAL).name())
                .policy(validationService.governingPolicy(ObjectCategory.MEDICAL))
                .objectUid(medUid)
                .data(data)
                .contextView("PRESCRIPTIONS")
                .subjectName(displayName(owner.getIdentityUid()))
                .identityUid(scanner.getIdentityUid().toString())
                .organizationUid(organizationUid != null ? organizationUid.toString() : null)
                .organizationName(actingOrg != null ? actingOrg.getName() : null)
                .requestUid(req.getRequestUid().toString())
                .decisionUid(decision.getDecisionUid().toString())
                .interactionUid(interaction.getInteractionUid().toString())
                .historyUid(history.getHistoryUid().toString())
                .build();
    }

    /** Ленивое доусоздание пакета досье владельца QR (для аккаунтов, созданных до Phase 2). */
    private void ensureDossier(Identity owner) {
        try {
            citizenDossierService.ensureForIdentity(owner);
        } catch (Exception e) {
            // Досье — обогащение, а не предусловие: сбой генерации не должен ломать скан.
        }
    }

    /** Публичная визитка гражданина (VCARD-объект досье) + единая метрика доверия. */
    private Map<String, Object> publicBusinessCard(Identity owner) {
        ensureDossier(owner);
        Map<String, Object> card = citizenDossierService
                .find(CitizenDossierService.vcardUidFor(owner.getIdentityUid()))
                .map(citizenDossierService::payload)
                .orElseGet(LinkedHashMap::new);
        card.putIfAbsent("fullName", displayName(owner.getIdentityUid()));
        card.put("trustLevel", owner.getTrustLevel());
        return card;
    }

    /** Owner / patient confirms a pending access request → Decision = APPROVED. */
    @Transactional
    public GatewayResponse confirmProfileAccess(Identity owner, UUID interactionUid) {
        Interaction interaction = requirePendingTarget(owner, interactionUid);
        boolean medical = MEDICAL_SCAN_TYPE.equals(interaction.getInteractionType());
        interaction.setStatus(InteractionStatus.CONFIRMED);
        interactionRepository.save(interaction);

        String reason = medical
                ? "Пациент подтвердил доступ к медицинской карте."
                : "Владелец подтвердил доступ к профилю.";
        Decision decision = decisionRepository.save(Decision.builder()
                .requestUid(interaction.getRequestUid())
                .identityUid(owner.getIdentityUid())
                .outcome(DecisionOutcome.APPROVED)
                .reasonCode(medical ? "PATIENT_CONSENT_GRANTED" : "OWNER_CONFIRMED")
                .reason(reason)
                .riskLevel("LOW")
                .build());

        requestRepository.findById(interaction.getRequestUid()).ifPresent(r -> {
            r.setStatus(RequestStatus.APPROVED);
            requestRepository.save(r);
        });

        // Запись адресована СКАНЕРУ (запросившему доступ) — стемпим её его тенантом, а не
        // тенантом владельца, в чьей транзакции она пишется: иначе врач из org-тенанта не
        // видит исход согласия в своём журнале (тот же класс бага, что и служебный доступ).
        History history = auditService.record(interaction.getIdentityUid(), interaction.getObjectUid(),
                HistoryEventType.PROFILE_ACCESS_CONFIRMED, reason,
                interaction.getRequestUid(), decision.getDecisionUid(), interaction.getInteractionUid(),
                tenantOf(interaction.getIdentityUid()));

        eventService.record(EventType.ACCESS_CONFIRMED, owner.getIdentityUid(), interaction.getObjectUid(),
                interaction.getInteractionUid(), medical ? "Согласие пациента на доступ к медкарте" : "Доступ к профилю подтверждён");

        notificationService.notify(interaction.getIdentityUid(), "ACCESS_RESULT", medical
                ? "Пациент разрешил доступ к медкарте. Откройте результат сканирования."
                : "Доступ к профилю «" + displayName(owner.getIdentityUid()) + "» подтверждён.");

        return GatewayResponse.builder()
                .success(true)
                .outcome(DecisionOutcome.APPROVED.name())
                .reason(reason)
                .riskLevel("LOW")
                .category(medical ? "MEDICAL" : "IDENTITY")
                .objectUid(interaction.getObjectUid())
                .data(medical ? null : profilePayload(owner.getIdentityUid()))
                .identityUid(owner.getIdentityUid().toString())
                .requestUid(interaction.getRequestUid().toString())
                .decisionUid(decision.getDecisionUid().toString())
                .interactionUid(interaction.getInteractionUid().toString())
                .historyUid(history.getHistoryUid().toString())
                .build();
    }

    /** Owner / patient rejects a pending access request → Decision = REJECTED. */
    @Transactional
    public GatewayResponse rejectProfileAccess(Identity owner, UUID interactionUid) {
        Interaction interaction = requirePendingTarget(owner, interactionUid);
        boolean medical = MEDICAL_SCAN_TYPE.equals(interaction.getInteractionType());
        interaction.setStatus(InteractionStatus.REJECTED);
        interactionRepository.save(interaction);

        String reason = medical
                ? "Пациент отклонил доступ к медицинской карте."
                : "Владелец отклонил доступ к профилю.";
        Decision decision = decisionRepository.save(Decision.builder()
                .requestUid(interaction.getRequestUid())
                .identityUid(owner.getIdentityUid())
                .outcome(DecisionOutcome.REJECTED)
                .reasonCode(medical ? "PATIENT_CONSENT_DENIED" : "OWNER_REJECTED")
                .reason(reason)
                .riskLevel("MEDIUM")
                .build());

        requestRepository.findById(interaction.getRequestUid()).ifPresent(r -> {
            r.setStatus(RequestStatus.REJECTED);
            requestRepository.save(r);
        });

        History history = auditService.record(interaction.getIdentityUid(), interaction.getObjectUid(),
                HistoryEventType.PROFILE_ACCESS_REJECTED, reason,
                interaction.getRequestUid(), decision.getDecisionUid(), interaction.getInteractionUid(),
                tenantOf(interaction.getIdentityUid()));

        eventService.record(EventType.DECISION_REJECTED, owner.getIdentityUid(), interaction.getObjectUid(),
                interaction.getInteractionUid(), medical ? "Пациент отклонил доступ к медкарте" : "Доступ к профилю отклонён");

        notificationService.notify(interaction.getIdentityUid(), "ACCESS_RESULT", medical
                ? "Пациент отклонил доступ к медицинской карте."
                : "Доступ к профилю «" + displayName(owner.getIdentityUid()) + "» отклонён.");

        return GatewayResponse.builder()
                .success(false)
                .outcome(DecisionOutcome.REJECTED.name())
                .reason(reason)
                .riskLevel("MEDIUM")
                .category(medical ? "MEDICAL" : "IDENTITY")
                .objectUid(interaction.getObjectUid())
                .identityUid(owner.getIdentityUid().toString())
                .requestUid(interaction.getRequestUid().toString())
                .decisionUid(decision.getDecisionUid().toString())
                .interactionUid(interaction.getInteractionUid().toString())
                .historyUid(history.getHistoryUid().toString())
                .build();
    }

    /**
     * Pending access/consent requests addressed to this owner (newest first). Uses a native,
     * tenant-filter-bypassing query so a request raised by a specialist in another tenant (a
     * hospital doctor asking a public-tenant patient for medical-card consent) reaches its target.
     */
    public List<Interaction> pendingAccessRequests(UUID ownerIdentityUid) {
        return interactionRepository.findPendingRequestsForTarget(ownerIdentityUid);
    }

    /**
     * The scanner polls this after scanning someone's profile QR. Once the owner confirms,
     * it returns the permitted profile data so the <b>scanner</b> (not just the owner) finally
     * sees the result — closing the old dead-end where confirmation led nowhere on the
     * scanning side. Only the scanner who initiated the request may read it.
     */
    public GatewayResponse profileAccessResult(Identity scanner, UUID interactionUid) {
        Interaction interaction = interactionRepository.findById(interactionUid)
                .orElseThrow(() -> new IllegalArgumentException("Запрос доступа не найден."));
        boolean medical = MEDICAL_SCAN_TYPE.equals(interaction.getInteractionType());
        boolean profile = "PROFILE_SCAN".equals(interaction.getInteractionType());
        // Owner-Approval объекта (OWNER_PROFILE) идёт через тот же результат-поллинг: после
        // согласия владельца сканирующему открывается профиль владельца, как при PROFILE_SCAN.
        boolean ownerProfile = OWNER_PROFILE_TYPE.equals(interaction.getInteractionType());
        if (!scanner.getIdentityUid().equals(interaction.getIdentityUid()) || !(medical || profile || ownerProfile)) {
            throw new AccessDeniedException("Этот запрос вам не принадлежит.");
        }
        InteractionStatus status = interaction.getStatus();
        boolean confirmed = status == InteractionStatus.CONFIRMED;
        boolean rejected = status == InteractionStatus.REJECTED;
        String outcome = confirmed ? DecisionOutcome.APPROVED.name()
                : rejected ? DecisionOutcome.REJECTED.name() : DecisionOutcome.REVIEW.name();

        if (medical) {
            if (rejected) {
                return medicalResult(scanner, interaction, DecisionOutcome.REJECTED.name(),
                        "Пациент отклонил доступ к медицинской карте.", "MEDIUM", null);
            }
            if (!confirmed) {
                return medicalResult(scanner, interaction, outcome, "Ожидается согласие пациента…", "MEDIUM", null);
            }
            // Consent granted. The professional gates are RE-evaluated at reveal time, because the
            // doctor's live context (working mode, mock hour) may have changed since the request —
            // consent never overrides the policy engine.
            String objectUid = interaction.getObjectUid();
            RegistryClient.Resolved resolved = registryClient.resolve(objectUid);
            boolean workingMode = sessionService.current(scanner.getIdentityUid()).getMode() == SessionMode.WORKING;
            Integer overrideHour = devTimeService.currentMockHour();
            Organization org = organizationService.resolveActingOrganization(scanner.getIdentityUid());
            ValidationService.Verdict verdict = validationService.decideAccess(scanner, resolved.category(),
                    resolved.known(), org != null ? org.getOrganizationUid() : null, workingMode, overrideHour);
            if (verdict.outcome() != DecisionOutcome.APPROVED) {
                return medicalResult(scanner, interaction, verdict.outcome().name(), verdict.reason(),
                        verdict.riskLevel(), null);
            }
            Object card = resolved.data();
            if (card instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) card;
                m.put("prescriptions", medicalService.listForObject(objectUid));
            }
            return medicalResult(scanner, interaction, DecisionOutcome.APPROVED.name(),
                    "Пациент дал согласие. Медицинская карта открыта.", "MEDIUM", card);
        }

        String reason = confirmed ? "Владелец подтвердил доступ. Профиль открыт."
                : rejected ? "Владелец отклонил доступ к профилю."
                : "Ожидается подтверждение владельца…";
        return GatewayResponse.builder()
                .success(confirmed)
                .outcome(outcome)
                .reason(reason)
                .riskLevel(confirmed ? "LOW" : "MEDIUM")
                .category("IDENTITY")
                .objectUid(interaction.getObjectUid())
                .data(confirmed && interaction.getTargetIdentityUid() != null
                        ? profilePayload(interaction.getTargetIdentityUid()) : null)
                .identityUid(scanner.getIdentityUid().toString())
                .interactionUid(interaction.getInteractionUid().toString())
                .build();
    }

    private GatewayResponse medicalResult(Identity scanner, Interaction interaction,
                                          String outcome, String reason, String risk, Object data) {
        return GatewayResponse.builder()
                .success(DecisionOutcome.APPROVED.name().equals(outcome))
                .outcome(outcome)
                .reason(reason)
                .riskLevel(risk)
                .category("MEDICAL")
                .contextView("MEDICAL")
                .objectUid(interaction.getObjectUid())
                .dataClassification(DataClassification.forCategory(ObjectCategory.MEDICAL).name())
                .policy(validationService.governingPolicy(ObjectCategory.MEDICAL))
                .data(data)
                .accessTier(data != null ? "FULL" : null)
                .identityUid(scanner.getIdentityUid().toString())
                .interactionUid(interaction.getInteractionUid().toString())
                .build();
    }

    private Interaction requirePendingTarget(Identity owner, UUID interactionUid) {
        Interaction interaction = interactionRepository.findById(interactionUid)
                .orElseThrow(() -> new IllegalArgumentException("Запрос доступа не найден."));
        if (!owner.getIdentityUid().equals(interaction.getTargetIdentityUid())) {
            throw new IllegalStateException("Этот запрос адресован не вам.");
        }
        if (interaction.getStatus() != InteractionStatus.PENDING) {
            throw new IllegalArgumentException("Запрос уже обработан.");
        }
        return interaction;
    }

    /**
     * Peel a scanned value back to its bare registry identifier. The platform mints QR codes
     * that encode an absolute deep link ({@code <origin>/s/<identifier>}) so a phone's stock
     * camera can open the app (see {@link QrService#toScanUrl}). When that whole URL is fed
     * back into the scan API — e.g. an in-app reader hands us the decoded URL verbatim — the
     * registry would look up the URL string, miss, and wrongly report "не найдено в реестре".
     * Stripping the {@code <origin>/s/} wrapper here makes a freshly created object or a
     * personal {@code IDENTITY:} QR resolve immediately, no matter which reader produced it.
     */
    static String normalizeIdentifier(String raw) {
        String v = raw == null ? "" : raw.trim();
        int marker = v.indexOf("/s/");
        if (marker >= 0 && (v.startsWith("http://") || v.startsWith("https://"))) {
            v = v.substring(marker + 3).trim();
            int cut = v.indexOf('?');
            if (cut >= 0) v = v.substring(0, cut);
            cut = v.indexOf('#');
            if (cut >= 0) v = v.substring(0, cut);
            try {
                v = java.net.URLDecoder.decode(v, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // keep the raw path segment if it isn't valid percent-encoding
            }
        }
        return v.trim();
    }

    /**
     * True when {@code owner} belongs to a different tenant than the current caller (audit H-2).
     * Admins / system tasks run with no tenant in context (unscoped) and bypass this check;
     * the shared {@link TenantContext#PUBLIC_TENANT} (citizens, guests, demo cards) is visible
     * to everyone.
     */
    private boolean isCrossTenant(Identity owner) {
        UUID caller = TenantContext.getTenantId();
        if (caller == null) {
            return false; // admin / unscoped — sees everyone
        }
        UUID ownerTenant = owner.getTenantId();
        if (ownerTenant == null || ownerTenant.equals(TenantContext.PUBLIC_TENANT)) {
            return false; // shared/public identities are universally resolvable
        }
        return !ownerTenant.equals(caller);
    }

    private String displayName(UUID identityUid) {
        // Native, tenant-filter-bypassing (как в HistoryController): имя субъекта должно
        // разрешаться и через границу тенантов — владелец вещи из публичного тенанта обязан
        // отображаться реальным именем в служебном раскрытии и в уведомлениях.
        return userRepository.findDisplayNameByIdentityUid(identityUid)
                .filter(s -> !s.isBlank())
                .orElse("Пользователь");
    }

    /**
     * Тенант личности-адресата для записей журнала, которые пишутся «на чужое имя»
     * (служебный доступ, исход согласия): запись обязана нести тенант читателя, иначе
     * tenant-фильтр спрячет её из его личного журнала. {@code findById} — это
     * {@code em.find}, Hibernate-@Filter на него не действует, поэтому личность
     * разрешается через границу тенантов.
     */
    private UUID tenantOf(UUID identityUid) {
        return identityRepository.findById(identityUid)
                .map(Identity::getTenantId)
                .orElse(TenantContext.PUBLIC_TENANT);
    }

    /**
     * Полный профиль после подтверждения владельцем: визитка (VCARD-объект) плюс
     * закрытая часть — единая метрика доверия, риск и профессия.
     */
    private Map<String, Object> profilePayload(UUID identityUid) {
        Map<String, Object> data = citizenDossierService
                .find(CitizenDossierService.vcardUidFor(identityUid))
                .map(citizenDossierService::payload)
                .orElseGet(LinkedHashMap::new);
        Optional<User> u = userRepository.findByIdentityUid(identityUid);
        data.putIfAbsent("fullName",
                u.map(x -> (x.getFirstName() + " " + x.getLastName()).trim()).orElse("Пользователь"));
        u.ifPresent(x -> data.put("profession", x.getProfession()));
        identityRepository.findById(identityUid).ifPresent(id -> {
            data.put("trustLevel", id.getTrustLevel());
            data.put("riskScore", id.getRiskScore());
            data.put("fullProfile", true);
        });
        data.put("note", "Полный профиль раскрыт с согласия владельца. Доступ зафиксирован в журнале.");
        return data;
    }

    /**
     * Субъект персональных данных, которому принадлежит запись реестра: пациент медкарты
     * ({@code patientIdentityUid}) или субъект правового досье ({@code subjectIdentityUid}).
     * Управляет owner-override'ом (владелец всегда может открыть данные о себе).
     */
    private UUID subjectOfRecord(Map<String, Object> data) {
        UUID patient = patientOfRecord(data);
        if (patient != null) {
            return patient;
        }
        if (data == null || data.get("subjectIdentityUid") == null) {
            return null;
        }
        try {
            return UUID.fromString(data.get("subjectIdentityUid").toString().trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The patient of record whose explicit consent governs access to a medical card. It is
     * carried inside the card payload ({@code patientIdentityUid}) so resolution stays
     * tenant-agnostic — a hospital-tenant doctor can identify the (public-tenant) patient
     * without a cross-tenant DB lookup. {@code null} ⇒ no identifiable patient (legacy cards).
     */
    private UUID patientOfRecord(Map<String, Object> data) {
        if (data == null) {
            return null;
        }
        Object raw = data.get(PATIENT_KEY);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw.toString().trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The professional gates are clear, but a medical card belongs to a patient: raise a
     * consent request the patient must approve before the card opens (Owner-Approval for
     * medical records). Mirrors {@link #scanIdentityProfile}, except a confirmed result
     * reveals the medical card (via {@link #profileAccessResult}), not a profile. The scanner
     * gets REVIEW and polls {@code /api/v2/access/{id}/result}.
     */
    @Transactional
    public GatewayResponse requestMedicalConsent(Identity scanner, String objectUid, String objectName,
                                                 UUID patientUid, UUID organizationUid) {
        String reason = "Доступ к медицинской карте требует согласия пациента. Запрос отправлен пациенту.";
        RequestRecord req = requestRepository.save(RequestRecord.builder()
                .identityUid(scanner.getIdentityUid())
                .organizationUid(organizationUid)
                .objectUid(objectUid)
                .requestType(RequestType.ACCESS)
                .status(RequestStatus.REVIEW)
                .build());
        Decision decision = decisionRepository.save(Decision.builder()
                .requestUid(req.getRequestUid())
                .identityUid(scanner.getIdentityUid())
                .outcome(DecisionOutcome.REVIEW)
                .reasonCode("PATIENT_CONSENT_REQUIRED")
                .reason(reason)
                .riskLevel("HIGH")
                .build());
        Interaction interaction = interactionRepository.save(Interaction.builder()
                .identityUid(scanner.getIdentityUid())
                .requestUid(req.getRequestUid())
                .objectUid(objectUid)
                .targetIdentityUid(patientUid)
                .interactionType(MEDICAL_SCAN_TYPE)
                .status(InteractionStatus.PENDING)
                .detail("Запрос доступа к медкарте от " + displayName(scanner.getIdentityUid()))
                .build());
        History history = auditService.record(scanner.getIdentityUid(), objectUid,
                HistoryEventType.PROFILE_ACCESS_REQUESTED, reason,
                req.getRequestUid(), decision.getDecisionUid(), interaction.getInteractionUid());
        eventService.record(EventType.ACCESS_REQUESTED, scanner.getIdentityUid(), objectUid,
                interaction.getInteractionUid(), "Запрос согласия пациента на доступ к медкарте");
        notificationService.notify(patientUid, "ACCESS_REQUEST",
                "Запрос доступа к вашей медкарте: " + displayName(scanner.getIdentityUid())
                        + ". Подтвердите или отклоните.");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", objectName != null ? objectName : "Медицинская карта");
        data.put("description", "Сканирование — это только запрос. Карта откроется лишь с согласия пациента.");
        data.put("note", "Ожидается подтверждение пациента.");
        return GatewayResponse.builder()
                .success(false)
                .outcome(DecisionOutcome.REVIEW.name())
                .reason(reason)
                .riskLevel("HIGH")
                .category("MEDICAL")
                .objectUid(objectUid)
                .dataClassification(DataClassification.forCategory(ObjectCategory.MEDICAL).name())
                .policy(validationService.governingPolicy(ObjectCategory.MEDICAL))
                .data(data)
                .identityUid(scanner.getIdentityUid().toString())
                .organizationUid(organizationUid != null ? organizationUid.toString() : null)
                .requestUid(req.getRequestUid().toString())
                .decisionUid(decision.getDecisionUid().toString())
                .interactionUid(interaction.getInteractionUid().toString())
                .historyUid(history.getHistoryUid().toString())
                .build();
    }
}
