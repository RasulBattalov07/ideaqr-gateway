package com.ideaqr.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.Interaction;
import com.ideaqr.gateway.domain.RegistryObject;
import com.ideaqr.gateway.domain.UserSession;
import com.ideaqr.gateway.domain.enums.DecisionOutcome;
import com.ideaqr.gateway.domain.enums.IdentityStatus;
import com.ideaqr.gateway.domain.enums.IdentityType;
import com.ideaqr.gateway.domain.enums.InteractionStatus;
import com.ideaqr.gateway.domain.enums.ObjectCategory;
import com.ideaqr.gateway.domain.enums.RoleType;
import com.ideaqr.gateway.domain.enums.SessionMode;
import com.ideaqr.gateway.repository.DecisionRepository;
import com.ideaqr.gateway.repository.IdentityRepository;
import com.ideaqr.gateway.repository.InteractionRepository;
import com.ideaqr.gateway.repository.RequestRepository;
import com.ideaqr.gateway.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks in the retail-checkout flow «Бизнес и магазины» (дизайн-ТЗ заказчика): the buyer
 * scans a shop item and either pays on the spot (line → PAID, «оплачен, но не выдан») or
 * adds it to the cart; the CASHIER (role + trust + working mode + hours) scans the buyer's
 * personal QR, collects the cart payment and issues paid lines — issuing transfers object
 * ownership through the standard {@code OBJECT_TRANSFERRED} pipeline (the QR never
 * changes). Lines are governed {@code Interaction(RETAIL_PURCHASE)} rows under
 * {@code RequestRecord(PURCHASE)}; the status column never leaves the V1 CHECK set.
 */
@ExtendWith(MockitoExtension.class)
class RetailCheckoutFlowTest {

    @Mock private RequestRepository requestRepository;
    @Mock private DecisionRepository decisionRepository;
    @Mock private InteractionRepository interactionRepository;
    @Mock private AuditService auditService;
    @Mock private EventService eventService;
    @Mock private NotificationService notificationService;
    @Mock private OrganizationService organizationService;
    @Mock private RegistryClient registryClient;
    @Mock private ObjectLifecycleService objectLifecycleService;
    @Mock private ValidationService validationService;
    @Mock private SessionService sessionService;
    @Mock private DevTimeService devTimeService;
    @Mock private IdentityRepository identityRepository;
    @Mock private UserRepository userRepository;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks private RetailCheckoutService service;

    private Identity identityWithRoles(Set<RoleType> roles) {
        Identity i = Identity.builder()
                .identityType(IdentityType.PRIMARY)
                .status(IdentityStatus.ACTIVE)
                .trustLevel(50)
                .roles(new LinkedHashSet<>(roles))
                .build();
        i.setIdentityUid(UUID.randomUUID());
        return i;
    }

    private Identity buyer() {
        return identityWithRoles(Set.of(RoleType.CITIZEN));
    }

    private Identity cashier() {
        return identityWithRoles(Set.of(RoleType.CASHIER, RoleType.CITIZEN));
    }

