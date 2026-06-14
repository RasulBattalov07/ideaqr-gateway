package com.ideaqr.gateway.service;

import com.ideaqr.gateway.dto.GatewayResponse;
import com.ideaqr.gateway.dto.ScanRequest;
import com.ideaqr.gateway.entity.Decision;
import com.ideaqr.gateway.entity.Identity;
import com.ideaqr.gateway.entity.Interaction;
import com.ideaqr.gateway.entity.RequestRecord;
import com.ideaqr.gateway.enums.DecisionResult;
import com.ideaqr.gateway.enums.RequestStatus;
import com.ideaqr.gateway.enums.RequestType;
import com.ideaqr.gateway.repository.DecisionRepository;
import com.ideaqr.gateway.repository.InteractionRepository;
import com.ideaqr.gateway.repository.RequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * End-to-end orchestration of a scan, implementing the pipeline:
 *
 *   Intake -> Create Request -> Evaluate rules (time/roles) -> Create Decision
 *          -> (if APPROVED) route to mock KZ registry
 *          -> Create Interaction + append History
 *
 * Anonymous scans dynamically provision a GUEST identity before processing.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GatewayService {

    private final RequestRepository requestRepository;
    private final DecisionRepository decisionRepository;
    private final InteractionRepository interactionRepository;
    private final IdentityService identityService;
    private final ValidationService validationService;
    private final RegistryClient registryClient;
    private final AuditService auditService;

    /**
     * Process a single scan end to end.
     */
    @Transactional
    public GatewayResponse processScan(ScanRequest scanRequest) {
        LocalDateTime now = LocalDateTime.now();

        // 1. Resolve or provision the identity.
        Identity identity = resolveIdentity(scanRequest);

        // 2. Create the Request (PENDING).
        RequestRecord request = RequestRecord.builder()
                .requestUid(UUID.randomUUID())
                .identityUid(identity.getIdentityUid())
                .requestType(scanRequest.getRequestType())
                .status(RequestStatus.PENDING)
                .createdAt(now)
                .build();
        request = requestRepository.save(request);

        Map<String, Object> requestPayload = new LinkedHashMap<>();
        requestPayload.put("requestUid", request.getRequestUid().toString());
        requestPayload.put("identityUid", identity.getIdentityUid().toString());
        requestPayload.put("requestType", request.getRequestType().name());
        requestPayload.put("objectUid", scanRequest.getObjectUid());
        auditService.append("REQUEST_CREATED", null, requestPayload);

        // 3. Evaluate the rules engine.
        ValidationService.Verdict verdict = validationService.evaluate(
                identity, scanRequest.getRequestType(), scanRequest.getObjectUid(), now);

        // 4. Persist the Decision.
        Decision decision = Decision.builder()
                .decisionUid(UUID.randomUUID())
                .requestUid(request.getRequestUid())
                .result(verdict.getResult())
                .reason(verdict.getReason())
                .createdAt(LocalDateTime.now())
                .build();
        decision = decisionRepository.save(decision);

        // 5. Update request status based on the verdict.
        request.setStatus(verdict.getResult() == DecisionResult.APPROVED
                ? RequestStatus.PROCESSED
                : RequestStatus.FAILED);
        request = requestRepository.save(request);

        // 6. Create the Interaction (always recorded, approved or not).
        String interactionType = scanRequest.getInteractionType() != null
                ? scanRequest.getInteractionType()
                : "SCAN";
        Interaction interaction = Interaction.builder()
                .interactionUid(UUID.randomUUID())
                .identityUid(identity.getIdentityUid())
                .requestUid(request.getRequestUid())
                .decisionUid(decision.getDecisionUid())
                .objectUid(scanRequest.getObjectUid())
                .interactionType(interactionType)
                .createdAt(LocalDateTime.now())
                .build();
        interaction = interactionRepository.save(interaction);

        // 7. Route to mock registry only when approved.
        Object registryData = null;
        if (verdict.isApproved()) {
            registryData = registryClient.fetchMockData(
                    scanRequest.getObjectUid(), scanRequest.getRequestType());
        }

        // 8. Append the deep audit event tied to this interaction.
        String eventType = switch (verdict.getResult()) {
            case APPROVED -> "ACCESS_GRANTED";
            case REJECTED -> "ACCESS_DENIED";
            case REVIEW -> "ACCESS_REVIEW";
        };
        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("identityUid", identity.getIdentityUid().toString());
        auditPayload.put("identityType", identity.getIdentityType().name());
        auditPayload.put("requestUid", request.getRequestUid().toString());
        auditPayload.put("decisionUid", decision.getDecisionUid().toString());
        auditPayload.put("interactionUid", interaction.getInteractionUid().toString());
        auditPayload.put("objectUid", scanRequest.getObjectUid());
        auditPayload.put("result", verdict.getResult().name());
        auditPayload.put("reason", verdict.getReason());
        auditService.append(eventType, interaction.getInteractionUid(), auditPayload);

        log.info("Processed scan: identity={} object={} result={}",
                identity.getIdentityUid(), scanRequest.getObjectUid(), verdict.getResult());

        // 9. Build the response.
        return GatewayResponse.builder()
                .identityUid(identity.getIdentityUid())
                .requestUid(request.getRequestUid())
                .decisionUid(decision.getDecisionUid())
                .interactionUid(interaction.getInteractionUid())
                .result(verdict.getResult())
                .reason(verdict.getReason())
                .objectUid(scanRequest.getObjectUid())
                .registryData(registryData)
                .processedAt(interaction.getCreatedAt())
                .build();
    }

    /**
     * If an identityUid was supplied, load it; otherwise provision a GUEST.
     */
    private Identity resolveIdentity(ScanRequest scanRequest) {
        if (scanRequest.getIdentityUid() != null) {
            return identityService.getById(scanRequest.getIdentityUid());
        }
        log.info("Anonymous scan detected; provisioning GUEST identity");
        return identityService.provisionGuest();
    }
}
