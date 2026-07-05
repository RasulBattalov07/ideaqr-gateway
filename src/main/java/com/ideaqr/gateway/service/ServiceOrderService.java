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
 * Модуль «УСЛУГИ И БЫТ» (Phase 2): бытовая заявка (вывоз мусора, сантехник, электрик…)
 * привязывается к профилю гражданина и проходит ТОТ ЖЕ управляемый конвейер, что и любое
 * действие платформы: Request(SERVICE_ORDER) → Decision → Interaction → History → Event.
 * Никакой отдельной таблицы: заявка — это governed Interaction с JSON-payload в detail
 * (тот же приём, что у рецептов), поэтому схема не меняется, а неизменяемый след полный.
 *
 * <p><b>Двусторонний флоу (P0 из ZAKAZDAR-аудита).</b> Вторая сторона заявки — исполнитель
 * с ролью {@link RoleType#SERVICE_OPERATOR} — кодируется существующими колонками, без миграции:
 * {@code target_identity_uid} (свободное для объектных interaction'ов поле «вторая сторона»)
 * хранит исполнителя, принявшего заявку, а стадия «работа выполнена, ждём заказчика» — ключ
 * {@code stage=DONE} в JSON {@code detail}. Жизненный цикл в терминах UI:
 * NEW (PENDING, исполнителя нет) → ACCEPTED (PENDING + исполнитель) → DONE (PENDING +
 * stage=DONE) → COMPLETED (CONFIRMED — подтвердил заказчик). Статусная колонка при этом
 * не выходит за CHECK-ограничение V1 (PENDING/CONFIRMED/REJECTED).</p>
 */
@Service
@RequiredArgsConstructor
public class ServiceOrderService {

    /** Тип interaction-строки заявки (free-text колонка — без миграции). */
    public static final String ORDER_TYPE = "SERVICE_ORDER";
    /** Ключ стадии в detail-JSON: исполнитель завершил работу, ждём подтверждения заказчика. */
    static final String STAGE_KEY = "stage";
    static final String STAGE_DONE = "DONE";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** Каталог демо-услуг ЖКХ (mock: карточки услуг фиксированы, исполнители — живые). */
    public record CatalogItem(String key, String icon, String label, String description,
                              String operator, String price, String eta) {}

    public static final List<CatalogItem> CATALOG = List.of(
            new CatalogItem("TRASH_PICKUP", "🗑", "Вывоз мусора от двери",
                    "Заберём бытовые отходы от двери квартиры в течение часа.",
                    "УК «Comfort Service»", "500 ₸ / заявка", "до 60 минут"),
            new CatalogItem("PLUMBER", "🔧", "Вызов сантехника",
                    "Протечки, засоры, замена смесителей и арматуры.",
                    "Сервис «Астана Су»", "от 4 000 ₸", "сегодня 14:00–18:00"),
            new CatalogItem("ELECTRICIAN", "⚡", "Вызов электрика",
                    "Розетки, автоматы, освещение — диагностика и ремонт.",
                    "АО «Астана-РЭК»", "от 3 500 ₸", "завтра 09:00–12:00"),
            new CatalogItem("CLEANING", "🧹", "Уборка подъезда / территории",
                    "Внеплановая уборка мест общего пользования по заявке жильца.",
                    "УК «Comfort Service»", "по тарифу КСК", "в течение дня"));

    private final RequestRepository requestRepository;
    private final DecisionRepository decisionRepository;
    private final InteractionRepository interactionRepository;
    private final AuditService auditService;
    private final EventService eventService;
    private final NotificationService notificationService;
    private final OrganizationService organizationService;
    private final CitizenDossierService citizenDossierService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    /** Оформить заявку: полный конвейер + уведомление. Адрес подтягивается из eGov-досье. */
    @Transactional
    public Map<String, Object> order(Identity identity, String serviceKey, String note) {
        CatalogItem item = CATALOG.stream()
                .filter(c -> c.key().equalsIgnoreCase(serviceKey == null ? "" : serviceKey.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Неизвестная услуга. Выберите услугу из каталога."));

        var actingOrg = organizationService.resolveActingOrganization(identity.getIdentityUid());
        UUID organizationUid = actingOrg != null ? actingOrg.getOrganizationUid() : null;

        RequestRecord req = requestRepository.save(RequestRecord.builder()
                .identityUid(identity.getIdentityUid())
                .organizationUid(organizationUid)
                .objectUid("SERVICE_" + item.key())
                .requestType(RequestType.SERVICE_ORDER)
                .status(RequestStatus.PENDING)
                .build());
        Decision decision = decisionRepository.save(Decision.builder()
                .requestUid(req.getRequestUid())
                .identityUid(identity.getIdentityUid())
                .outcome(DecisionOutcome.APPROVED)
                .reasonCode("SERVICE_ACCEPTED")
                .reason("Заявка принята и передана в очередь исполнителей (" + item.operator() + ").")
                .riskLevel("LOW")
                .build());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("service", item.key());
        payload.put("label", item.label());
        payload.put("icon", item.icon());
        payload.put("operator", item.operator());
        payload.put("price", item.price());
        payload.put("eta", item.eta());
        // detail-колонка — varchar(400): адрес и комментарий ограничиваем, чтобы JSON
        // никогда не резался даже после дописывания stage/completedAt (см. serialize()).
        payload.put("address", clip(addressOf(identity), 48));
        if (note != null && !note.isBlank()) {
            payload.put("note", clip(note.trim(), 60));
        }
        payload.put("orderedAt", LocalDateTime.now().format(TS));

        Interaction order = interactionRepository.save(Interaction.builder()
                .identityUid(identity.getIdentityUid())
                .requestUid(req.getRequestUid())
                .objectUid("SERVICE_" + item.key())
                .interactionType(ORDER_TYPE)
                .status(InteractionStatus.PENDING)
                .detail(serialize(payload))
                .build());

        req.setStatus(RequestStatus.PROCESSED);
        requestRepository.save(req);

        auditService.record(identity.getIdentityUid(), "SERVICE_" + item.key(), HistoryEventType.ISSUE_REPORTED,
                "Заявка на услугу «" + item.label() + "» принята.",
                req.getRequestUid(), decision.getDecisionUid(), order.getInteractionUid());
        eventService.record(EventType.SERVICE_STARTED, identity.getIdentityUid(), "SERVICE_" + item.key(),
                order.getInteractionUid(), "Заявка: " + item.label());
        notificationService.notify(identity.getIdentityUid(), "SERVICE",
                "Заявка «" + item.label() + "» принята. Ожидает исполнителя (" + item.operator() + ").");

        return row(order, payload);
    }

    /** Мои заявки, новые сверху. */
    public List<Map<String, Object>> mine(Identity identity) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Interaction i : interactionRepository
                .findByIdentityUidAndInteractionTypeOrderByCreatedAtDesc(identity.getIdentityUid(), ORDER_TYPE)) {
            rows.add(row(i, deserialize(i.getDetail())));
        }
        return rows;
    }

    /**
     * Очередь исполнителя: все заявки платформы, новые сверху. Только для роли
     * SERVICE_OPERATOR. Interaction не тенант-скоуплен, поэтому заявки граждан из
     * публичного тенанта видны исполнителю из тенанта его УК — это и есть смысл очереди;
     * персональные данные в строке ограничены тем, что нужно для выезда (имя, адрес).
     */
    public List<Map<String, Object>> queue(Identity operator) {
        requireOperator(operator);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Interaction i : interactionRepository.findByInteractionTypeOrderByCreatedAtDesc(ORDER_TYPE)) {
            Map<String, Object> m = row(i, deserialize(i.getDetail()));
            m.put("customerName", displayName(i.getIdentityUid()));
            m.put("assigneeMe", operator.getIdentityUid().equals(i.getTargetIdentityUid()));
            rows.add(m);
        }
        return rows;
    }

    /** Исполнитель принимает заявку в работу: вторая сторона фиксируется в target_identity_uid. */
    @Transactional
    public Map<String, Object> accept(Identity operator, UUID orderUid) {
        requireOperator(operator);
        Interaction order = load(orderUid);
        if (order.getStatus() == InteractionStatus.CONFIRMED) {
            throw new IllegalStateException("Заявка уже завершена.");
        }
        if (order.getStatus() == InteractionStatus.REJECTED) {
            throw new IllegalStateException("Заявка отклонена и не может быть принята.");
        }
        if (order.getTargetIdentityUid() != null && !order.getTargetIdentityUid().equals(operator.getIdentityUid())) {
            throw new IllegalStateException("Заявку уже принял другой исполнитель.");
        }
        Map<String, Object> payload = deserialize(order.getDetail());
        if (STAGE_DONE.equals(payload.get(STAGE_KEY))) {
            throw new IllegalStateException("Работа по заявке уже выполнена — ожидается подтверждение заказчика.");
        }
        order.setTargetIdentityUid(operator.getIdentityUid());
        interactionRepository.save(order);

        String label = String.valueOf(payload.getOrDefault("label", order.getObjectUid()));
        auditService.record(operator.getIdentityUid(), order.getObjectUid(), HistoryEventType.OBJECT_MODIFIED,
                "Заявка «" + label + "» принята в работу исполнителем.");
        eventService.record(EventType.SERVICE_STARTED, operator.getIdentityUid(), order.getObjectUid(),
                order.getInteractionUid(), "Исполнитель принял заявку");
        notificationService.notify(order.getIdentityUid(), "SERVICE",
                "Исполнитель " + displayName(operator.getIdentityUid()) + " принял заявку «" + label + "». Статус: в работе.");
        return row(order, payload);
    }

    /** Исполнитель завершил работу: stage=DONE в detail, заявка ждёт подтверждения заказчика. */
    @Transactional
    public Map<String, Object> finish(Identity operator, UUID orderUid) {
        requireOperator(operator);
        Interaction order = load(orderUid);
        if (order.getStatus() != InteractionStatus.PENDING) {
            throw new IllegalStateException("Заявка уже закрыта.");
        }
        if (!operator.getIdentityUid().equals(order.getTargetIdentityUid())) {
            throw new AccessDeniedException("Завершить работу может только исполнитель, принявший заявку.");
        }
        Map<String, Object> payload = deserialize(order.getDetail());
        payload.put(STAGE_KEY, STAGE_DONE);
        order.setDetail(serialize(payload));
        interactionRepository.save(order);

        String label = String.valueOf(payload.getOrDefault("label", order.getObjectUid()));
        auditService.record(operator.getIdentityUid(), order.getObjectUid(), HistoryEventType.OBJECT_MODIFIED,
                "Исполнитель завершил работу по заявке «" + label + "».");
        eventService.record(EventType.SERVICE_COMPLETED, operator.getIdentityUid(), order.getObjectUid(),
                order.getInteractionUid(), "Работа выполнена, ждёт подтверждения заказчика");
        notificationService.notify(order.getIdentityUid(), "SERVICE",
                "Исполнитель завершил услугу «" + label + "». Подтвердите выполнение в разделе «Услуги и быт».");
        return row(order, payload);
    }

    /** Подтверждение выполнения заказчиком (владельцем заявки) — финальный шаг конвейера. */
    @Transactional
    public Map<String, Object> complete(Identity identity, UUID orderUid) {
        Interaction order = load(orderUid);
        if (!identity.getIdentityUid().equals(order.getIdentityUid())) {
            throw new AccessDeniedException("Эта заявка вам не принадлежит.");
        }
        if (order.getStatus() == InteractionStatus.CONFIRMED) {
            throw new IllegalStateException("Заявка уже выполнена.");
        }
        Map<String, Object> payload = deserialize(order.getDetail());
        payload.put("completedAt", LocalDateTime.now().format(TS));
        order.setDetail(serialize(payload));
        order.setStatus(InteractionStatus.CONFIRMED);
        interactionRepository.save(order);

        String label = String.valueOf(payload.getOrDefault("label", "—"));
        auditService.record(identity.getIdentityUid(), order.getObjectUid(), HistoryEventType.OBJECT_MODIFIED,
                "Услуга «" + label + "» выполнена.");
        eventService.record(EventType.SERVICE_COMPLETED, identity.getIdentityUid(), order.getObjectUid(),
                order.getInteractionUid(), "Заказчик подтвердил выполнение");
        notificationService.notify(identity.getIdentityUid(), "SERVICE",
                "Услуга «" + label + "» отмечена выполненной. Спасибо за оценку!");
        if (order.getTargetIdentityUid() != null) {
            notificationService.notify(order.getTargetIdentityUid(), "SERVICE",
                    "Заказчик подтвердил выполнение услуги «" + label + "».");
        }
        return row(order, payload);
    }

    // ------------------------------------------------------------------

    private Interaction load(UUID orderUid) {
        Interaction order = interactionRepository.findById(orderUid)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена."));
        if (!ORDER_TYPE.equals(order.getInteractionType())) {
            throw new IllegalArgumentException("Указанная запись не является заявкой на услугу.");
        }
        return order;
    }

    private void requireOperator(Identity identity) {
        if (identity == null || identity.getRoles() == null
                || !identity.getRoles().contains(RoleType.SERVICE_OPERATOR)) {
            throw new AccessDeniedException("Очередь заявок доступна только исполнителям (роль SERVICE_OPERATOR).");
        }
    }

    /** Кросс-тенантное имя (native-запрос, как в HistoryController): исполнитель и заказчик живут в разных тенантах. */
    private String displayName(UUID identityUid) {
        if (identityUid == null) {
            return "Пользователь";
        }
        return userRepository.findDisplayNameByIdentityUid(identityUid)
                .filter(s -> !s.isBlank())
                .orElse("Пользователь");
    }

    private String addressOf(Identity identity) {
        return citizenDossierService.find(CitizenDossierService.legalUidFor(identity.getIdentityUid()))
                .map(citizenDossierService::payload)
                .map(p -> p.get("address"))
                .map(Object::toString)
                .orElse("Адрес уточнит оператор");
    }

    private Map<String, Object> row(Interaction order, Map<String, Object> payload) {
        Map<String, Object> m = new LinkedHashMap<>(payload);
        m.remove(STAGE_KEY); // стадия уже свёрнута в status — не дублируем внутренности
        m.put("orderUid", order.getInteractionUid().toString());
        m.put("status", uiStatus(order, payload));
        if (order.getTargetIdentityUid() != null) {
            m.put("assigneeName", displayName(order.getTargetIdentityUid()));
        }
        return m;
    }

    /** UI-статус из пары (статусная колонка, stage в detail) — см. javadoc класса. */
    private String uiStatus(Interaction order, Map<String, Object> payload) {
        if (order.getStatus() == InteractionStatus.REJECTED) {
            return "DECLINED";
        }
        if (order.getStatus() == InteractionStatus.CONFIRMED) {
            return "COMPLETED";
        }
        if (STAGE_DONE.equals(payload.get(STAGE_KEY))) {
            return "DONE";
        }
        return order.getTargetIdentityUid() != null ? "ACCEPTED" : "NEW";
    }

    private String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    /**
     * detail — varchar(400). Вместо слепого среза (он ломает JSON и терял бы весь payload)
     * жертвуем необязательными полями по одному, пока строка не поместится. При текущем
     * каталоге и клиппинге адреса/комментария ветка деградации недостижима — это страховка
     * для старых строк, записанных до ограничения длины.
     */
    private String serialize(Map<String, Object> payload) {
        Map<String, Object> p = new LinkedHashMap<>(payload);
        String json = toJson(p);
        String[] expendable = {"note", "icon", "eta", "price", "address"};
        for (int i = 0; json.length() > 400 && i < expendable.length; i++) {
            p.remove(expendable[i]);
            json = toJson(p);
        }
        return json.length() > 400 ? "{}" : json;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
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
