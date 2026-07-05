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
import com.ideaqr.gateway.repository.DecisionRepository;
import com.ideaqr.gateway.repository.InteractionRepository;
import com.ideaqr.gateway.repository.RequestRepository;
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
 */
@Service
@RequiredArgsConstructor
public class ServiceOrderService {

    /** Тип interaction-строки заявки (free-text колонка — без миграции). */
    public static final String ORDER_TYPE = "SERVICE_ORDER";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** Каталог демо-услуг ЖКХ (mock: за услугами нет реального оператора). */
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
                .reason("Заявка принята. Оператор назначен: " + item.operator() + ".")
                .riskLevel("LOW")
                .build());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("service", item.key());
        payload.put("label", item.label());
        payload.put("icon", item.icon());
        payload.put("operator", item.operator());
        payload.put("price", item.price());
        payload.put("eta", item.eta());
        payload.put("address", addressOf(identity));
        if (note != null && !note.isBlank()) {
            String trimmed = note.trim();
            // detail-колонка — varchar(400): комментарий ограничиваем, чтобы JSON никогда не резался.
            payload.put("note", trimmed.length() > 80 ? trimmed.substring(0, 80) : trimmed);
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
                "Заявка «" + item.label() + "» принята. Оператор: " + item.operator() + ", срок: " + item.eta() + ".");

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

    /** Демо-закрытие заявки её владельцем («оператор выполнил работу»). */
    @Transactional
    public Map<String, Object> complete(Identity identity, UUID orderUid) {
        Interaction order = interactionRepository.findById(orderUid)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена."));
        if (!ORDER_TYPE.equals(order.getInteractionType())) {
            throw new IllegalArgumentException("Указанная запись не является заявкой на услугу.");
        }
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

        auditService.record(identity.getIdentityUid(), order.getObjectUid(), HistoryEventType.OBJECT_MODIFIED,
                "Услуга «" + payload.getOrDefault("label", "—") + "» выполнена.");
        eventService.record(EventType.SERVICE_COMPLETED, identity.getIdentityUid(), order.getObjectUid(),
                order.getInteractionUid(), "Услуга выполнена");
        notificationService.notify(identity.getIdentityUid(), "SERVICE",
                "Услуга «" + payload.getOrDefault("label", "—") + "» отмечена выполненной. Спасибо за оценку!");
        return row(order, payload);
    }

    // ------------------------------------------------------------------

    private String addressOf(Identity identity) {
        return citizenDossierService.find(CitizenDossierService.legalUidFor(identity.getIdentityUid()))
                .map(citizenDossierService::payload)
                .map(p -> p.get("address"))
                .map(Object::toString)
                .orElse("Адрес уточнит оператор");
    }

    private Map<String, Object> row(Interaction order, Map<String, Object> payload) {
        Map<String, Object> m = new LinkedHashMap<>(payload);
        m.put("orderUid", order.getInteractionUid().toString());
        m.put("status", order.getStatus() == InteractionStatus.CONFIRMED ? "COMPLETED" : "IN_PROGRESS");
        return m;
    }

    private String serialize(Map<String, Object> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            // detail is varchar(400); the payload is compact by construction.
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
