package com.ideaqr.gateway.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideaqr.gateway.domain.Decision;
import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.Interaction;
import com.ideaqr.gateway.domain.OrganizationMembership;
import com.ideaqr.gateway.domain.RequestRecord;
import com.ideaqr.gateway.domain.enums.DecisionOutcome;
import com.ideaqr.gateway.domain.enums.EventType;
import com.ideaqr.gateway.domain.enums.HistoryEventType;
import com.ideaqr.gateway.domain.enums.InteractionStatus;
import com.ideaqr.gateway.domain.enums.RequestStatus;
import com.ideaqr.gateway.domain.enums.RequestType;
import com.ideaqr.gateway.domain.enums.RoleType;
import com.ideaqr.gateway.domain.enums.SessionMode;
import com.ideaqr.gateway.repository.DecisionRepository;
import com.ideaqr.gateway.repository.IdentityRepository;
import com.ideaqr.gateway.repository.InteractionRepository;
import com.ideaqr.gateway.repository.OrganizationMembershipRepository;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Модуль «УСЛУГИ И БЫТ» — <b>трёхсторонний</b> флоу (заказчик × оператор × исполнитель).
 * Заявка проходит ТОТ ЖЕ управляемый конвейер, что и любое действие платформы:
 * Request(SERVICE_ORDER) → Decision → Interaction → History → Event. Никакой отдельной
 * таблицы: заявка — это governed Interaction с JSON-payload в detail (тот же приём, что
 * у рецептов), поэтому схема не меняется, а неизменяемый след полный.
 *
 * <p><b>Диспетчерская модель (ТЗ заказчика).</b> Три личности кодируются существующими
 * колонками: {@code identity_uid} — заказчик, {@code target_identity_uid} — назначенный
 * исполнитель (роль {@link RoleType#EXECUTOR}); оператор ({@link RoleType#SERVICE_OPERATOR})
 * действует как диспетчер и остаётся актором записей журнала о назначении. Тонкая стадия —
 * ключ {@code stage} в JSON {@code detail}; статусная колонка не выходит за CHECK V1
 * (PENDING/CONFIRMED/REJECTED). Жизненный цикл в терминах UI:</p>
 *
 * <pre>
 * NEW        (PENDING, target∅)                — ждёт оператора
 * ASSIGNED   (PENDING, target=исполнитель,
 *             stage=ASSIGNED)                  — оператор назначил; заказчику пришла карточка исполнителя
 * IN_PROGRESS(PENDING, stage=IN_PROGRESS)      — заказчик отсканировал QR исполнителя у двери и
 *                                                подтвердил приход (личность сверена)
 * COMPLETED  (CONFIRMED)                       — заказчик вторым сканом «подтвердил и оплатил»
 * DECLINED   (REJECTED)                        — отклонена
 * </pre>
 *
 * <p>Оба перехода ASSIGNED→IN_PROGRESS и IN_PROGRESS→COMPLETED инициируются СКАНОМ личного
 * QR исполнителя заказчиком (см. {@code GatewayService.scanIdentityProfile} → SERVICE_VISIT):
 * скан — это сверка личности, подтверждение — явное действие. Сервер при подтверждении
 * повторно сверяет отсканированный uid с назначенным исполнителем.</p>
 */
@Service
@RequiredArgsConstructor
public class ServiceOrderService {

    /** Тип interaction-строки заявки (free-text колонка — без миграции). */
    public static final String ORDER_TYPE = "SERVICE_ORDER";
    /** Ключ стадии в detail-JSON (см. javadoc класса). */
    static final String STAGE_KEY = "stage";
    static final String STAGE_ASSIGNED = "ASSIGNED";
    static final String STAGE_IN_PROGRESS = "IN_PROGRESS";
    /** Легаси-стадия двустороннего флоу («работа выполнена») — читается как IN_PROGRESS. */
    static final String STAGE_LEGACY_DONE = "DONE";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter TS_SHORT = DateTimeFormatter.ofPattern("HH:mm");

    /** Каталог демо-услуг ЖКХ (mock: карточки услуг фиксированы, исполнители — живые). */
    public record CatalogItem(String key, String icon, String label, String description,
                              String operator, String price, String eta) {}

    public static final List<CatalogItem> CATALOG = List.of(
            new CatalogItem("TRASH_PICKUP", "🗑", "Вывоз мусора от двери",
                    "Заберём бытовые отходы от двери квартиры в течение часа.",
                    "УК «Comfort Service»", "500 ₸ / заявка", "до 60 минут"),
            new CatalogItem("PLUMBER", "🔧", "Вызов сантехника",
                    "Протечки, засоры, замена смесителей и арматуры.",
                    "УК «Comfort Service»", "от 4 000 ₸", "сегодня 14:00–18:00"),
            new CatalogItem("ELECTRICIAN", "⚡", "Вызов электрика",
                    "Розетки, автоматы, освещение — диагностика и ремонт.",
                    "УК «Comfort Service»", "от 3 500 ₸", "завтра 09:00–12:00"),
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
    private final IdentityRepository identityRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final SessionService sessionService;
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
                .reason("Заявка принята. Оператор " + item.operator() + " назначит исполнителя.")
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
        // никогда не резался даже после дописывания stage/таймстемпов (см. serialize()).
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
                "Заявка «" + item.label() + "» принята. Оператор назначит исполнителя.");

        return row(order, payload);
    }

    /** Мои заявки, новые сверху (с карточкой назначенного исполнителя). */
    public List<Map<String, Object>> mine(Identity identity) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Interaction i : interactionRepository
                .findByIdentityUidAndInteractionTypeOrderByCreatedAtDesc(identity.getIdentityUid(), ORDER_TYPE)) {
            rows.add(row(i, deserialize(i.getDetail())));
        }
        return rows;
    }

    /**
     * Диспетчерская оператора: все заявки платформы, новые сверху. Только для роли
     * SERVICE_OPERATOR. Interaction не тенант-скоуплен, поэтому заявки граждан из
     * публичного тенанта видны оператору из тенанта его УК — это и есть смысл очереди;
     * персональные данные в строке ограничены тем, что нужно для назначения (имя, адрес).
     */
    public List<Map<String, Object>> queue(Identity operator) {
        requireRole(operator, RoleType.SERVICE_OPERATOR, "Диспетчерская доступна только операторам услуг.");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Interaction i : interactionRepository.findByInteractionTypeOrderByCreatedAtDesc(ORDER_TYPE)) {
            Map<String, Object> m = row(i, deserialize(i.getDetail()));
            m.put("customerName", displayName(i.getIdentityUid()));
            rows.add(m);
        }
        return rows;
    }

    /** «Мои наряды» исполнителя: заявки, назначенные на него, новые сверху. */
    public List<Map<String, Object>> assigned(Identity executor) {
        requireRole(executor, RoleType.EXECUTOR, "Наряды доступны только исполнителям.");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Interaction i : interactionRepository
                .findByTargetIdentityUidAndInteractionTypeOrderByCreatedAtDesc(executor.getIdentityUid(), ORDER_TYPE)) {
            Map<String, Object> m = row(i, deserialize(i.getDetail()));
            m.put("customerName", displayName(i.getIdentityUid()));
            rows.add(m);
        }
        return rows;
    }

    /**
     * Исполнители, доступные оператору для назначения: ACTIVE-штат его организации с
     * work-ролью EXECUTOR. Имена — кросс-тенантным native-запросом (диспетчер УК и
     * исполнитель могут жить в разных тенантах от гражданина-заказчика).
     */
    public List<Map<String, Object>> executors(Identity operator) {
        requireRole(operator, RoleType.SERVICE_OPERATOR, "Список исполнителей доступен только операторам услуг.");
        var org = organizationService.resolveActingOrganization(operator.getIdentityUid());
        List<Map<String, Object>> rows = new ArrayList<>();
        if (org == null) {
            return rows;
        }
        for (OrganizationMembership m : membershipRepository.findByOrganizationUid(org.getOrganizationUid())) {
            boolean active = m.getStatus() == null || "ACTIVE".equalsIgnoreCase(m.getStatus());
            if (!active || !"EXECUTOR".equalsIgnoreCase(m.getWorkRole())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("identityUid", m.getIdentityUid().toString());
            row.put("name", displayName(m.getIdentityUid()));
            row.put("organization", org.getName());
            rows.add(row);
        }
        return rows;
    }

    /**
     * Оператор назначает исполнителя (сердце диспетчерской модели). Назначение — это
     * профессиональное действие: роль SERVICE_OPERATOR + рабочий режим. Заявка получает
     * вторую сторону ({@code target_identity_uid}) и стадию ASSIGNED; заказчику в тот же
     * момент уходит карточка исполнителя (в заявке и уведомлением), исполнителю — наряд.
     */
    @Transactional
    public Map<String, Object> assign(Identity operator, UUID orderUid, String executorRef) {
        requireRole(operator, RoleType.SERVICE_OPERATOR, "Назначать исполнителей может только оператор услуг.");
        if (sessionService.current(operator.getIdentityUid()).getMode() != SessionMode.WORKING) {
            throw new AccessDeniedException("Назначение исполнителя доступно только в рабочем режиме.");
        }
        Interaction order = load(orderUid);
        if (order.getStatus() == InteractionStatus.CONFIRMED) {
            throw new IllegalStateException("Заявка уже завершена.");
        }
        if (order.getStatus() == InteractionStatus.REJECTED) {
            throw new IllegalStateException("Заявка отклонена.");
        }
        if (order.getTargetIdentityUid() != null) {
            throw new IllegalStateException("Исполнитель уже назначен на эту заявку.");
        }

        Identity executor = resolveExecutor(executorRef);
        if (executor.getIdentityUid().equals(order.getIdentityUid())) {
            throw new IllegalArgumentException("Исполнитель не может быть заказчиком этой же заявки.");
        }

        Map<String, Object> payload = deserialize(order.getDetail());
        payload.put(STAGE_KEY, STAGE_ASSIGNED);
        payload.put("assignedAt", LocalDateTime.now().format(TS_SHORT));
        order.setTargetIdentityUid(executor.getIdentityUid());
        order.setDetail(serialize(payload));
        interactionRepository.save(order);

        String label = String.valueOf(payload.getOrDefault("label", order.getObjectUid()));
        String executorName = displayName(executor.getIdentityUid());
        auditService.record(operator.getIdentityUid(), order.getObjectUid(), HistoryEventType.OBJECT_MODIFIED,
                "Оператор назначил исполнителя " + executorName + " на заявку «" + label + "».");
        eventService.record(EventType.ASSIGNMENT_CREATED, operator.getIdentityUid(), order.getObjectUid(),
                order.getInteractionUid(), "Назначен исполнитель: " + executorName);
        notificationService.notify(order.getIdentityUid(), "SERVICE",
                "По заявке «" + label + "» назначен исполнитель: " + executorName
                        + ". Его карточка — в вашей заявке. Когда он придёт, отсканируйте его QR и подтвердите приход.");
        notificationService.notify(executor.getIdentityUid(), "SERVICE",
                "Вам назначен наряд: «" + label + "». Адрес — в разделе «Мои наряды».");
        return row(order, payload);
    }

    /**
     * Заказчик подтверждает ПРИХОД исполнителя после сверки личности сканом его QR.
     * {@code scannedExecutorUid} — личность, которую заказчик реально отсканировал у двери:
     * сервер сверяет её с назначенным исполнителем, чужой QR отклоняется.
     */
    @Transactional
    public Map<String, Object> confirmArrival(Identity customer, UUID orderUid, UUID scannedExecutorUid) {
        Interaction order = requireOwnOpenOrder(customer, orderUid);
        requireScannedMatch(order, scannedExecutorUid);
        Map<String, Object> payload = deserialize(order.getDetail());
        if (!STAGE_ASSIGNED.equals(payload.get(STAGE_KEY))) {
            throw new IllegalStateException(order.getTargetIdentityUid() == null
                    ? "Исполнитель ещё не назначен оператором."
                    : "Приход уже подтверждён — заявка в работе.");
        }
        payload.put(STAGE_KEY, STAGE_IN_PROGRESS);
        payload.put("arrivedAt", LocalDateTime.now().format(TS_SHORT));
        order.setDetail(serialize(payload));
        interactionRepository.save(order);

        String label = String.valueOf(payload.getOrDefault("label", order.getObjectUid()));
        auditService.record(customer.getIdentityUid(), order.getObjectUid(), HistoryEventType.OBJECT_MODIFIED,
                "Заказчик сверил личность по QR и подтвердил приход исполнителя по заявке «" + label + "».");
        eventService.record(EventType.SERVICE_STARTED, customer.getIdentityUid(), order.getObjectUid(),
                order.getInteractionUid(), "Приход исполнителя подтверждён — работа началась");
        notificationService.notify(order.getTargetIdentityUid(), "SERVICE",
                "Заказчик подтвердил ваш приход по заявке «" + label + "». Статус: в работе.");
        return row(order, payload);
    }

    /**
     * Финал: заказчик вторым сканом QR исполнителя «подтверждает и оплачивает» (демо-платёж).
     * Требует, чтобы приход был подтверждён (stage IN_PROGRESS; легаси-DONE принимается).
     */
    @Transactional
    public Map<String, Object> completeAndPay(Identity customer, UUID orderUid, UUID scannedExecutorUid) {
        Interaction order = requireOwnOpenOrder(customer, orderUid);
        requireScannedMatch(order, scannedExecutorUid);
        Map<String, Object> payload = deserialize(order.getDetail());
        Object stage = payload.get(STAGE_KEY);
        if (!STAGE_IN_PROGRESS.equals(stage) && !STAGE_LEGACY_DONE.equals(stage)) {
            throw new IllegalStateException("Сначала подтвердите приход исполнителя (первый скан его QR).");
        }
        payload.put("paidAt", LocalDateTime.now().format(TS_SHORT));
        payload.put("completedAt", LocalDateTime.now().format(TS));
        order.setDetail(serialize(payload));
        order.setStatus(InteractionStatus.CONFIRMED);
        interactionRepository.save(order);

        String label = String.valueOf(payload.getOrDefault("label", "—"));
        String amount = String.valueOf(payload.getOrDefault("price", "по тарифу"));
        auditService.record(customer.getIdentityUid(), order.getObjectUid(), HistoryEventType.OBJECT_MODIFIED,
                "Услуга «" + label + "» выполнена: заказчик подтвердил работу и оплатил (" + amount + ", демо-платёж).");
        eventService.record(EventType.SERVICE_COMPLETED, customer.getIdentityUid(), order.getObjectUid(),
                order.getInteractionUid(), "Заказчик подтвердил выполнение и оплату");
        notificationService.notify(customer.getIdentityUid(), "SERVICE",
                "Услуга «" + label + "» завершена и оплачена (" + amount + "). Спасибо!");
        if (order.getTargetIdentityUid() != null) {
            notificationService.notify(order.getTargetIdentityUid(), "SERVICE",
                    "Заказчик подтвердил выполнение и оплатил услугу «" + label + "». Наряд закрыт.");
        }
        return row(order, payload);
    }

    // ------------------------------------------------------------------
    //  Контекст для сканов (GatewayService → SERVICE_VISIT)
    // ------------------------------------------------------------------

    /**
     * Активная заявка сканирующего, назначенная на отсканированную личность: связка
     * «заказчик ↔ исполнитель у двери». Именно она (а не роль сканирующего) включает
     * представление SERVICE_VISIT и легитимирует кросс-тенантный резолв личного QR
     * исполнителя. Берётся самая свежая открытая заявка со стадией ASSIGNED/IN_PROGRESS.
     */
    public Interaction activeVisitOrder(UUID customerUid, UUID executorUid) {
        for (Interaction i : interactionRepository
                .findByIdentityUidAndInteractionTypeOrderByCreatedAtDesc(customerUid, ORDER_TYPE)) {
            if (i.getStatus() != InteractionStatus.PENDING
                    || !executorUid.equals(i.getTargetIdentityUid())) {
                continue;
            }
            Object stage = deserialize(i.getDetail()).get(STAGE_KEY);
            if (STAGE_ASSIGNED.equals(stage) || STAGE_IN_PROGRESS.equals(stage) || STAGE_LEGACY_DONE.equals(stage)) {
                return i;
            }
        }
        return null;
    }

    /** Срез заявки для карточки SERVICE_VISIT: статус + доступное заказчику действие. */
    public Map<String, Object> visitPayload(Interaction order) {
        Map<String, Object> payload = deserialize(order.getDetail());
        Map<String, Object> m = row(order, payload);
        Object stage = payload.get(STAGE_KEY);
        m.put("action", STAGE_ASSIGNED.equals(stage) ? "CONFIRM_ARRIVAL" : "CONFIRM_COMPLETE");
        return m;
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

    private Interaction requireOwnOpenOrder(Identity customer, UUID orderUid) {
        Interaction order = load(orderUid);
        if (!customer.getIdentityUid().equals(order.getIdentityUid())) {
            throw new AccessDeniedException("Эта заявка вам не принадлежит.");
        }
        if (order.getStatus() == InteractionStatus.CONFIRMED) {
            throw new IllegalStateException("Заявка уже завершена.");
        }
        if (order.getStatus() == InteractionStatus.REJECTED) {
            throw new IllegalStateException("Заявка отклонена.");
        }
        return order;
    }

    /** Сверка личности: подтвердить можно только того исполнителя, чей QR отсканирован. */
    private void requireScannedMatch(Interaction order, UUID scannedExecutorUid) {
        if (order.getTargetIdentityUid() == null) {
            throw new IllegalStateException("Исполнитель ещё не назначен оператором.");
        }
        if (scannedExecutorUid != null && !scannedExecutorUid.equals(order.getTargetIdentityUid())) {
            throw new AccessDeniedException(
                    "Отсканированный QR не совпадает с назначенным исполнителем. Подтверждение отклонено.");
        }
    }

    private void requireRole(Identity identity, RoleType role, String message) {
        if (identity == null || identity.getRoles() == null || !identity.getRoles().contains(role)) {
            throw new AccessDeniedException(message);
        }
    }

    /**
     * Исполнитель по username или UID личности (значение личного QR). {@code findById} —
     * это {@code em.find}, tenant-@Filter на него не действует: исполнитель из тенанта УК
     * резолвится оператору намеренно. Назначить можно только личность с ролью EXECUTOR.
     */
    private Identity resolveExecutor(String ref) {
        String v = ref == null ? "" : ref.trim();
        if (v.toUpperCase(Locale.ROOT).startsWith("IDENTITY:")) {
            v = v.substring("IDENTITY:".length()).trim();
        }
        if (v.isEmpty()) {
            throw new IllegalArgumentException("Укажите исполнителя: имя пользователя или UID личности.");
        }
        final String ref0 = v;
        UUID uid;
        try {
            uid = UUID.fromString(ref0);
        } catch (IllegalArgumentException notUuid) {
            uid = userRepository.findIdentityUidByUsernameAnyTenant(ref0)
                    .map(UUID::fromString)
                    .orElseThrow(() -> new IllegalArgumentException("Исполнитель «" + ref0 + "» не найден."));
        }
        Identity executor = identityRepository.findById(uid)
                .orElseThrow(() -> new IllegalArgumentException("Личность исполнителя не найдена."));
        if (executor.getRoles() == null || !executor.getRoles().contains(RoleType.EXECUTOR)) {
            throw new IllegalArgumentException("У выбранного сотрудника нет роли исполнителя (EXECUTOR).");
        }
        return executor;
    }

    /** Кросс-тенантное имя (native-запрос, как в HistoryController): стороны заявки живут в разных тенантах. */
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
            m.put("executorUid", order.getTargetIdentityUid().toString());
            m.put("assigneeName", displayName(order.getTargetIdentityUid()));
            // Карточка исполнителя для заказчика: организация + телефон из публичной визитки.
            var org = organizationService.resolveActingOrganization(order.getTargetIdentityUid());
            if (org != null) {
                m.put("assigneeOrg", org.getName());
            }
            citizenDossierService.find(CitizenDossierService.vcardUidFor(order.getTargetIdentityUid()))
                    .map(citizenDossierService::payload)
                    .ifPresent(v -> {
                        if (v.get("phone") != null) m.put("assigneePhone", v.get("phone"));
                    });
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
        Object stage = payload.get(STAGE_KEY);
        if (STAGE_IN_PROGRESS.equals(stage) || STAGE_LEGACY_DONE.equals(stage)) {
            return "IN_PROGRESS";
        }
        return order.getTargetIdentityUid() != null ? "ASSIGNED" : "NEW";
    }

    private String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    /**
     * detail — varchar(400). Вместо слепого среза (он ломает JSON и терял бы весь payload)
     * жертвуем необязательными полями по одному, пока строка не поместится: сперва
     * стадийные таймстемпы, затем декоративные поля. Семантика (service, label, stage,
     * orderedAt) не приносится в жертву никогда.
     */
    private String serialize(Map<String, Object> payload) {
        Map<String, Object> p = new LinkedHashMap<>(payload);
        String json = toJson(p);
        String[] expendable = {"assignedAt", "arrivedAt", "note", "icon", "eta", "address", "price"};
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
