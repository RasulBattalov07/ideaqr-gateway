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
import com.ideaqr.gateway.domain.enums.IdentityType;
import com.ideaqr.gateway.domain.enums.InteractionStatus;
import com.ideaqr.gateway.domain.enums.ObjectCategory;
import com.ideaqr.gateway.domain.enums.RequestStatus;
import com.ideaqr.gateway.domain.enums.RequestType;
import com.ideaqr.gateway.domain.enums.SessionMode;
import com.ideaqr.gateway.repository.DecisionRepository;
import com.ideaqr.gateway.repository.IdentityRepository;
import com.ideaqr.gateway.repository.InteractionRepository;
import com.ideaqr.gateway.repository.RequestRepository;
import com.ideaqr.gateway.repository.UserRepository;
import com.ideaqr.gateway.tenant.TenantContext;
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
 * СЦЕНАРИЙ «БИЗНЕС И МАГАЗИНЫ» — розничный чек-аут (покупатель × кассир).
 *
 * <p>Линия чека — это governed {@link Interaction} типа {@code RETAIL_PURCHASE} под
 * {@code RequestRecord(PURCHASE)} (тот же приём без новых таблиц, что и заявки услуг):
 * {@code identity_uid} — покупатель, {@code object_uid} — товар, {@code target_identity_uid}
 * — кассир, выдавший товар (вторая сторона, заполняется при выдаче). Тонкая стадия — ключ
 * {@code stage} в JSON {@code detail}; статусная колонка не выходит за CHECK V1:</p>
 *
 * <pre>
 * CART    (PENDING)   — «добавить в корзину»; клиент может удалить (REJECTED/REMOVED)
 * PAID    (PENDING)   — «оплатить на месте» сразу, либо кассир «принял оплату» корзины:
 *                       ОПЛАЧЕН, НО НЕ ВЫДАН — ровно то, что кассир видит по QR клиента
 * ISSUED  (CONFIRMED) — кассир выдал товар: право владения передаётся покупателю через
 *                       {@link ObjectLifecycleService#transfer} (OBJECT_TRANSFERRED,
 *                       QR объекта НЕ меняется), вещь появляется в «Мои объекты»
 * </pre>
 *
 * <p>Кассовые операции (принять оплату / выдать) охраняются {@link ValidationService#retailCheckout}:
 * роль CASHIER + доверие + рабочий режим + рабочее время. Товар магазина — обычный
 * реестровый объект публичного тенанта с {@code forSale:true} и ценой в payload; владелец —
 * кассир (склад магазина). Оплата — демо-платёж, без реального биллинга.</p>
 */
@Service
@RequiredArgsConstructor
public class RetailCheckoutService {

    /** Тип interaction-строки линии чека (free-text колонка — без миграции). */
    public static final String PURCHASE_TYPE = "RETAIL_PURCHASE";
    static final String STAGE_KEY = "stage";
    static final String STAGE_CART = "CART";
    static final String STAGE_PAID = "PAID";
    static final String STAGE_ISSUED = "ISSUED";
    static final String STAGE_REMOVED = "REMOVED";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter TS_SHORT = DateTimeFormatter.ofPattern("HH:mm");

    private final RequestRepository requestRepository;
    private final DecisionRepository decisionRepository;
    private final InteractionRepository interactionRepository;
    private final AuditService auditService;
    private final EventService eventService;
    private final NotificationService notificationService;
    private final OrganizationService organizationService;
    private final RegistryClient registryClient;
    private final ObjectLifecycleService objectLifecycleService;
    private final ValidationService validationService;
    private final SessionService sessionService;
    private final DevTimeService devTimeService;
    private final IdentityRepository identityRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------------
    //  Покупатель
    // ------------------------------------------------------------------

    /** «Добавить в корзину»: линия CART — оплата будет принята кассиром у кассы. */
    @Transactional
    public Map<String, Object> addToCart(Identity buyer, String objectUid) {
        return createLine(buyer, objectUid, false);
    }

    /** «Оплатить на месте»: демо-платёж сразу — линия PAID («оплачен, но не выдан»). */
    @Transactional
    public Map<String, Object> buyNow(Identity buyer, String objectUid) {
        return createLine(buyer, objectUid, true);
    }

    private Map<String, Object> createLine(Identity buyer, String objectUid, boolean instant) {
        if (buyer.getIdentityType() == IdentityType.GUEST) {
            throw new AccessDeniedException("Покупки доступны только зарегистрированным пользователям.");
        }
        RegistryClient.Resolved resolved = registryClient.resolve(
                objectUid == null ? "" : objectUid.trim());
        UUID ownerUid = resolved.dbObject() != null ? resolved.dbObject().getOwnerIdentityUid() : null;
        if (!purchasable(resolved, ownerUid, buyer.getIdentityUid())) {
            throw new IllegalArgumentException(
                    "Этот объект не продаётся через кассу (нет цены или это не товар магазина).");
        }
        String uid = resolved.dbObject().getObjectUid();
        if (openLineFor(buyer.getIdentityUid(), uid) != null) {
            throw new IllegalStateException("Этот товар уже в вашей корзине или оплачен и ждёт выдачи.");
        }

        var actingOrg = organizationService.resolveActingOrganization(buyer.getIdentityUid());
        RequestRecord req = requestRepository.save(RequestRecord.builder()
                .identityUid(buyer.getIdentityUid())
                .organizationUid(actingOrg != null ? actingOrg.getOrganizationUid() : null)
                .objectUid(uid)
                .requestType(RequestType.PURCHASE)
                .status(RequestStatus.PENDING)
                .build());
        String reason = instant
                ? "Оплата принята (демо-платёж). Товар ждёт выдачи на кассе."
                : "Товар добавлен в корзину. Оплата — на кассе.";
        Decision decision = decisionRepository.save(Decision.builder()
                .requestUid(req.getRequestUid())
                .identityUid(buyer.getIdentityUid())
                .outcome(DecisionOutcome.APPROVED)
                .reasonCode(instant ? "PAYMENT_ACCEPTED" : "CART_ADDED")
                .reason(reason)
                .riskLevel("LOW")
                .build());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", clip(resolved.displayName(), 60));
        Object price = resolved.data() != null ? resolved.data().get("price") : null;
        payload.put("price", price instanceof Number ? price : 0);
        Object currency = resolved.data() != null ? resolved.data().get("currency") : null;
        payload.put("currency", currency != null ? currency.toString() : "₸");
        Object store = resolved.data() != null ? resolved.data().get("store") : null;
        if (store != null) {
            payload.put("store", clip(store.toString(), 40));
        }
        payload.put(STAGE_KEY, instant ? STAGE_PAID : STAGE_CART);
        payload.put("channel", instant ? "INSTANT" : "CART");
        payload.put("addedAt", LocalDateTime.now().format(TS));
        if (instant) {
            payload.put("paidAt", LocalDateTime.now().format(TS_SHORT));
        }

        Interaction line = interactionRepository.save(Interaction.builder()
                .identityUid(buyer.getIdentityUid())
                .requestUid(req.getRequestUid())
                .objectUid(uid)
                .interactionType(PURCHASE_TYPE)
                .status(InteractionStatus.PENDING)
                .detail(serialize(payload))
                .build());

        req.setStatus(RequestStatus.PROCESSED);
        requestRepository.save(req);

        auditService.record(buyer.getIdentityUid(), uid, HistoryEventType.ISSUE_REPORTED,
                (instant ? "Покупка оплачена на месте (демо-платёж): «" : "Товар добавлен в корзину: «")
                        + resolved.displayName() + "».",
                req.getRequestUid(), decision.getDecisionUid(), line.getInteractionUid());
        eventService.record(EventType.INTERACTION_CREATED, buyer.getIdentityUid(), uid,
                line.getInteractionUid(), instant ? "Оплата на месте: " + resolved.displayName()
                        : "В корзину: " + resolved.displayName());
        if (instant) {
            notificationService.notify(buyer.getIdentityUid(), "RETAIL",
                    "Товар «" + resolved.displayName()
                            + "» оплачен (демо). Покажите ваш QR кассиру — он выдаст покупку.");
        }
        return lineRow(line, payload);
    }

    /** Покупатель убирает позицию из корзины (только неоплаченную). */
    @Transactional
    public Map<String, Object> removeFromCart(Identity buyer, UUID lineUid) {
        Interaction line = loadLine(lineUid);
        if (!buyer.getIdentityUid().equals(line.getIdentityUid())) {
            throw new AccessDeniedException("Эта позиция вам не принадлежит.");
        }
        Map<String, Object> payload = deserialize(line.getDetail());
        if (line.getStatus() != InteractionStatus.PENDING || !STAGE_CART.equals(payload.get(STAGE_KEY))) {
            throw new IllegalStateException("Удалить можно только неоплаченную позицию корзины.");
        }
        payload.put(STAGE_KEY, STAGE_REMOVED);
        line.setDetail(serialize(payload));
        line.setStatus(InteractionStatus.REJECTED);
        interactionRepository.save(line);

        auditService.record(buyer.getIdentityUid(), line.getObjectUid(), HistoryEventType.OBJECT_MODIFIED,
                "Позиция «" + payload.getOrDefault("name", line.getObjectUid()) + "» удалена из корзины.");
        return lineRow(line, payload);
    }

    /** Мои покупки: корзина + «оплачено, ждёт выдачи» + недавно полученное, с итогами. */
    public Map<String, Object> mine(Identity buyer) {
        return purchasesOf(buyer.getIdentityUid(), true);
    }

    // ------------------------------------------------------------------
    //  Кассир
    // ------------------------------------------------------------------

    /**
     * Чек клиента для кассира (по скану личного QR и для живого обновления): имя + открытые
     * линии (корзина, «оплачен-не выдан») с итогами. Принцип минимального доступа: история
     * покупок и полученные товары кассиру не раскрываются.
     */
    public Map<String, Object> checkoutView(Identity cashier, UUID buyerUid) {
        requireCashierGates(cashier);
        Map<String, Object> view = purchasesOf(buyerUid, false);
        view.put("customerUid", buyerUid.toString());
        view.put("customerName", displayName(buyerUid));
        return view;
    }

    /**
     * «Принять оплату»: кассир закрывает корзину клиента демо-платежом — все линии CART
     * становятся PAID («оплачен, но не выдан»). Если клиент передумал у кассы («не хватает
     * средств»), он сам удаляет позицию со своего телефона — чек кассира обновится поллингом.
     */
    @Transactional
    public Map<String, Object> collectPayment(Identity cashier, UUID buyerUid) {
        requireCashierGates(cashier);
        long total = 0;
        int count = 0;
        for (Interaction line : interactionRepository
                .findByIdentityUidAndInteractionTypeOrderByCreatedAtDesc(buyerUid, PURCHASE_TYPE)) {
            if (line.getStatus() != InteractionStatus.PENDING) {
                continue;
            }
            Map<String, Object> payload = deserialize(line.getDetail());
            if (!STAGE_CART.equals(payload.get(STAGE_KEY))) {
                continue;
            }
            payload.put(STAGE_KEY, STAGE_PAID);
            payload.put("paidAt", LocalDateTime.now().format(TS_SHORT));
            line.setDetail(serialize(payload));
            interactionRepository.save(line);
            total += priceOf(payload);
            count++;
        }
        if (count == 0) {
            throw new IllegalStateException("В корзине клиента нет неоплаченных позиций.");
        }

        String desc = "Касса: принята оплата корзины клиента " + displayName(buyerUid)
                + " — " + count + " поз. на " + total + " ₸ (демо-платёж).";
        auditService.record(cashier.getIdentityUid(), null, HistoryEventType.OBJECT_MODIFIED, desc);
        // Запись адресована ПОКУПАТЕЛЮ — стемпим её его тенантом (дисциплина subject-addressed
        // строк), иначе tenant-фильтр спрятал бы её из журнала клиента.
        auditService.record(buyerUid, null, HistoryEventType.OBJECT_MODIFIED,
                "Оплата " + count + " поз. на " + total + " ₸ принята на кассе (демо-платёж). Товары ждут выдачи.",
                null, null, null, tenantOf(buyerUid));
        eventService.record(EventType.OBJECT_MODIFIED, cashier.getIdentityUid(), null, null, desc);
        notificationService.notify(buyerUid, "RETAIL",
                "Оплата принята на кассе: " + count + " поз., " + total + " ₸. Кассир выдаст ваши покупки.");
        return checkoutView(cashier, buyerUid);
    }

    /**
     * «Выдать»: финал линии. Кассир отдаёт оплаченный товар — право владения переходит
     * покупателю через стандартный {@link ObjectLifecycleService#transfer} (запись
     * OBJECT_TRANSFERRED, QR объекта не меняется), кассир фиксируется второй стороной
     * линии, покупатель получает уведомление и запись в собственном журнале.
     */
    @Transactional
    public Map<String, Object> issue(Identity cashier, UUID lineUid) {
        requireCashierGates(cashier);
        Interaction line = loadLine(lineUid);
        Map<String, Object> payload = deserialize(line.getDetail());
        if (line.getStatus() != InteractionStatus.PENDING || !STAGE_PAID.equals(payload.get(STAGE_KEY))) {
            throw new IllegalStateException("Выдать можно только оплаченную и ещё не выданную позицию.");
        }
        UUID buyerUid = line.getIdentityUid();

        // Передача владения — тем же конвейером, что продажа авто: OBJECT_TRANSFERRED,
        // неизменный QR, уведомление новому владельцу, объект появляется в «Мои объекты».
        objectLifecycleService.transfer(cashier, line.getObjectUid(), buyerUid,
                "Выдача оплаченного товара на кассе");

        payload.put(STAGE_KEY, STAGE_ISSUED);
        payload.put("issuedAt", LocalDateTime.now().format(TS_SHORT));
        line.setDetail(serialize(payload));
        line.setStatus(InteractionStatus.CONFIRMED);
        line.setTargetIdentityUid(cashier.getIdentityUid());
        interactionRepository.save(line);

        String name = String.valueOf(payload.getOrDefault("name", line.getObjectUid()));
        auditService.record(cashier.getIdentityUid(), line.getObjectUid(), HistoryEventType.OBJECT_MODIFIED,
                "Касса: товар «" + name + "» выдан покупателю " + displayName(buyerUid) + " (оплачен · выдан).");
        auditService.record(buyerUid, line.getObjectUid(), HistoryEventType.OBJECT_TRANSFERRED,
                "Вам выдан оплаченный товар «" + name + "» — вы стали владельцем. QR объекта не изменился.",
                line.getRequestUid(), null, line.getInteractionUid(), tenantOf(buyerUid));
        eventService.record(EventType.SERVICE_COMPLETED, cashier.getIdentityUid(), line.getObjectUid(),
                line.getInteractionUid(), "Выдача товара покупателю завершена");
        notificationService.notify(buyerUid, "RETAIL",
                "Товар «" + name + "» выдан и привязан к вам. Он появился в «Мои объекты».");
        return lineRow(line, payload);
    }

    // ------------------------------------------------------------------
    //  Контекст для сканов (GatewayService)
    // ------------------------------------------------------------------

    /**
     * Коммерческий блок карточки товара для зарегистрированного сканирующего:
     * «Оплатить на месте / В корзину» для товара магазина ({@code forSale:true} + цена,
     * владелец — магазин, не сканирующий), либо текущее состояние его открытой линии.
     * {@code null} — объект через кассу не продаётся (личные вещи, каталог pre-ownership).
     */
    public Map<String, Object> commerceFor(Identity scanner, RegistryClient.Resolved resolved, UUID ownerUid) {
        if (scanner.getIdentityType() == IdentityType.GUEST
                || !purchasable(resolved, ownerUid, scanner.getIdentityUid())) {
            return null;
        }
        Map<String, Object> commerce = new LinkedHashMap<>();
        commerce.put("available", true);
        commerce.put("price", resolved.data().get("price"));
        Object currency = resolved.data().get("currency");
        commerce.put("currency", currency != null ? currency.toString() : "₸");
        Object store = resolved.data().get("store");
        if (store != null) {
            commerce.put("store", store.toString());
        }
        Interaction open = openLineFor(scanner.getIdentityUid(), resolved.dbObject().getObjectUid());
        if (open == null) {
            commerce.put("state", "AVAILABLE");
        } else {
            Map<String, Object> payload = deserialize(open.getDetail());
            commerce.put("state", STAGE_PAID.equals(payload.get(STAGE_KEY)) ? "PAID" : "IN_CART");
            commerce.put("lineUid", open.getInteractionUid().toString());
        }
        return commerce;
    }

    // ------------------------------------------------------------------

    /** Товар магазина: реестровый DB-объект RETAIL с владельцем-магазином, forSale и ценой. */
    private boolean purchasable(RegistryClient.Resolved resolved, UUID ownerUid, UUID scannerUid) {
        if (resolved.dbObject() == null || ownerUid == null || ownerUid.equals(scannerUid)
                || resolved.category() != ObjectCategory.RETAIL || resolved.data() == null) {
            return false;
        }
        Object forSale = resolved.data().get("forSale");
        Object price = resolved.data().get("price");
        return Boolean.TRUE.equals(forSale) && price instanceof Number n && n.longValue() > 0;
    }

    /** Открытая (PENDING, CART/PAID) линия покупателя по товару — дубликаты запрещены. */
    private Interaction openLineFor(UUID buyerUid, String objectUid) {
        for (Interaction i : interactionRepository
                .findByIdentityUidAndInteractionTypeOrderByCreatedAtDesc(buyerUid, PURCHASE_TYPE)) {
            if (i.getStatus() == InteractionStatus.PENDING && objectUid.equals(i.getObjectUid())) {
                return i;
            }
        }
        return null;
    }

    private Map<String, Object> purchasesOf(UUID buyerUid, boolean includeIssued) {
        List<Map<String, Object>> cart = new ArrayList<>();
        List<Map<String, Object>> paid = new ArrayList<>();
        List<Map<String, Object>> issued = new ArrayList<>();
        long cartTotal = 0;
        long paidTotal = 0;
        for (Interaction i : interactionRepository
                .findByIdentityUidAndInteractionTypeOrderByCreatedAtDesc(buyerUid, PURCHASE_TYPE)) {
            Map<String, Object> payload = deserialize(i.getDetail());
            Map<String, Object> row = lineRow(i, payload);
            if (i.getStatus() == InteractionStatus.PENDING) {
                if (STAGE_PAID.equals(payload.get(STAGE_KEY))) {
                    paid.add(row);
                    paidTotal += priceOf(payload);
                } else if (STAGE_CART.equals(payload.get(STAGE_KEY))) {
                    cart.add(row);
                    cartTotal += priceOf(payload);
                }
            } else if (includeIssued && i.getStatus() == InteractionStatus.CONFIRMED && issued.size() < 6) {
                issued.add(row);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cart", cart);
        m.put("paid", paid);
        if (includeIssued) {
            m.put("issued", issued);
        }
        m.put("cartTotal", cartTotal);
        m.put("paidTotal", paidTotal);
        return m;
    }

    private Map<String, Object> lineRow(Interaction line, Map<String, Object> payload) {
        Map<String, Object> m = new LinkedHashMap<>(payload);
        m.put("lineUid", line.getInteractionUid().toString());
        m.put("objectUid", line.getObjectUid());
        // Терминальные состояния читаются из статусной колонки, открытые — из stage.
        if (line.getStatus() == InteractionStatus.CONFIRMED) {
            m.put(STAGE_KEY, STAGE_ISSUED);
        } else if (line.getStatus() == InteractionStatus.REJECTED) {
            m.put(STAGE_KEY, STAGE_REMOVED);
        }
        return m;
    }

    private long priceOf(Map<String, Object> payload) {
        Object p = payload.get("price");
        return p instanceof Number n ? n.longValue() : 0;
    }

    private Interaction loadLine(UUID lineUid) {
        Interaction line = interactionRepository.findById(lineUid)
                .orElseThrow(() -> new IllegalArgumentException("Позиция чека не найдена."));
        if (!PURCHASE_TYPE.equals(line.getInteractionType())) {
            throw new IllegalArgumentException("Указанная запись не является покупкой.");
        }
        return line;
    }

    /** Кассовые ворота: роль + доверие + рабочий режим + время (отказ — человекочитаемый). */
    private void requireCashierGates(Identity cashier) {
        boolean working = sessionService.current(cashier.getIdentityUid()).getMode() == SessionMode.WORKING;
        ValidationService.Verdict verdict =
                validationService.retailCheckout(cashier, working, devTimeService.currentMockHour());
        if (verdict.outcome() != DecisionOutcome.APPROVED) {
            throw new AccessDeniedException(verdict.reason());
        }
    }

    /** Кросс-тенантное имя клиента (native): покупатель живёт в публичном тенанте, кассир — в орг-тенанте. */
    private String displayName(UUID identityUid) {
        return userRepository.findDisplayNameByIdentityUid(identityUid)
                .filter(s -> !s.isBlank())
                .orElse("Покупатель");
    }

    /** Тенант адресата subject-addressed записей журнала (см. AuditService#record c override). */
    private UUID tenantOf(UUID identityUid) {
        return identityRepository.findById(identityUid)
                .map(Identity::getTenantId)
                .orElse(TenantContext.PUBLIC_TENANT);
    }

    private String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    private String serialize(Map<String, Object> payload) {
        Map<String, Object> p = new LinkedHashMap<>(payload);
        String json = toJson(p);
        String[] expendable = {"store", "addedAt", "channel"};
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
