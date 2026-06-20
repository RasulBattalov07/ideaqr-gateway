package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.Decision;
import com.ideaqr.gateway.domain.History;
import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.Interaction;
import com.ideaqr.gateway.domain.RequestRecord;
import com.ideaqr.gateway.domain.User;
import com.ideaqr.gateway.domain.Workflow;
import com.ideaqr.gateway.domain.enums.DecisionOutcome;
import com.ideaqr.gateway.domain.enums.EventType;
import com.ideaqr.gateway.domain.enums.HistoryEventType;
import com.ideaqr.gateway.domain.enums.InteractionStatus;
import com.ideaqr.gateway.domain.enums.RequestStatus;
import com.ideaqr.gateway.domain.enums.RequestType;
import com.ideaqr.gateway.dto.GatewayResponse;
import com.ideaqr.gateway.dto.ReportRequest;
import com.ideaqr.gateway.dto.ScanRequest;
import com.ideaqr.gateway.repository.DecisionRepository;
import com.ideaqr.gateway.repository.IdentityRepository;
import com.ideaqr.gateway.repository.InteractionRepository;
import com.ideaqr.gateway.repository.RequestRepository;
import com.ideaqr.gateway.repository.UserRepository;
import com.ideaqr.gateway.repository.WorkflowRepository;
import lombok.RequiredArgsConstructor;
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

    @Transactional
    public GatewayResponse scan(Identity identity, ScanRequest request) {
        String objectUid = request.getObjectUid().trim();

        // Person-to-person: scanning another user's primary QR is a profile-access
        // request that the owner must confirm — not a direct object read.
        if (objectUid.toUpperCase().startsWith(IDENTITY_PREFIX)) {
            return scanIdentityProfile(identity, objectUid);
        }

        RegistryClient.Resolved resolved = registryClient.resolve(objectUid);

        RequestRecord req = requestRepository.save(RequestRecord.builder()
                .identityUid(identity.getIdentityUid())
                .objectUid(objectUid)
                .requestType(RequestType.ACCESS)
                .status(RequestStatus.PENDING)
                .build());

        ValidationService.Verdict verdict = validationService.decideAccess(
                identity, resolved.category(), request.getContextHour(), resolved.known());
        boolean approved = verdict.outcome() == DecisionOutcome.APPROVED;

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
                .detail("Сканирование объекта " + objectUid + " → " + verdict.outcome().name())
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
                "Скан объекта " + objectUid + " → " + verdict.outcome().name());

        // Final pipeline stage (… → History → Trust Score): recompute the acting
        // identity's score so the MVP demo can show it being recalculated live.
        int trustScore = trustScoreService.refresh(identity);

        return GatewayResponse.builder()
                .success(approved)
                .outcome(verdict.outcome().name())
                .reason(verdict.reason())
                .riskLevel(verdict.riskLevel())
                .category(resolved.category().name())
                .objectUid(objectUid)
                .data(approved ? resolved.data() : null)
                .identityUid(identity.getIdentityUid().toString())
                .requestUid(req.getRequestUid().toString())
                .decisionUid(decision.getDecisionUid().toString())
                .interactionUid(interaction.getInteractionUid().toString())
                .historyUid(history.getHistoryUid().toString())
                .trustScore(trustScore)
                .build();
    }

    @Transactional
    public GatewayResponse report(Identity identity, ReportRequest request) {
        String objectUid = request.getObjectUid() != null ? request.getObjectUid().trim() : "";
        RegistryClient.Resolved resolved = registryClient.resolve(objectUid);
        String reason = "Обращение по объекту принято и зарегистрировано.";

        RequestRecord req = requestRepository.save(RequestRecord.builder()
                .identityUid(identity.getIdentityUid())
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

        RequestRecord req = requestRepository.save(RequestRecord.builder()
                .identityUid(identity.getIdentityUid())
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
        RequestRecord req = requestRepository.save(RequestRecord.builder()
                .identityUid(scanner.getIdentityUid())
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
