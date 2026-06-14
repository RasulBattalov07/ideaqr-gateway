package com.ideaqr.gateway.service;

import com.ideaqr.gateway.entity.Assignment;
import com.ideaqr.gateway.entity.QrCode;
import com.ideaqr.gateway.enums.AssignmentStatus;
import com.ideaqr.gateway.enums.DecisionResult;
import com.ideaqr.gateway.enums.QrType;
import com.ideaqr.gateway.exception.BusinessRuleException;
import com.ideaqr.gateway.repository.AssignmentRepository;
import com.ideaqr.gateway.repository.DecisionRepository;
import com.ideaqr.gateway.repository.QrCodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * QR governance. A QR code may only be registered when an APPROVED Decision exists
 * for a QR_CREATION Request. Also produces Assignments binding a QR to a concrete object.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class QrService {

    private final QrCodeRepository qrCodeRepository;
    private final AssignmentRepository assignmentRepository;
    private final DecisionRepository decisionRepository;
    private final AuditService auditService;

    /**
     * Register a QR code for an identity, gated on an APPROVED QR_CREATION decision.
     *
     * @param identityUid the owner
     * @param requestUid  the QR_CREATION request whose decision authorizes this
     * @param qrType      MAIN or TEMPORARY
     * @return the persisted QrCode
     */
    @Transactional
    public QrCode createQr(UUID identityUid, UUID requestUid, QrType qrType) {
        boolean approved = decisionRepository
                .existsByRequestUidAndResult(requestUid, DecisionResult.APPROVED);
        if (!approved) {
            throw new BusinessRuleException(
                    "QR creation denied: no APPROVED decision for request " + requestUid);
        }

        QrCode qr = QrCode.builder()
                .qrUid(UUID.randomUUID())
                .identityUid(identityUid)
                .qrType(qrType)
                .active(true)
                .createdAt(LocalDateTime.now())
                .build();
        QrCode saved = qrCodeRepository.save(qr);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("qrUid", saved.getQrUid().toString());
        payload.put("identityUid", identityUid.toString());
        payload.put("qrType", qrType.name());
        payload.put("authorizingRequestUid", requestUid.toString());
        auditService.append("QR_CREATED", null, payload);

        log.info("Created {} QR {} for identity {}", qrType, saved.getQrUid(), identityUid);
        return saved;
    }

    /**
     * Bind an identity + QR to a concrete object via an Assignment.
     */
    @Transactional
    public Assignment assign(UUID identityUid, UUID qrUid, String objectUid) {
        Assignment assignment = Assignment.builder()
                .assignmentUid(UUID.randomUUID())
                .identityUid(identityUid)
                .qrUid(qrUid)
                .objectUid(objectUid)
                .status(AssignmentStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .build();
        Assignment saved = assignmentRepository.save(assignment);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("assignmentUid", saved.getAssignmentUid().toString());
        payload.put("identityUid", identityUid.toString());
        payload.put("qrUid", qrUid.toString());
        payload.put("objectUid", objectUid);
        auditService.append("ASSIGNMENT_CREATED", null, payload);

        log.info("Created assignment {} ({} -> {} -> {})",
                saved.getAssignmentUid(), identityUid, qrUid, objectUid);
        return saved;
    }
}
