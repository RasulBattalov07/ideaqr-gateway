package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.Decision;
import com.ideaqr.gateway.domain.History;
import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.Interaction;
import com.ideaqr.gateway.domain.RegistryObject;
import com.ideaqr.gateway.domain.RequestRecord;
import com.ideaqr.gateway.domain.enums.DecisionOutcome;
import com.ideaqr.gateway.domain.enums.EventType;
import com.ideaqr.gateway.domain.enums.HistoryEventType;
import com.ideaqr.gateway.domain.enums.ObjectStatus;
import com.ideaqr.gateway.domain.enums.RequestStatus;
import com.ideaqr.gateway.domain.enums.RequestType;
import com.ideaqr.gateway.repository.DecisionRepository;
import com.ideaqr.gateway.repository.InteractionRepository;
import com.ideaqr.gateway.repository.RegistryObjectRepository;
import com.ideaqr.gateway.repository.RequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Drives the <b>OBJECT LIFECYCLE</b> ({@code CREATED → ACTIVE → MODIFIED →
 * ARCHIVED}). Per the главное архитектурное правило, a lifecycle change is not a
 * side-process: every transition is pushed through the same governance pipeline
 * as any other interaction —
 *
 * <pre>
 *   Identity → Request → Decision → Interaction → Event → History → Trust Score
 * </pre>
 *
 * so the object keeps a complete, immutable change-history ("Система должна
 * сохранять полную историю изменения объекта") and its Trust Score is kept fresh.
 */
@Service
@RequiredArgsConstructor
public class ObjectLifecycleService {

    private final RegistryObjectRepository registryObjectRepository;
    private final RequestRepository requestRepository;
    private final DecisionRepository decisionRepository;
    private final InteractionRepository interactionRepository;
    private final AuditService auditService;
    private final EventService eventService;

    /** Move an object to {@code ACTIVE} (e.g. put it into circulation / un-archive). */
    @Transactional
    public RegistryObject activate(Identity actor, String objectUid, String note) {
        return transition(actor, objectUid, ObjectStatus.ACTIVE,
                HistoryEventType.OBJECT_MODIFIED, EventType.OBJECT_MODIFIED,
                "Объект активирован", note);
    }

    /** Record a change to the object's data/state → {@code MODIFIED}. */
    @Transactional
    public RegistryObject modify(Identity actor, String objectUid, String note) {
        return transition(actor, objectUid, ObjectStatus.MODIFIED,
                HistoryEventType.OBJECT_MODIFIED, EventType.OBJECT_MODIFIED,
                "Объект изменён", note);
    }

    /** Retire an object from circulation → {@code ARCHIVED} (history is kept). */
    @Transactional
    public RegistryObject archive(Identity actor, String objectUid, String note) {
        return transition(actor, objectUid, ObjectStatus.ARCHIVED,
                HistoryEventType.OBJECT_ARCHIVED, EventType.OBJECT_ARCHIVED,
                "Объект архивирован", note);
    }

    /**
     * Transfer ownership of an object to a new identity (FINAL ТЗ — "Изменяется
     * владелец объекта... история объекта должна полностью сохраняться"; Расулу —
     * продажа автомобиля). The object is <b>not</b> re-created: only its
     * {@code ownerIdentityUid} changes, the status moves to {@code MODIFIED}, and the
     * transfer is appended to the immutable journal as {@code OBJECT_TRANSFERRED}, so the
     * object keeps an unbroken chain of custody. Routed through the same governance
     * pipeline as every other interaction.
     */
    @Transactional
    public RegistryObject transfer(Identity actor, String objectUid, UUID newOwnerIdentityUid, String note) {
        if (newOwnerIdentityUid == null) {
            throw new IllegalArgumentException("Не указан новый владелец объекта.");
        }
        RegistryObject object = registryObjectRepository.findByObjectUid(objectUid)
                .orElseThrow(() -> new IllegalArgumentException("Объект не найден: " + objectUid));
        if (object.getStatus() == ObjectStatus.ARCHIVED) {
            throw new IllegalStateException("Нельзя передать архивированный объект.");
        }
        if (newOwnerIdentityUid.equals(object.getOwnerIdentityUid())) {
            throw new IllegalArgumentException("Объект уже принадлежит указанному владельцу.");
        }

        String detail = "Передача владельца объекта"
                + (note != null && !note.isBlank() ? ": " + note.trim() : "");

        // Identity → Request → Decision → Interaction (the governed chain).
        RequestRecord req = requestRepository.save(RequestRecord.builder()
                .identityUid(actor.getIdentityUid())
                .objectUid(objectUid)
                .requestType(RequestType.OBJECT_LIFECYCLE)
                .status(RequestStatus.PENDING)
                .build());

        Decision decision = decisionRepository.save(Decision.builder()
                .requestUid(req.getRequestUid())
                .identityUid(actor.getIdentityUid())
                .outcome(DecisionOutcome.APPROVED)
                .reasonCode("OBJECT_TRANSFER")
                .reason(detail)
                .riskLevel("LOW")
                .build());

        Interaction interaction = interactionRepository.save(Interaction.builder()
                .identityUid(actor.getIdentityUid())
                .requestUid(req.getRequestUid())
                .objectUid(objectUid)
                .targetIdentityUid(newOwnerIdentityUid)
                .interactionType("OBJECT_TRANSFER")
                .detail(detail.length() > 380 ? detail.substring(0, 380) : detail)
                .build());

        req.setStatus(RequestStatus.PROCESSED);
        requestRepository.save(req);

        // Reassign ownership in place (no re-mint); status → MODIFIED; refresh Trust Score.
        object.setOwnerIdentityUid(newOwnerIdentityUid);
        object.setStatus(ObjectStatus.MODIFIED);
        object.setUpdatedAt(LocalDateTime.now());
        object.setTrustScore(computeObjectTrust(objectUid));
        registryObjectRepository.save(object);

        // Event → History (immutable chain of custody — история не теряется).
        auditService.record(actor.getIdentityUid(), objectUid, HistoryEventType.OBJECT_TRANSFERRED, detail,
                req.getRequestUid(), decision.getDecisionUid(), interaction.getInteractionUid());
        eventService.record(EventType.OBJECT_TRANSFERRED, actor.getIdentityUid(), objectUid,
                interaction.getInteractionUid(), detail);

        return object;
    }

    // ------------------------------------------------------------------
    //  Shared pipeline for every lifecycle transition
    // ------------------------------------------------------------------

    private RegistryObject transition(Identity actor, String objectUid, ObjectStatus target,
                                      HistoryEventType historyType, EventType eventType,
                                      String label, String note) {
        RegistryObject object = registryObjectRepository.findByObjectUid(objectUid)
                .orElseThrow(() -> new IllegalArgumentException("Объект не найден: " + objectUid));

        if (object.getStatus() == ObjectStatus.ARCHIVED && target == ObjectStatus.ARCHIVED) {
            throw new IllegalStateException("Объект уже архивирован.");
        }

        String detail = label + (note != null && !note.isBlank() ? ": " + note.trim() : "");

        // Identity → Request → Decision → Interaction (the governed chain).
        RequestRecord req = requestRepository.save(RequestRecord.builder()
                .identityUid(actor.getIdentityUid())
                .objectUid(objectUid)
                .requestType(RequestType.OBJECT_LIFECYCLE)
                .status(RequestStatus.PENDING)
                .build());

        Decision decision = decisionRepository.save(Decision.builder()
                .requestUid(req.getRequestUid())
                .identityUid(actor.getIdentityUid())
                .outcome(DecisionOutcome.APPROVED)
                .reasonCode("OBJECT_LIFECYCLE")
                .reason(detail)
                .riskLevel("LOW")
                .build());

        Interaction interaction = interactionRepository.save(Interaction.builder()
                .identityUid(actor.getIdentityUid())
                .requestUid(req.getRequestUid())
                .objectUid(objectUid)
                .interactionType("OBJECT_LIFECYCLE")
                .detail(detail.length() > 380 ? detail.substring(0, 380) : detail)
                .build());

        req.setStatus(RequestStatus.PROCESSED);
        requestRepository.save(req);

        // Apply the transition and refresh the object's own Trust Score.
        object.setStatus(target);
        object.setUpdatedAt(LocalDateTime.now());
        object.setTrustScore(computeObjectTrust(objectUid));
        registryObjectRepository.save(object);

        // Event → History (immutable change-history).
        History history = auditService.record(actor.getIdentityUid(), objectUid, historyType, detail,
                req.getRequestUid(), decision.getDecisionUid(), interaction.getInteractionUid());
        eventService.record(eventType, actor.getIdentityUid(), objectUid, interaction.getInteractionUid(), detail);

        return object;
    }

    /**
     * Simple, explainable Trust Score for an object: the more governed
     * interactions it accumulates, the more it is trusted. A richer model can
     * replace this later without touching callers.
     */
    private int computeObjectTrust(String objectUid) {
        long interactions = interactionRepository.countByObjectUid(objectUid);
        long raw = 50 + 3L * interactions;
        return (int) Math.max(0, Math.min(100, raw));
    }
}
