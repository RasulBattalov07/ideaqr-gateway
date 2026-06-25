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
    private final TrustScoreService trustScoreService;
    private final OrganizationService organizationService;
    private final SessionService sessionService;
    private final DevTimeService devTimeService;
    private final MedicalService medicalService;

    @Transactional
    public GatewayResponse scan(Identity identity, ScanRequest request) {
        String objectUid = request.getObjectUid().trim();

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

        RequestRecord req = requestRepository.save(RequestRecord.builder()
                .identityUid(identity.getIdentityUid())
                .organizationUid(organizationUid)
                .objectUid(objectUid)
                .requestType(RequestType.ACCESS)
                .status(RequestStatus.PENDING)
                .build());

        // Two MVP demo levers now feed the policy engine, so the mode toggle and the
        // working-hours window actually gate something live (not decorative buttons):
        //   • working mode — professional categories require the actor to be ON the clock;
        //   • mock hour    — the session "time machine" (dev panel), server-side only.
        boolean workingMode = sessionService.current(identity.getIdentityUid()).getMode() == SessionMode.WORKING;
        Integer overrideHour = devTimeService.currentMockHour();
        ValidationService.Verdict verdict = validationService.decideAccess(
                identity, resolved.category(), resolved.known(), organizationUid, workingMode, overrideHour);
        boolean approved = verdict.outcome() == DecisionOutcome.APPROVED;

        // Tiered visibility (Scenario #1 / ПУБЛИЧНАЯ СТРАНИЦА): the access *decision* is
        // identical for everyone, but a GUEST only ever receives the public projection of
        // the card (name / image / short description / rating). A registered identity gets
        // the full payload; sensitive fields (price, reviews, history, supplier) are
        // stripped for guests by PublicCard's default-deny whitelist.
        boolean guest = identity.getIdentityType() == IdentityType.GUEST;
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

        Decision decision = decisionRepository.save(Decision.builder()
                .requestUid(req.getRequestUid())
                .identityUid(identity.getIdentityUid())
                .outcome(verdict.outcome())
                .reasonCode(verdict.reasonCode())
                .reason(verdict.reason())
                .riskLevel(verdict.riskLevel())
                .build());

        Interaction interaction = interactionRepository.save(Interaction.builder()
                .identityUid(identity.getIdentityUid())
                .requestUid(req.getRequestUid())
                .objectUid(objectUid)
                .interactionType("SCAN")
                .detail("Сканирование объекта " + objectUid + " → " + verdict.outcome().name()
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

        // Final pipeline stage (… → History → Trust Score): recompute the acting
        // identity's score so the MVP demo can show it being recalculated live.
        int trustScore = trustScoreService.refresh(identity);

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
                .trustScore(trustScore)
                .accessTier(accessTier)
                .registrationRequired(registrationRequired)
                .cta(cta)
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
        String reason = "SOS-запрос принят. Приоритет повышен, ответственные лица уведомлены.";

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

        notificationService.notify(identity.getIdentityUid(), "SOS",
                "SOS-запрос зарегистрирован и эскалирован.");

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

        String reason = "Запрос доступа к профилю отправлен. Ожидается подтверждение владельца.";
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

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", displayName(owner.getIdentityUid()));
        data.put("description", "Запрос отправлен. Сканирование — это только просмотр.");
        data.put("note", "Полный профиль откроется после подтверждения владельцем.");

        return GatewayResponse.builder()
                .success(false)
                .outcome(DecisionOutcome.REVIEW.name())
                .reason(reason)
                .riskLevel("MEDIUM")
                .category("IDENTITY")
                .objectUid(qrValue)
                .data(data)
                .identityUid(scanner.getIdentityUid().toString())
                .requestUid(req.getRequestUid().toString())
                .decisionUid(decision.getDecisionUid().toString())
                .interactionUid(interaction.getInteractionUid().toString())
                .historyUid(history.getHistoryUid().toString())
                .build();
    }

    /** Owner confirms a pending profile-access request → Decision = APPROVED. */
    @Transactional
    public GatewayResponse confirmProfileAccess(Identity owner, UUID interactionUid) {
        Interaction interaction = requirePendingTarget(owner, interactionUid);
        interaction.setStatus(InteractionStatus.CONFIRMED);
        interactionRepository.save(interaction);

        String reason = "Владелец подтвердил доступ к профилю.";
        Decision decision = decisionRepository.save(Decision.builder()
                .requestUid(interaction.getRequestUid())
                .identityUid(owner.getIdentityUid())
                .outcome(DecisionOutcome.APPROVED)
                .reasonCode("OWNER_CONFIRMED")
                .reason(reason)
                .riskLevel("LOW")
                .build());

        requestRepository.findById(interaction.getRequestUid()).ifPresent(r -> {
            r.setStatus(RequestStatus.APPROVED);
            requestRepository.save(r);
        });

        History history = auditService.record(interaction.getIdentityUid(), interaction.getObjectUid(),
                HistoryEventType.PROFILE_ACCESS_CONFIRMED, reason,
                interaction.getRequestUid(), decision.getDecisionUid(), interaction.getInteractionUid());

        eventService.record(EventType.ACCESS_CONFIRMED, owner.getIdentityUid(), interaction.getObjectUid(),
                interaction.getInteractionUid(), "Доступ к профилю подтверждён");

        notificationService.notify(interaction.getIdentityUid(), "ACCESS_RESULT",
                "Доступ к профилю «" + displayName(owner.getIdentityUid()) + "» подтверждён.");

        // Confirming is a successful interaction → recompute the owner's Trust Score.
        int trustScore = trustScoreService.refresh(owner);

        return GatewayResponse.builder()
                .success(true)
                .outcome(DecisionOutcome.APPROVED.name())
                .reason(reason)
                .riskLevel("LOW")
                .category("IDENTITY")
                .objectUid(interaction.getObjectUid())
                .data(profilePayload(owner.getIdentityUid()))
                .identityUid(owner.getIdentityUid().toString())
                .requestUid(interaction.getRequestUid().toString())
                .decisionUid(decision.getDecisionUid().toString())
                .interactionUid(interaction.getInteractionUid().toString())
                .historyUid(history.getHistoryUid().toString())
                .trustScore(trustScore)
                .build();
    }

    /** Owner rejects a pending profile-access request → Decision = REJECTED. */
    @Transactional
    public GatewayResponse rejectProfileAccess(Identity owner, UUID interactionUid) {
        Interaction interaction = requirePendingTarget(owner, interactionUid);
        interaction.setStatus(InteractionStatus.REJECTED);
        interactionRepository.save(interaction);

        String reason = "Владелец отклонил доступ к профилю.";
        Decision decision = decisionRepository.save(Decision.builder()
                .requestUid(interaction.getRequestUid())
                .identityUid(owner.getIdentityUid())
                .outcome(DecisionOutcome.REJECTED)
                .reasonCode("OWNER_REJECTED")
                .reason(reason)
                .riskLevel("MEDIUM")
                .build());

        requestRepository.findById(interaction.getRequestUid()).ifPresent(r -> {
            r.setStatus(RequestStatus.REJECTED);
            requestRepository.save(r);
        });

        History history = auditService.record(interaction.getIdentityUid(), interaction.getObjectUid(),
                HistoryEventType.PROFILE_ACCESS_REJECTED, reason,
                interaction.getRequestUid(), decision.getDecisionUid(), interaction.getInteractionUid());

        eventService.record(EventType.DECISION_REJECTED, owner.getIdentityUid(), interaction.getObjectUid(),
                interaction.getInteractionUid(), "Доступ к профилю отклонён");

        notificationService.notify(interaction.getIdentityUid(), "ACCESS_RESULT",
                "Доступ к профилю «" + displayName(owner.getIdentityUid()) + "» отклонён.");

        return GatewayResponse.builder()
                .success(false)
                .outcome(DecisionOutcome.REJECTED.name())
                .reason(reason)
                .riskLevel("MEDIUM")
                .category("IDENTITY")
                .objectUid(interaction.getObjectUid())
                .identityUid(owner.getIdentityUid().toString())
                .requestUid(interaction.getRequestUid().toString())
                .decisionUid(decision.getDecisionUid().toString())
                .interactionUid(interaction.getInteractionUid().toString())
                .historyUid(history.getHistoryUid().toString())
                .build();
    }

    /** Pending profile-access requests addressed to this owner (newest first). */
    public List<Interaction> pendingAccessRequests(UUID ownerIdentityUid) {
        return interactionRepository.findByTargetIdentityUidOrderByCreatedAtDesc(ownerIdentityUid).stream()
                .filter(i -> i.getStatus() == InteractionStatus.PENDING)
                .toList();
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
        if (!scanner.getIdentityUid().equals(interaction.getIdentityUid())
                || !"PROFILE_SCAN".equals(interaction.getInteractionType())) {
            throw new AccessDeniedException("Этот запрос вам не принадлежит.");
        }
        InteractionStatus status = interaction.getStatus();
        boolean confirmed = status == InteractionStatus.CONFIRMED;
        boolean rejected = status == InteractionStatus.REJECTED;
        String outcome = confirmed ? DecisionOutcome.APPROVED.name()
                : rejected ? DecisionOutcome.REJECTED.name() : DecisionOutcome.REVIEW.name();
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
        return userRepository.findByIdentityUid(identityUid)
                .map(u -> (u.getFirstName() + " " + u.getLastName()).trim())
                .orElse("Пользователь");
    }

    private Map<String, Object> profilePayload(UUID identityUid) {
        Map<String, Object> data = new LinkedHashMap<>();
        Optional<User> u = userRepository.findByIdentityUid(identityUid);
        data.put("title", u.map(x -> (x.getFirstName() + " " + x.getLastName()).trim()).orElse("Пользователь"));
        u.ifPresent(x -> data.put("description", "Профессия: " + x.getProfession()));
        identityRepository.findById(identityUid).ifPresent(id ->
                data.put("note", "Уровень доверия: " + id.getTrustLevel() + " / 100"));
        return data;
    }
}
