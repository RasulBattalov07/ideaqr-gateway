package com.ideaqr.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.ideaqr.gateway.domain.*;
import com.ideaqr.gateway.domain.enums.*;
import com.ideaqr.gateway.dto.QrCreationRequest;
import com.ideaqr.gateway.dto.QrCreationResponse;
import com.ideaqr.gateway.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Mints QR codes and runs the QR_CREATION governance pipeline. QR codes are
 * identifiers only; the scannable PNG is generated server-side with ZXing and
 * returned as a data URI. Created objects are persisted so the citizen terminal
 * can resolve them later.
 */
@Service
@RequiredArgsConstructor
public class QrService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final QrRepository qrRepository;
    private final RegistryObjectRepository registryObjectRepository;
    private final AssignmentRepository assignmentRepository;
    private final RequestRepository requestRepository;
    private final DecisionRepository decisionRepository;
    private final InteractionRepository interactionRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    /** Mint the permanent primary identity QR (called once during registration). */
    @Transactional
    public Qr createPrimaryQr(Identity identity) {
        Qr qr = qrRepository.save(Qr.builder()
                .qrValue("IDENTITY:" + identity.getIdentityUid())
                .qrType(QrType.PRIMARY)
                .status(QrStatus.ACTIVE)
                .ownerIdentity(identity)
                .build());
        assignmentRepository.save(Assignment.builder()
                .qr(qr)
                .identity(identity)
                .assignmentRole("OWNER")
                .build());
        return qr;
    }

    /** Run the full QR_CREATION pipeline for a new governed object (admin panel). */
    @Transactional
    public QrCreationResponse createGovernedObject(Identity admin, QrCreationRequest request) {
        ObjectCategory category = parseCategory(request.getCategory());
        String objectUid = generateObjectUid(category);
        String displayName = request.getDisplayName().trim();

        RequestRecord req = requestRepository.save(RequestRecord.builder()
                .identityUid(admin.getIdentityUid())
                .objectUid(objectUid)
                .requestType(RequestType.QR_CREATION)
                .status(RequestStatus.PENDING)
                .build());

        Decision decision = decisionRepository.save(Decision.builder()
                .requestUid(req.getRequestUid())
                .identityUid(admin.getIdentityUid())
                .outcome(DecisionOutcome.APPROVED)
                .reasonCode("ADMIN_AUTHORIZED")
                .reason("Объект создан администратором и зарегистрирован в реестре.")
                .riskLevel("LOW")
                .build());

        Qr qr = qrRepository.save(Qr.builder()
                .qrValue(objectUid)
                .qrType(QrType.OBJECT)
                .status(QrStatus.ACTIVE)
                .ownerIdentity(admin)
                .build());

        RegistryObject object = registryObjectRepository.save(RegistryObject.builder()
                .objectUid(objectUid)
                .category(category)
                .displayName(displayName)
                .dataJson(buildDataJson(category, objectUid, request))
                .createdByIdentityUid(admin.getIdentityUid())
                .qrUid(qr.getQrUid())
                .build());

        assignmentRepository.save(Assignment.builder()
                .qr(qr)
                .identity(admin)
                .assignmentRole("GOVERNOR")
                .build());

        Interaction interaction = interactionRepository.save(Interaction.builder()
                .identityUid(admin.getIdentityUid())
                .requestUid(req.getRequestUid())
                .objectUid(objectUid)
                .interactionType("QR_CREATION")
                .detail("Создан объект «" + displayName + "»")
                .build());

        req.setStatus(RequestStatus.PROCESSED);
        requestRepository.save(req);

        History history = auditService.record(admin.getIdentityUid(), objectUid, HistoryEventType.QR_CREATED,
                "Создан управляемый QR-код для объекта «" + displayName + "»",
                req.getRequestUid(), decision.getDecisionUid(), interaction.getInteractionUid());

        return QrCreationResponse.builder()
                .success(true)
                .outcome(decision.getOutcome().name())
                .reason(decision.getReason())
                .objectUid(objectUid)
                .displayName(displayName)
                .category(category.name())
                .qrUid(qr.getQrUid().toString())
                .qrImageDataUri(pngDataUri(objectUid))
                .identityUid(admin.getIdentityUid().toString())
                .requestUid(req.getRequestUid().toString())
                .decisionUid(decision.getDecisionUid().toString())
                .interactionUid(interaction.getInteractionUid().toString())
                .historyUid(history.getHistoryUid().toString())
                .createdAt(object.getCreatedAt() != null ? object.getCreatedAt().format(TS) : null)
                .build();
    }

    /** Objects visible in the admin panel (the whole registry of minted objects). */
    public List<RegistryObject> listObjectsForAdmin(UUID adminIdentityUid) {
        return registryObjectRepository.findAllByOrderByCreatedAtDesc();
    }

    /** Server-paginated object list for the admin panel (audit M-2). */
    public Page<RegistryObject> listObjectsForAdmin(Pageable pageable) {
        return registryObjectRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    /** Regenerate the scannable PNG for an existing object (the QR encodes its UID). */
    public String regenerateImageFor(String objectUid) {
        return pngDataUri(objectUid);
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private ObjectCategory parseCategory(String raw) {
        try {
            return ObjectCategory.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return ObjectCategory.GENERAL;
        }
    }

    private String generateObjectUid(ObjectCategory category) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        return category.name() + "_" + suffix;
    }

    /** Build the category-specific card payload that the SPA will render. */
    private String buildDataJson(ObjectCategory category, String objectUid, QrCreationRequest r) {
        Map<String, Object> d = new LinkedHashMap<>();
        String name = r.getDisplayName() != null ? r.getDisplayName().trim() : objectUid;
        switch (category) {
            case RETAIL -> {
                d.put("productName", name);
                if (notBlank(r.getBrand())) d.put("brand", r.getBrand().trim());
                d.put("sku", objectUid);
                if (r.getPrice() != null) d.put("price", r.getPrice());
                d.put("currency", notBlank(r.getCurrency()) ? r.getCurrency().trim() : "₸");
                if (notBlank(r.getDescription())) d.put("description", r.getDescription().trim());
                List<Map<String, Object>> sizes = new ArrayList<>();
                if (r.getSizes() != null) {
                    for (QrCreationRequest.SizeDto s : r.getSizes()) {
                        if (!notBlank(s.getSize())) continue;
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("size", s.getSize().trim());
                        m.put("stock", s.getStock() != null ? s.getStock() : 0);
                        sizes.add(m);
                    }
                }
                if (!sizes.isEmpty()) d.put("sizes", sizes);
                List<Map<String, Object>> alts = new ArrayList<>();
                if (r.getAlternatives() != null) {
                    for (QrCreationRequest.AlternativeDto a : r.getAlternatives()) {
                        if (!notBlank(a.getStore())) continue;
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("store", a.getStore().trim());
                        if (a.getPrice() != null) m.put("price", a.getPrice());
                        String safeUrl = sanitizeUrl(a.getUrl());
                        if (safeUrl != null) m.put("url", safeUrl);
                        if (notBlank(a.getNote())) m.put("note", a.getNote().trim());
                        alts.add(m);
                    }
                }
                if (!alts.isEmpty()) d.put("alternatives", alts);
                if (notBlank(r.getDiscountCode())) {
                    Map<String, Object> loyalty = new LinkedHashMap<>();
                    loyalty.put("code", r.getDiscountCode().trim());
                    if (notBlank(r.getDiscountNote())) loyalty.put("note", r.getDiscountNote().trim());
                    d.put("loyalty", loyalty);
                }
            }
            case ECO -> {
                d.put("title", name);
                d.put("binId", objectUid);
                if (notBlank(r.getLocation())) d.put("location", r.getLocation().trim());
                d.put("status", "В работе");
                if (notBlank(r.getDescription())) d.put("note", r.getDescription().trim());
            }
            case INFRASTRUCTURE -> {
                d.put("title", name);
                d.put("assetId", objectUid);
                if (notBlank(r.getLocation())) d.put("location", r.getLocation().trim());
                d.put("status", "В работе");
                if (notBlank(r.getDescription())) d.put("technicalNotes", r.getDescription().trim());
            }
            case MEDICAL -> {
                d.put("patientName", name);
                d.put("patientId", objectUid);
                if (notBlank(r.getDescription())) d.put("note", r.getDescription().trim());
            }
            default -> {
                d.put("title", name);
                if (notBlank(r.getDescription())) d.put("description", r.getDescription().trim());
            }
        }
        try {
            return objectMapper.writeValueAsString(d);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось сериализовать данные объекта", e);
        }
    }

    private boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /**
     * Accept only {@code http(s)} links into a stored card (audit L-1). Anything else
     * — {@code javascript:}, {@code data:}, etc. — is dropped so it can never be rendered
     * into an {@code href} and become a stored-XSS sink.
     */
    private String sanitizeUrl(String url) {
        if (!notBlank(url)) {
            return null;
        }
        String trimmed = url.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return (lower.startsWith("http://") || lower.startsWith("https://")) ? trimmed : null;
    }

    private String pngDataUri(String text) {
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(pngBytes(text));
    }

    /**
     * Encode {@code text} as a 320×320 PNG QR code. Output is deterministic for a
     * given value, so callers (e.g. the cached {@code GET /api/qr/{uid}.png} endpoint)
     * can safely cache it instead of re-encoding on every list render (audit 3.4).
     */
    public byte[] pngBytes(String text) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 320, 320, hints);
            BufferedImage image = MatrixToImageWriter.toBufferedImage(matrix);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось сгенерировать QR-код", e);
        }
    }
}
