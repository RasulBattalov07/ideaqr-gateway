package com.ideaqr.gateway.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideaqr.gateway.domain.Decision;
import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.Interaction;
import com.ideaqr.gateway.domain.RequestRecord;
import com.ideaqr.gateway.domain.enums.DecisionOutcome;
import com.ideaqr.gateway.domain.enums.EventType;
import com.ideaqr.gateway.domain.enums.HistoryEventType;
import com.ideaqr.gateway.domain.enums.InteractionStatus;
import com.ideaqr.gateway.domain.enums.RequestStatus;
import com.ideaqr.gateway.domain.enums.RequestType;
import com.ideaqr.gateway.domain.enums.RoleType;
import com.ideaqr.gateway.repository.DecisionRepository;
import com.ideaqr.gateway.repository.InteractionRepository;
import com.ideaqr.gateway.repository.RequestRepository;
import com.ideaqr.gateway.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The <b>Doctor → Pharmacist</b> flow ("терапевт делает назначение — фармацевт выдаёт").
 *
 * <p>A prescription is not a side-table: it is a first-class governed {@link Interaction}
 * attached to a medical object. A doctor <em>writes</em> it ({@code PENDING}); a pharmacist
 * <em>dispenses</em> it ({@code CONFIRMED}). Both actions run through the same
 * Request → Decision → Interaction → History chain as every other action, so the
 * prescription has a full, immutable audit trail — and no schema migration is needed
 * (the interaction type is free text; the status reuses the existing lifecycle).</p>
 */
@Service
@RequiredArgsConstructor
public class MedicalService {

    /** Marks an interaction row as a prescription (free-text interaction_type — no migration). */
    public static final String RX_TYPE = "PRESCRIPTION";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final InteractionRepository interactionRepository;
    private final RequestRepository requestRepository;
    private final DecisionRepository decisionRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final EventService eventService;
    private final ObjectMapper objectMapper;

    /** A doctor writes a prescription onto a medical object. */
    @Transactional
    public List<Map<String, Object>> prescribe(Identity doctor, String objectUid,
                                               String name, String dose, String schedule) {
        if (!doctor.getRoles().contains(RoleType.DOCTOR)) {
            throw new AccessDeniedException("Выписывать рецепты может только врач.");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Укажите наименование препарата.");
        }
        String prescriber = displayName(doctor.getIdentityUid());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name.trim());
        payload.put("dose", dose != null ? dose.trim() : "");
        payload.put("schedule", schedule != null ? schedule.trim() : "");
        payload.put("prescriber", prescriber);
        payload.put("prescribedAt", LocalDateTime.now().format(TS));

        RequestRecord req = requestRepository.save(RequestRecord.builder()
                .identityUid(doctor.getIdentityUid())
                .objectUid(objectUid)
                .requestType(RequestType.ACCESS)
                .status(RequestStatus.PROCESSED)
                .build());
        Decision decision = decisionRepository.save(Decision.builder()
                .requestUid(req.getRequestUid())
                .identityUid(doctor.getIdentityUid())
                .outcome(DecisionOutcome.APPROVED)
                .reasonCode("PRESCRIPTION_ISSUED")
                .reason("Назначение выписано врачом.")
                .riskLevel("LOW")
                .build());
        Interaction rx = interactionRepository.save(Interaction.builder()
                .identityUid(doctor.getIdentityUid())
                .requestUid(req.getRequestUid())
                .objectUid(objectUid)
                .interactionType(RX_TYPE)
                .status(InteractionStatus.PENDING)
                .detail(serialize(payload))
                .build());

        auditService.record(doctor.getIdentityUid(), objectUid, HistoryEventType.OBJECT_MODIFIED,
                "Врач выписал назначение: " + name.trim(),
                req.getRequestUid(), decision.getDecisionUid(), rx.getInteractionUid());
        eventService.record(EventType.OBJECT_MODIFIED, doctor.getIdentityUid(), objectUid,
                rx.getInteractionUid(), "Назначение выписано: " + name.trim());

        return listForObject(objectUid);
    }

    /** A pharmacist dispenses a pending prescription. */
    @Transactional
    public List<Map<String, Object>> dispense(Identity pharmacist, UUID prescriptionUid) {
        if (!pharmacist.getRoles().contains(RoleType.PHARMACIST)) {
            throw new AccessDeniedException("Выдавать препараты по рецепту может только фармацевт.");
        }
        Interaction rx = interactionRepository.findById(prescriptionUid)
                .orElseThrow(() -> new IllegalArgumentException("Назначение не найдено."));
        if (!RX_TYPE.equals(rx.getInteractionType())) {
            throw new IllegalArgumentException("Указанная запись не является назначением.");
        }
        if (rx.getStatus() == InteractionStatus.CONFIRMED) {
            throw new IllegalStateException("Препарат по этому рецепту уже выдан.");
        }

        Map<String, Object> payload = deserialize(rx.getDetail());
        payload.put("dispensedBy", displayName(pharmacist.getIdentityUid()));
        payload.put("dispensedAt", LocalDateTime.now().format(TS));
        rx.setDetail(serialize(payload));
        rx.setStatus(InteractionStatus.CONFIRMED);
        interactionRepository.save(rx);

        auditService.record(pharmacist.getIdentityUid(), rx.getObjectUid(), HistoryEventType.OBJECT_MODIFIED,
                "Фармацевт выдал препарат по рецепту: " + payload.getOrDefault("name", ""));
        eventService.record(EventType.OBJECT_MODIFIED, pharmacist.getIdentityUid(), rx.getObjectUid(),
                rx.getInteractionUid(), "Препарат выдан по рецепту.");

        return listForObject(rx.getObjectUid());
    }

    /** Live prescriptions attached to a medical object, newest first (for the card). */
    public List<Map<String, Object>> listForObject(String objectUid) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Interaction rx : interactionRepository
                .findByObjectUidAndInteractionTypeOrderByCreatedAtDesc(objectUid, RX_TYPE)) {
            Map<String, Object> payload = deserialize(rx.getDetail());
            Map<String, Object> m = new LinkedHashMap<>(payload);
            m.put("prescriptionUid", rx.getInteractionUid().toString());
            m.put("status", rx.getStatus() == InteractionStatus.CONFIRMED ? "DISPENSED" : "PRESCRIBED");
            rows.add(m);
        }
        return rows;
    }

    // ------------------------------------------------------------------

    private String displayName(UUID identityUid) {
        return userRepository.findByIdentityUid(identityUid)
                .map(u -> (u.getFirstName() + " " + u.getLastName()).trim())
                .orElse("Специалист");
    }

    private String serialize(Map<String, Object> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            // detail is varchar(400); keep prescriptions compact so they always fit.
            return json.length() > 400 ? json.substring(0, 400) : json;
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> deserialize(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }
}
