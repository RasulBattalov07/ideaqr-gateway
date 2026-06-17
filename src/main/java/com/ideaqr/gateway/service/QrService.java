package com.ideaqr.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.ideaqr.gateway.domain.*;
import com.ideaqr.gateway.domain.enums.*;
import com.ideaqr.gateway.dto.QrCreationRequest;
import com.ideaqr.gateway.dto.QrCreationResponse;
import com.ideaqr.gateway.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * QR governance. Generates real scannable PNG codes (server-side, via ZXing),
 * mints the permanent primary QR for each identity, and runs the full
 * {@code QR_CREATION} governance pipeline when an administrator creates a new
 * object: Request → Decision → QR → Assignment → Interaction → History.
 */
@Service
@RequiredArgsConstructor
public class QrService {

    private final QrRepository qrRepository;
    private final AssignmentRepository assignmentRepository;
    private final RegistryObjectRepository registryObjectRepository;
    private final RequestRepository requestRepository;
    private final DecisionRepository decisionRepository;
    private final InteractionRepository interactionRepository;
    private final AuditService auditService;
    private final ValidationService validationService;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ------------------------------------------------------------------
    //  QR image generation (real, scannable PNG)
    // ------------------------------------------------------------------

    public String generateQrPngDataUri(String text) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            BitMatrix matrix = writer.encode(text, BarcodeFormat.QR_CODE, 360, 360, hints);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось сгенерировать QR-код", e);
        }
    }

    // ------------------------------------------------------------------
    //  Primary identity QR (one permanent QR per verified person)
    // ------------------------------------------------------------------

    public Qr createPrimaryQr(Identity identity) {
        Qr qr = Qr.builder()
                .qrValue("IDENTITY:" + identity.getIdentityUid())
                .qrType(QrType.PRIMARY)
                .status(QrStatus.ACTIVE)
                .ownerIdentityUid(identity.getIdentityUid())
                .build();
        qr = qrRepository.save(qr);

        Assignment assignment = Assignment.builder()
                .qrUid(qr.getQrUid())
                .identityUid(identity.getIdentityUid())
                .assignmentRole("OWNER")
                .build();
        assignmentRepository.save(assignment);
        return qr;
    }

    // ------------------------------------------------------------------
    //  Governed object creation (admin panel "Govern and Create QR codes")
    // ------------------------------------------------------------------

    @Transactional
    public QrCreationResponse createGovernedObject(Identity admin, QrCreationRequest request) {
        ObjectCategory category = parseCategory(request.getCategory());

        // 1. Request enters the pipeline.
        RequestRecord req = RequestRecord.builder()
                .identityUid(admin.getIdentityUid())
                .objectUid(null)
                .requestType(RequestType.QR_CREATION)
                .status(RequestStatus.PENDING)
                .build();
        req = requestRepository.save(req);

        // 2. Decision: only governing roles may mint objects.
        ValidationService.DecisionResult result = validationService.decideQrCreation(admin);
        Decision decision = Decision.builder()
                .requestUid(req.getRequestUid())
                .identityUid(admin.getIdentityUid())
                .outcome(result.outcome())
                .reasonCode(result.reasonCode())
                .reason(result.reason())
                .riskLevel(result.riskLevel())
                .build();
        decision = decisionRepository.save(decision);

        if (result.outcome() != DecisionOutcome.APPROVED) {
            req.setStatus(RequestStatus.FAILED);
            requestRepository.save(req);

            Interaction denied = interactionRepository.save(Interaction.builder()
                    .identityUid(admin.getIdentityUid())
                    .requestUid(req.getRequestUid())
                    .interactionType("QR_CREATION")
                    .detail("Отклонено: " + result.reason())
                    .build());

            History h = auditService.record(admin.getIdentityUid(), req.getRequestUid(),
                    decision.getDecisionUid(), denied.getInteractionUid(), null,
                    HistoryEventType.ACCESS_DENIED, "Отказано в создании QR-объекта: " + result.reason());

            return QrCreationResponse.builder()
                    .success(false)
                    .outcome(result.outcome().name())
                    .reason(result.reason())
                    .identityUid(admin.getIdentityUid().toString())
                    .requestUid(req.getRequestUid().toString())
                    .decisionUid(decision.getDecisionUid().toString())
                    .interactionUid(denied.getInteractionUid().toString())
                    .historyUid(h.getHistoryUid().toString())
                    .build();
        }

        // 3. Approved → generate a unique object UID and the governed QR.
        String objectUid = generateObjectUid(category);
        Qr qr = qrRepository.save(Qr.builder()
                .qrValue(objectUid)
                .qrType(QrType.OBJECT)
                .status(QrStatus.ACTIVE)
                .ownerIdentityUid(admin.getIdentityUid())
                .build());

        // 4. Assignment binds the QR to the governing admin.
        assignmentRepository.save(Assignment.builder()
                .qrUid(qr.getQrUid())
                .identityUid(admin.getIdentityUid())
                .assignmentRole("GOVERNOR")
                .build());

        // 5. Persist the registry object with its contextual card payload.
        Map<String, Object> payload = buildPayload(category, objectUid, request);
        String dataJson = writeJson(payload);
        registryObjectRepository.save(RegistryObject.builder()
                .objectUid(objectUid)
                .category(category)
                .displayName(request.getDisplayName())
                .dataJson(dataJson)
                .createdByIdentityUid(admin.getIdentityUid())
                .qrUid(qr.getQrUid())
                .build());

        // 6. Interaction + 7. append-only history.
        Interaction interaction = interactionRepository.save(Interaction.builder()
                .identityUid(admin.getIdentityUid())
                .requestUid(req.getRequestUid())
                .objectUid(objectUid)
                .interactionType("QR_CREATION")
                .detail("Создан объект «" + request.getDisplayName() + "» (" + category + ")")
                .build());

        req.setObjectUid(objectUid);
        req.setStatus(RequestStatus.PROCESSED);
        requestRepository.save(req);

        History history = auditService.record(admin.getIdentityUid(), req.getRequestUid(),
                decision.getDecisionUid(), interaction.getInteractionUid(), objectUid,
                HistoryEventType.QR_CREATED,
                "Сгенерирован QR-код для объекта «" + request.getDisplayName() + "»");

        return QrCreationResponse.builder()
                .success(true)
                .outcome(result.outcome().name())
                .reason(result.reason())
                .objectUid(objectUid)
                .displayName(request.getDisplayName())
                .category(category.name())
                .qrUid(qr.getQrUid().toString())
                .qrImageDataUri(generateQrPngDataUri(objectUid))
                .identityUid(admin.getIdentityUid().toString())
                .requestUid(req.getRequestUid().toString())
                .decisionUid(decision.getDecisionUid().toString())
                .interactionUid(interaction.getInteractionUid().toString())
                .historyUid(history.getHistoryUid().toString())
                .createdAt(java.time.LocalDateTime.now().format(TS))
                .build();
    }

    public List<RegistryObject> listObjectsForAdmin(UUID adminIdentityUid) {
        return registryObjectRepository.findByCreatedByIdentityUidOrderByCreatedAtDesc(adminIdentityUid);
    }

    public String regenerateImageFor(String objectUid) {
        return generateQrPngDataUri(objectUid);
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private ObjectCategory parseCategory(String raw) {
        if (raw == null) {
            return ObjectCategory.GENERAL;
        }
        try {
            return ObjectCategory.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return ObjectCategory.GENERAL;
        }
    }

    private String generateObjectUid(ObjectCategory category) {
        String prefix = switch (category) {
            case RETAIL -> "RETAIL";
            case MEDICAL -> "PATIENT";
            case ECO -> "ECO";
            case INFRASTRUCTURE -> "INFRA";
            default -> "OBJECT";
        };
        String candidate;
        do {
            String suffix = UUID.randomUUID().toString().replace("-", "")
                    .substring(0, 8).toUpperCase(Locale.ROOT);
            candidate = prefix + "_" + suffix;
        } while (registryObjectRepository.existsByObjectUid(candidate));
        return candidate;
    }

    private Map<String, Object> buildPayload(ObjectCategory category, String objectUid, QrCreationRequest r) {
        Map<String, Object> data = new LinkedHashMap<>();
        switch (category) {
            case RETAIL -> {
                data.put("productName", r.getDisplayName());
                data.put("brand", orDefault(r.getBrand(), "—"));
                data.put("sku", objectUid);
                data.put("price", r.getPrice() != null ? r.getPrice() : 0L);
                data.put("currency", orDefault(r.getCurrency(), "₸"));
                data.put("description", orDefault(r.getDescription(), "Описание не указано."));
                List<Map<String, Object>> sizes = new ArrayList<>();
                if (r.getSizes() != null) {
                    for (QrCreationRequest.SizeStock s : r.getSizes()) {
                        if (s.getSize() == null || s.getSize().isBlank()) {
                            continue;
                        }
                        Map<String, Object> sm = new LinkedHashMap<>();
                        sm.put("size", s.getSize());
                        sm.put("stock", s.getStock() != null ? s.getStock() : 0);
                        sizes.add(sm);
                    }
                }
                data.put("sizes", sizes);
                List<Map<String, Object>> alts = new ArrayList<>();
                if (r.getAlternatives() != null) {
                    for (QrCreationRequest.Alternative a : r.getAlternatives()) {
                        if (a.getStore() == null || a.getStore().isBlank()) {
                            continue;
                        }
                        Map<String, Object> am = new LinkedHashMap<>();
                        am.put("store", a.getStore());
                        am.put("price", a.getPrice() != null ? a.getPrice() : 0L);
                        am.put("url", orDefault(a.getUrl(), "#"));
                        am.put("note", orDefault(a.getNote(), ""));
                        alts.add(am);
                    }
                }
                data.put("alternatives", alts);
                if (r.getDiscountCode() != null && !r.getDiscountCode().isBlank()) {
                    Map<String, Object> loyalty = new LinkedHashMap<>();
                    loyalty.put("code", r.getDiscountCode());
                    loyalty.put("discount", "");
                    loyalty.put("note", orDefault(r.getDiscountNote(),
                            "Эксклюзивный промокод в приложении IDEAQR."));
                    data.put("loyalty", loyalty);
                }
            }
            case ECO -> {
                data.put("binId", objectUid);
                data.put("title", r.getDisplayName());
                data.put("location", orDefault(r.getLocation(), "Местоположение не указано"));
                data.put("status", "В эксплуатации");
                data.put("environmentalTier", "Зелёный уровень");
                data.put("description", orDefault(r.getDescription(), ""));
                data.put("actions", List.of("report"));
            }
            case INFRASTRUCTURE -> {
                data.put("assetId", objectUid);
                data.put("title", r.getDisplayName());
                data.put("location", orDefault(r.getLocation(), "Местоположение не указано"));
                data.put("status", "В эксплуатации");
                data.put("technicalNotes", orDefault(r.getDescription(), ""));
                data.put("actions", List.of("report"));
            }
            case MEDICAL -> {
                data.put("patientName", r.getDisplayName());
                data.put("patientId", objectUid);
                data.put("description", orDefault(r.getDescription(), ""));
                data.put("note", "Демонстрационная карта, созданная администратором.");
            }
            default -> {
                data.put("title", r.getDisplayName());
                data.put("description", orDefault(r.getDescription(), ""));
            }
        }
        if (r.getExtra() != null && !r.getExtra().isEmpty()) {
            data.put("extra", r.getExtra());
        }
        return data;
    }

    private String orDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Ошибка сериализации данных объекта", e);
        }
    }
}
