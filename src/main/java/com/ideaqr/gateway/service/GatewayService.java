package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.Decision;
import com.ideaqr.gateway.domain.History;
import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.Interaction;
import com.ideaqr.gateway.domain.RequestRecord;
import com.ideaqr.gateway.domain.Workflow;
import com.ideaqr.gateway.domain.enums.DecisionOutcome;
import com.ideaqr.gateway.domain.enums.HistoryEventType;
import com.ideaqr.gateway.domain.enums.RequestStatus;
import com.ideaqr.gateway.domain.enums.RequestType;
import com.ideaqr.gateway.dto.GatewayResponse;
import com.ideaqr.gateway.dto.ReportRequest;
import com.ideaqr.gateway.dto.ScanRequest;
import com.ideaqr.gateway.repository.DecisionRepository;
import com.ideaqr.gateway.repository.InteractionRepository;
import com.ideaqr.gateway.repository.RequestRepository;
import com.ideaqr.gateway.repository.WorkflowRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Heart of the platform. Drives every scan and report through the governance
 * pipeline (Identity → Request → Decision → Interaction → History) and returns
 * the verdict, the localized reason, the risk level and the full chain of UUIDs.
 * Denied requests still return a populated response (HTTP 200, {@code success:false}).
 */
@Service
@RequiredArgsConstructor
public class GatewayService {

    private final RegistryClient registryClient;
    private final ValidationService validationService;
    private final AuditService auditService;
    private final RequestRepository requestRepository;
    private final DecisionRepository decisionRepository;
    private final InteractionRepository interactionRepository;
    private final NotificationService notificationService;
    private final WorkflowRepository workflowRepository;

    @Transactional
    public GatewayResponse scan(Identity identity, ScanRequest request) {
        String objectUid = request.getObjectUid().trim();
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
}
