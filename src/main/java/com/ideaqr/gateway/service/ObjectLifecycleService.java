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
import com.ideaqr.gateway.domain.enums.IdentityType;
import com.ideaqr.gateway.repository.DecisionRepository;
import com.ideaqr.gateway.repository.IdentityRepository;
import com.ideaqr.gateway.repository.InteractionRepository;
import com.ideaqr.gateway.repository.RegistryObjectRepository;
import com.ideaqr.gateway.repository.RequestRepository;
import com.ideaqr.gateway.repository.UserRepository;
import com.ideaqr.gateway.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
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
    private final IdentityRepository identityRepository;
    private final AuditService auditService;
    private final EventService eventService;
    private final NotificationService notificationService;
    private final RegistryClient registryClient;
    private final UserRepository userRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

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
        RegistryObject object = requireObject(objectUid);
        if (object.getStatus() == ObjectStatus.ARCHIVED) {
            throw new IllegalStateException("Нельзя передать архивированный объект.");
        }
        if (newOwnerIdentityUid.equals(object.getOwnerIdentityUid())) {
            throw new IllegalArgumentException("Объект уже принадлежит указанному владельцу.");
        }
        // Audit H-3: the new owner must be a real identity, or the chain of custody would
        // point at a non-existent owner (an orphaned object). Any-tenant on purpose: the
        // recipient may live in another tenant (citizen → org specialist hand-over).
        if (identityRepository.countByIdentityUidAnyTenant(newOwnerIdentityUid) == 0) {
            throw new IllegalArgumentException("Указанный новый владелец не найден в системе.");
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

        // Tell the new owner so the hand-off surfaces in their "Мои объекты" view (Point 3).
        notificationService.notify(newOwnerIdentityUid, "OBJECT_TRANSFER",
                "Вам передан объект «" + object.getDisplayName() + "». Откройте вкладку «Мои объекты».");

        return object;
    }

    // ------------------------------------------------------------------
    //  УНИВЕРСАЛЬНОЕ ПРАВИЛО ОБЪЕКТОВ — самообслуживание владения (BLOCK 1)
    // ------------------------------------------------------------------

    /**
     * Pre-ownership → покупка/привязка: зарегистрированный пользователь оформляет владение
     * бесхозной вещью (объект каталога без владельца). Ключевой инвариант платформы:
     * <b>QR объекта НИКОГДА не меняется</b> — идентификатор из каталога материализуется как
     * персистентный реестровый объект с тем же {@code objectUid}, меняется только владелец.
     * Объект остаётся в публичном тенанте (вещь принадлежит человеку, не организации),
     * а привязка проходит полный конвейер и фиксируется как {@code OBJECT_TRANSFERRED}.
     */
    @Transactional
    public RegistryObject claim(Identity actor, String objectUid) {
        if (actor.getIdentityType() == IdentityType.GUEST) {
            throw new AccessDeniedException("Оформление владения доступно только зарегистрированным пользователям.");
        }
        if (CitizenDossierService.isDossierObject(objectUid)) {
            throw new IllegalArgumentException("Документы цифрового досье не передаются во владение.");
        }
        if (registryObjectRepository.findByObjectUidAnyTenant(objectUid).isPresent()) {
            throw new IllegalStateException(
                    "У объекта уже есть владелец. Право владения передаёт только текущий владелец.");
        }
        RegistryClient.Resolved resolved = registryClient.resolve(objectUid);
        if (!resolved.known()) {
            throw new IllegalArgumentException("Объект не найден в реестре.");
        }
        boolean item = AiCardService.ITEM_CATEGORIES.contains(resolved.category())
                && (resolved.category() == com.ideaqr.gateway.domain.enums.ObjectCategory.RETAIL
                        || (resolved.data() != null && resolved.data().get("itemType") != null));
        if (!item) {
            throw new IllegalArgumentException("Этот объект не является передаваемой вещью.");
        }

        RegistryObject object = registryObjectRepository.save(RegistryObject.builder()
                .objectUid(objectUid)
                .category(resolved.category())
                .displayName(resolved.displayName())
                .dataJson(toJson(resolved.data()))
                .createdByIdentityUid(actor.getIdentityUid())
                .ownerIdentityUid(actor.getIdentityUid())
                // Личная вещь: всегда публичный тенант, чтобы её QR разрешался для любого
                // сканирующего (доступ к содержимому решает движок политик, не изоляция).
                .tenantId(TenantContext.PUBLIC_TENANT)
                .build());

        String detail = "Оформление владения (покупка): «" + object.getDisplayName()
                + "». QR объекта не изменился — изменился только владелец.";
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
                .reasonCode("OWNERSHIP_CLAIMED")
                .reason(detail)
                .riskLevel("LOW")
                .build());
        Interaction interaction = interactionRepository.save(Interaction.builder()
                .identityUid(actor.getIdentityUid())
                .requestUid(req.getRequestUid())
                .objectUid(objectUid)
                .interactionType("OBJECT_TRANSFER")
                .detail(detail.length() > 380 ? detail.substring(0, 380) : detail)
                .build());
        req.setStatus(RequestStatus.PROCESSED);
        requestRepository.save(req);

        auditService.record(actor.getIdentityUid(), objectUid, HistoryEventType.OBJECT_TRANSFERRED, detail,
                req.getRequestUid(), decision.getDecisionUid(), interaction.getInteractionUid());
        eventService.record(EventType.OBJECT_TRANSFERRED, actor.getIdentityUid(), objectUid,
                interaction.getInteractionUid(), detail);
        notificationService.notify(actor.getIdentityUid(), "OBJECT_TRANSFER",
                "Вы стали владельцем объекта «" + object.getDisplayName() + "». Он появился в «Мои объекты».");
        return object;
    }

    /**
     * Передача права владения самим владельцем (продажа/дарение вещи). В отличие от
     * административного {@link #transfer}, здесь действует жёсткое правило: инициировать
     * передачу может ТОЛЬКО текущий владелец. Получатель задаётся именем пользователя или
     * UID личности (значение личного QR).
     */
    @Transactional
    public RegistryObject transferByOwner(Identity actor, String objectUid, String newOwnerRef, String note) {
        if (actor.getIdentityType() == IdentityType.GUEST) {
            throw new AccessDeniedException("Передача владения доступна только зарегистрированным пользователям.");
        }
        if (CitizenDossierService.isDossierObject(objectUid)) {
            throw new IllegalArgumentException("Документы цифрового досье непередаваемы.");
        }
        RegistryObject object = requireObject(objectUid);
        if (!actor.getIdentityUid().equals(object.getOwnerIdentityUid())) {
            throw new AccessDeniedException("Передавать право владения может только текущий владелец объекта.");
        }
        return transfer(actor, objectUid, resolveIdentityRef(newOwnerRef), note);
    }

    /** Получатель передачи: UID личности (в т.ч. из значения QR {@code IDENTITY:<uuid>}) или username. */
    private UUID resolveIdentityRef(String ref) {
        String v = ref == null ? "" : ref.trim();
        if (v.toUpperCase(java.util.Locale.ROOT).startsWith("IDENTITY:")) {
            v = v.substring("IDENTITY:".length()).trim();
        }
        if (v.isEmpty()) {
            throw new IllegalArgumentException("Укажите получателя: имя пользователя или UID личности.");
        }
        try {
            return UUID.fromString(v);
        } catch (IllegalArgumentException notUuid) {
            // Получатель может жить в другом тенанте (передача «по нику», как перевод по
            // номеру телефона) — ищем кросс-тенантно, наружу отдаётся только identity uid.
            String username = v;
            return userRepository.findIdentityUidByUsernameAnyTenant(username)
                    .map(UUID::fromString)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Получатель «" + username + "» не найден: укажите имя пользователя или UID личности."));
        }
    }

    // ------------------------------------------------------------------
    //  Shared pipeline for every lifecycle transition
    // ------------------------------------------------------------------

    /**
     * Same-tenant lookup with an any-tenant fallback + manual guard: личная вещь живёт в
     * публичном тенанте, и её владелец-специалист из орг-тенанта обязан уметь ею управлять.
     * Кросс-тенантные ЧУЖИЕ объекты остаются невидимыми (дисциплина audit H-2).
     */
    private RegistryObject requireObject(String objectUid) {
        return registryObjectRepository.findByObjectUid(objectUid)
                .or(() -> registryObjectRepository.findByObjectUidAnyTenant(objectUid).filter(this::tenantVisible))
                .orElseThrow(() -> new IllegalArgumentException("Объект не найден: " + objectUid));
    }

    private boolean tenantVisible(RegistryObject o) {
        UUID caller = TenantContext.getTenantId();
        if (caller == null) {
            return true; // admin / system — unscoped
        }
        UUID tenant = o.getTenantId();
        return tenant == null || tenant.equals(TenantContext.PUBLIC_TENANT) || tenant.equals(caller);
    }

    private String toJson(java.util.Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data != null ? data : java.util.Map.of());
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось сериализовать данные объекта", e);
        }
    }

    private RegistryObject transition(Identity actor, String objectUid, ObjectStatus target,
                                      HistoryEventType historyType, EventType eventType,
                                      String label, String note) {
        RegistryObject object = requireObject(objectUid);

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
