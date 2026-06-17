package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.Decision;
import com.ideaqr.gateway.domain.History;
import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.Interaction;
import com.ideaqr.gateway.domain.RequestRecord;
import com.ideaqr.gateway.domain.enums.DecisionOutcome;
import com.ideaqr.gateway.domain.enums.HistoryEventType;
import com.ideaqr.gateway.domain.enums.ObjectCategory;
import com.ideaqr.gateway.domain.enums.RequestStatus;
import com.ideaqr.gateway.domain.enums.RequestType;
import com.ideaqr.gateway.dto.GatewayResponse;
import com.ideaqr.gateway.dto.ReportRequest;
import com.ideaqr.gateway.dto.ScanRequest;
import com.ideaqr.gateway.repository.DecisionRepository;
import com.ideaqr.gateway.repository.InteractionRepository;
import com.ideaqr.gateway.repository.RequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Orchestrates the Stage 2 governance pipeline for authenticated Stage 3 users:
 * <pre>Identity → Request → Decision → Interaction → History</pre>
 * Every scan and every issue-report flows through here. An Interaction and a
 * History record are written on <em>every</em> attempt — approved or not — which
 * is what makes the audit trail complete.
 */
@Service
@RequiredArgsConstructor
public class GatewayService {

    private final IdentityService identityService;
    private final ValidationService validationService;
    private final RegistryClient registryClient;
    private final AuditService auditService;
    private final RequestRepository requestRepository;
    private final DecisionRepository decisionRepository;
    private final InteractionRepository interactionRepository;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ------------------------------------------------------------------
    //  Scan
    // ------------------------------------------------------------------

    @Transactional
    public GatewayResponse scan(Identity identity, ScanRequest request) {
        String objectUid = request.getObjectUid().trim();

        // Resolve the object (admin-created first, then demo registries).
        Optional<RegistryClient.ResolvedObject> resolved = registryClient.resolve(objectUid);
        ObjectCategory category = resolved.map(RegistryClient.ResolvedObject::category)
                .orElse(registryClient.inferCategory(objectUid));
        String displayName = resolved.map(RegistryClient.ResolvedObject::displayName).orElse(objectUid);

        // 1. Request enters the pipeline.
        RequestRecord req = requestRepository.save(RequestRecord.builder()
                .identityUid(identity.getIdentityUid())
                .objectUid(objectUid)
                .requestType(RequestType.ACCESS)
                .status(RequestStatus.PENDING)
                .build());

        // 2. Decision via the rules engine.
        ValidationService.DecisionResult result =
                validationService.decide(identity, RequestType.ACCESS, objectUid, category, request.getContextHour());
        Decision decision = decisionRepository.save(Decision.builder()
                .requestUid(req.getRequestUid())
                .identityUid(identity.getIdentityUid())
                .outcome(result.outcome())
                .reasonCode(result.reasonCode())
                .reason(result.reason())
                .riskLevel(result.riskLevel())
                .build());

        // 3. Interaction is always recorded.
        Interaction interaction = interactionRepository.save(Interaction.builder()
                .identityUid(identity.getIdentityUid())
                .requestUid(req.getRequestUid())
                .objectUid(objectUid)
                .interactionType("SCAN")
                .detail("Сканирование объекта «" + displayName + "» → " + result.outcome())
                .build());

        // 4. Request status follows the verdict.
        boolean approved = result.outcome() == DecisionOutcome.APPROVED;
        req.setStatus(approved ? RequestStatus.PROCESSED : RequestStatus.FAILED);
        requestRepository.save(req);

        // 5. Attach registry data only when approved.
        Object data = (approved && resolved.isPresent()) ? resolved.get().data() : null;

        // 6. Append to the immutable history.
        HistoryEventType eventType = switch (result.outcome()) {
            case APPROVED -> HistoryEventType.ACCESS_GRANTED;
            case REJECTED -> HistoryEventType.ACCESS_DENIED;
            case REVIEW -> HistoryEventType.ACCESS_REVIEW;
        };
        History history = auditService.record(identity.getIdentityUid(), req.getRequestUid(),
                decision.getDecisionUid(), interaction.getInteractionUid(), objectUid,
                eventType, "Доступ к «" + displayName + "»: " + result.reason());

        return GatewayResponse.builder()
                .success(approved)
                .outcome(result.outcome().name())
                .reasonCode(result.reasonCode())
                .reason(result.reason())
                .riskLevel(result.riskLevel())
                .objectUid(objectUid)
                .category(category != null ? category.name() : ObjectCategory.UNKNOWN.name())
                .data(data)
                .identityUid(identity.getIdentityUid().toString())
                .requestUid(req.getRequestUid().toString())
                .decisionUid(decision.getDecisionUid().toString())
                .interactionUid(interaction.getInteractionUid().toString())
                .historyUid(history.getHistoryUid().toString())
                .timestamp(LocalDateTime.now().format(TS))
                .build();
    }