    /** Товар магазина: DB-объект с владельцем-магазином, forSale и ценой в payload. */
    private RegistryClient.Resolved shopItem(String uid, long price, UUID shopOwnerUid) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productName", "Футболка Uniqlo U Crew");
        data.put("price", price);
        data.put("currency", "₸");
        data.put("forSale", true);
        data.put("store", "IDEAQR Store · ТРЦ Керуен");
        RegistryObject db = RegistryObject.builder()
                .objectUid(uid)
                .category(ObjectCategory.RETAIL)
                .displayName("Футболка Uniqlo U Crew (белая)")
                .ownerIdentityUid(shopOwnerUid)
                .build();
        return new RegistryClient.Resolved(true, ObjectCategory.RETAIL, db.getDisplayName(), data, db);
    }

    private Interaction line(UUID buyerUid, String objectUid, String stage) {
        Interaction i = Interaction.builder()
                .identityUid(buyerUid)
                .requestUid(UUID.randomUUID())
                .objectUid(objectUid)
                .interactionType(RetailCheckoutService.PURCHASE_TYPE)
                .status(InteractionStatus.PENDING)
                .detail("{\"name\":\"Футболка Uniqlo U Crew\",\"price\":4990,\"currency\":\"₸\",\"stage\":\"" + stage + "\"}")
                .build();
        i.setInteractionUid(UUID.randomUUID());
        return i;
    }

    private void cashierOnDuty(Identity cashier, boolean approved) {
        when(sessionService.current(cashier.getIdentityUid())).thenReturn(
                UserSession.builder().identityUid(cashier.getIdentityUid()).mode(SessionMode.WORKING).build());
        when(validationService.retailCheckout(eq(cashier), anyBoolean(), any())).thenReturn(approved
                ? new ValidationService.Verdict(DecisionOutcome.APPROVED, "RETAIL_CHECKOUT", "ok", "LOW")
                : new ValidationService.Verdict(DecisionOutcome.REJECTED, "OUTSIDE_WORKING_HOURS",
                        "Кассовые операции возможны только в рабочее время (08:00–18:00).", "MEDIUM"));
    }

    // ------------------------------------------------------------------
    //  Покупатель: оплата на месте / корзина
    // ------------------------------------------------------------------

    @Test
    void buyNowCreatesPaidNotIssuedLine() {
        Identity buyer = buyer();
        when(registryClient.resolve("SHOP_TSHIRT_UNIQLO")).thenReturn(shopItem("SHOP_TSHIRT_UNIQLO", 4990, UUID.randomUUID()));
        when(interactionRepository.findByIdentityUidAndInteractionTypeOrderByCreatedAtDesc(
                buyer.getIdentityUid(), RetailCheckoutService.PURCHASE_TYPE)).thenReturn(List.of());
        // @PrePersist в тестах не срабатывает — эхо-мок присваивает uid сам, как БД.
        when(interactionRepository.save(any())).thenAnswer(inv -> {
            Interaction i = inv.getArgument(0);
            if (i.getInteractionUid() == null) {
                i.setInteractionUid(UUID.randomUUID());
            }
            return i;
        });
        when(requestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(decisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> row = service.buyNow(buyer, "SHOP_TSHIRT_UNIQLO");

        assertThat(row.get("stage")).isEqualTo("PAID");
        assertThat(row.get("channel")).isEqualTo("INSTANT");
        assertThat(row.get("price")).isEqualTo(4990L);
        verify(notificationService).notify(eq(buyer.getIdentityUid()), eq("RETAIL"), contains("кассиру"));
    }

    @Test
    void duplicateOpenLineIsRejected() {
        Identity buyer = buyer();
        when(registryClient.resolve("SHOP_TSHIRT_UNIQLO")).thenReturn(shopItem("SHOP_TSHIRT_UNIQLO", 4990, UUID.randomUUID()));
        when(interactionRepository.findByIdentityUidAndInteractionTypeOrderByCreatedAtDesc(
                buyer.getIdentityUid(), RetailCheckoutService.PURCHASE_TYPE))
                .thenReturn(List.of(line(buyer.getIdentityUid(), "SHOP_TSHIRT_UNIQLO", "CART")));

        assertThatThrownBy(() -> service.addToCart(buyer, "SHOP_TSHIRT_UNIQLO"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("уже в вашей корзине");
    }

    @Test
    void personalItemWithoutForSaleIsNotPurchasable() {
        Identity buyer = buyer();
        RegistryClient.Resolved item = shopItem("ITEM_JACKET_UNIQLO", 24990, UUID.randomUUID());
        item.data().remove("forSale"); // личная вещь: цена есть, но через кассу не продаётся
        when(registryClient.resolve("ITEM_JACKET_UNIQLO")).thenReturn(item);

        assertThatThrownBy(() -> service.buyNow(buyer, "ITEM_JACKET_UNIQLO"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не продаётся");
    }

    @Test
    void removeIsAllowedOnlyForUnpaidCartLines() {
        Identity buyer = buyer();
        Interaction paid = line(buyer.getIdentityUid(), "SHOP_TSHIRT_UNIQLO", "PAID");
        when(interactionRepository.findById(paid.getInteractionUid())).thenReturn(Optional.of(paid));
        assertThatThrownBy(() -> service.removeFromCart(buyer, paid.getInteractionUid()))
                .isInstanceOf(IllegalStateException.class);

        Interaction cart = line(buyer.getIdentityUid(), "SHOP_JEANS_LEVIS", "CART");
        when(interactionRepository.findById(cart.getInteractionUid())).thenReturn(Optional.of(cart));
        Map<String, Object> row = service.removeFromCart(buyer, cart.getInteractionUid());

        assertThat(cart.getStatus()).isEqualTo(InteractionStatus.REJECTED);
        assertThat(row.get("stage")).isEqualTo("REMOVED");
    }

    // ------------------------------------------------------------------
    //  Кассир: приём оплаты и выдача
    // ------------------------------------------------------------------

    @Test
    void cashierGatesGuardCheckoutOperations() {
        Identity offDuty = cashier();
        cashierOnDuty(offDuty, false);
        assertThatThrownBy(() -> service.collectPayment(offDuty, UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("рабочее время");
    }

    @Test
    void collectPaymentMarksWholeCartPaid() {
        Identity cash = cashier();
        UUID buyerUid = UUID.randomUUID();
        Interaction a = line(buyerUid, "SHOP_TSHIRT_UNIQLO", "CART");
        Interaction b = line(buyerUid, "SHOP_JEANS_LEVIS", "CART");
        cashierOnDuty(cash, true);
        when(interactionRepository.findByIdentityUidAndInteractionTypeOrderByCreatedAtDesc(
                buyerUid, RetailCheckoutService.PURCHASE_TYPE)).thenReturn(List.of(a, b));
        when(interactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findDisplayNameByIdentityUid(buyerUid)).thenReturn(Optional.of("Дамир Оспанов"));
        when(identityRepository.findById(buyerUid)).thenReturn(Optional.empty());

        Map<String, Object> view = service.collectPayment(cash, buyerUid);

        assertThat(a.getDetail()).contains("\"stage\":\"PAID\"");
        assertThat(b.getDetail()).contains("\"stage\":\"PAID\"");
        assertThat(view.get("customerName")).isEqualTo("Дамир Оспанов");
        verify(notificationService).notify(eq(buyerUid), eq("RETAIL"), contains("Оплата принята"));
    }

    @Test
    void collectPaymentNeedsAtLeastOneCartLine() {
        Identity cash = cashier();
        UUID buyerUid = UUID.randomUUID();
        cashierOnDuty(cash, true);
        when(interactionRepository.findByIdentityUidAndInteractionTypeOrderByCreatedAtDesc(
                buyerUid, RetailCheckoutService.PURCHASE_TYPE))
                .thenReturn(List.of(line(buyerUid, "SHOP_TSHIRT_UNIQLO", "PAID")));

        assertThatThrownBy(() -> service.collectPayment(cash, buyerUid))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("нет неоплаченных");
    }

    @Test
    void issueTransfersOwnershipAndClosesLine() {
        Identity cash = cashier();
        UUID buyerUid = UUID.randomUUID();
        Interaction paid = line(buyerUid, "SHOP_TSHIRT_UNIQLO", "PAID");
        cashierOnDuty(cash, true);
        when(interactionRepository.findById(paid.getInteractionUid())).thenReturn(Optional.of(paid));
        when(interactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findDisplayNameByIdentityUid(buyerUid)).thenReturn(Optional.of("Дамир Оспанов"));
        when(identityRepository.findById(buyerUid)).thenReturn(Optional.empty());

        Map<String, Object> row = service.issue(cash, paid.getInteractionUid());

        // Выдача = передача владения стандартным конвейером: QR не меняется, след полный.
        verify(objectLifecycleService).transfer(eq(cash), eq("SHOP_TSHIRT_UNIQLO"), eq(buyerUid), any());
        assertThat(paid.getStatus()).isEqualTo(InteractionStatus.CONFIRMED);
        assertThat(paid.getTargetIdentityUid()).isEqualTo(cash.getIdentityUid());
        assertThat(row.get("stage")).isEqualTo("ISSUED");
        verify(notificationService).notify(eq(buyerUid), eq("RETAIL"), contains("выдан"));
    }

    @Test
    void issueRequiresPaidStage() {
        Identity cash = cashier();
        Interaction cartLine = line(UUID.randomUUID(), "SHOP_TSHIRT_UNIQLO", "CART");
        cashierOnDuty(cash, true);
        when(interactionRepository.findById(cartLine.getInteractionUid())).thenReturn(Optional.of(cartLine));

        assertThatThrownBy(() -> service.issue(cash, cartLine.getInteractionUid()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("оплаченную");
    }

    // ------------------------------------------------------------------
    //  Контекст карточки товара (commerce-блок)
    // ------------------------------------------------------------------

    @Test
    void commerceReflectsOpenLineState() {
        Identity buyer = buyer();
        UUID shopOwner = UUID.randomUUID();
        RegistryClient.Resolved item = shopItem("SHOP_TSHIRT_UNIQLO", 4990, shopOwner);

        when(interactionRepository.findByIdentityUidAndInteractionTypeOrderByCreatedAtDesc(
                buyer.getIdentityUid(), RetailCheckoutService.PURCHASE_TYPE)).thenReturn(List.of());
        Map<String, Object> fresh = service.commerceFor(buyer, item, shopOwner);
        assertThat(fresh).isNotNull();
        assertThat(fresh.get("state")).isEqualTo("AVAILABLE");
        assertThat(fresh.get("price")).isEqualTo(4990L);

        Interaction open = line(buyer.getIdentityUid(), "SHOP_TSHIRT_UNIQLO", "CART");
        when(interactionRepository.findByIdentityUidAndInteractionTypeOrderByCreatedAtDesc(
                buyer.getIdentityUid(), RetailCheckoutService.PURCHASE_TYPE)).thenReturn(List.of(open));
        Map<String, Object> inCart = service.commerceFor(buyer, item, shopOwner);
        assertThat(inCart.get("state")).isEqualTo("IN_CART");
        assertThat(inCart.get("lineUid")).isEqualTo(open.getInteractionUid().toString());
    }

    @Test
    void commerceIsAbsentForOwnersAndNonSaleItems() {
        Identity buyer = buyer();
        UUID shopOwner = UUID.randomUUID();

        // Владелец товара (магазин сканирует свой же склад) — кассовые кнопки не нужны.
        RegistryClient.Resolved own = shopItem("SHOP_TSHIRT_UNIQLO", 4990, buyer.getIdentityUid());
        assertThat(service.commerceFor(buyer, own, buyer.getIdentityUid())).isNull();

        // Личная вещь без forSale — не товар кассы.
        RegistryClient.Resolved personal = shopItem("ITEM_JACKET_UNIQLO", 24990, shopOwner);
        personal.data().remove("forSale");
        assertThat(service.commerceFor(buyer, personal, shopOwner)).isNull();
    }
}