    // ------------------------------------------------------------------
    //  Report an issue (eco / infrastructure objects)
    // ------------------------------------------------------------------

    @Transactional
    public GatewayResponse report(Identity identity, ReportRequest request) {
        String objectUid = request.getObjectUid().trim();
        Optional<RegistryClient.ResolvedObject> resolved = registryClient.resolve(objectUid);
        ObjectCategory category = resolved.map(RegistryClient.ResolvedObject::category)
                .orElse(registryClient.inferCategory(objectUid));
        String displayName = resolved.map(RegistryClient.ResolvedObject::displayName).orElse(objectUid);

        RequestRecord req = requestRepository.save(RequestRecord.builder()
                .identityUid(identity.getIdentityUid())
                .objectUid(objectUid)
                .requestType(RequestType.REPORT_ISSUE)
                .status(RequestStatus.PENDING)
                .build());

        ValidationService.DecisionResult result = validationService.decideReport(identity, category);
        Decision decision = decisionRepository.save(Decision.builder()
                .requestUid(req.getRequestUid())
                .identityUid(identity.getIdentityUid())
                .outcome(result.outcome())
                .reasonCode(result.reasonCode())
                .reason(result.reason())
                .riskLevel(result.riskLevel())
                .build());

        String message = (request.getMessage() == null || request.getMessage().isBlank())
                ? "Сообщение о проблеме без описания"
                : request.getMessage().trim();

        Interaction interaction = interactionRepository.save(Interaction.builder()
                .identityUid(identity.getIdentityUid())
                .requestUid(req.getRequestUid())
                .objectUid(objectUid)
                .interactionType("REPORT_ISSUE")
                .detail("Обращение по объекту «" + displayName + "»: " + message)
                .build());

        req.setStatus(RequestStatus.PROCESSED);
        requestRepository.save(req);

        History history = auditService.record(identity.getIdentityUid(), req.getRequestUid(),
                decision.getDecisionUid(), interaction.getInteractionUid(), objectUid,
                HistoryEventType.ISSUE_REPORTED, "Зарегистрировано обращение по объекту «" + displayName + "»");

        return GatewayResponse.builder()
                .success(true)
                .outcome(result.outcome().name())
                .reasonCode(result.reasonCode())
                .reason("Обращение принято. Регистрационный номер: "
                        + req.getRequestUid().toString().substring(0, 8).toUpperCase())
                .riskLevel(result.riskLevel())
                .objectUid(objectUid)
                .category(category != null ? category.name() : ObjectCategory.UNKNOWN.name())
                .identityUid(identity.getIdentityUid().toString())
                .requestUid(req.getRequestUid().toString())
                .decisionUid(decision.getDecisionUid().toString())
                .interactionUid(interaction.getInteractionUid().toString())
                .historyUid(history.getHistoryUid().toString())
                .timestamp(LocalDateTime.now().format(TS))
                .build();
    }
}
