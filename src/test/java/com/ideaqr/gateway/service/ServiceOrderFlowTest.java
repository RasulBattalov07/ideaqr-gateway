package com.ideaqr.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.Interaction;
import com.ideaqr.gateway.domain.UserSession;
import com.ideaqr.gateway.domain.enums.IdentityStatus;
import com.ideaqr.gateway.domain.enums.IdentityType;
import com.ideaqr.gateway.domain.enums.InteractionStatus;
import com.ideaqr.gateway.domain.enums.RoleType;
import com.ideaqr.gateway.domain.enums.SessionMode;
import com.ideaqr.gateway.repository.DecisionRepository;
import com.ideaqr.gateway.repository.IdentityRepository;
import com.ideaqr.gateway.repository.InteractionRepository;
import com.ideaqr.gateway.repository.OrganizationMembershipRepository;
import com.ideaqr.gateway.repository.RequestRepository;
import com.ideaqr.gateway.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks in the THREE-PARTY «Услуги и быт» flow (дизайн-ТЗ заказчика): customer orders →
 * the order lands in the operator's dispatch board (role SERVICE_OPERATOR, working mode) →
 * operator assigns an executor (role EXECUTOR; second party in {@code target_identity_uid},
 * {@code stage=ASSIGNED} inside the detail JSON) → the customer scans the executor's
 * personal QR at the door and confirms arrival (scanned identity must match the assignee;
 * {@code stage=IN_PROGRESS}) → after the work a second scan confirms-and-pays (CONFIRMED).
 * The status column never leaves the V1 CHECK set; no schema change is involved.
 */
@ExtendWith(MockitoExtension.class)
class ServiceOrderFlowTest {

    @Mock private RequestRepository requestRepository;
    @Mock private DecisionRepository decisionRepository;
    @Mock private InteractionRepository interactionRepository;
    @Mock private AuditService auditService;
    @Mock private EventService eventService;
    @Mock private NotificationService notificationService;
    @Mock private OrganizationService organizationService;
    @Mock private CitizenDossierService citizenDossierService;
    @Mock private UserRepository userRepository;
    @Mock private IdentityRepository identityRepository;
    @Mock private OrganizationMembershipRepository membershipRepository;
    @Mock private SessionService sessionService;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks private ServiceOrderService service;

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

    private Identity operator() {
        return identityWithRoles(Set.of(RoleType.SERVICE_OPERATOR, RoleType.CITIZEN));
    }

    private Identity executor() {
        return identityWithRoles(Set.of(RoleType.EXECUTOR, RoleType.CITIZEN));
    }

    private Identity citizen() {
        return identityWithRoles(Set.of(RoleType.CITIZEN));
    }

    private Interaction orderOf(UUID customerUid, String detailJson) {
        Interaction order = Interaction.builder()
                .identityUid(customerUid)
                .requestUid(UUID.randomUUID())
                .objectUid("SERVICE_TRASH_PICKUP")
                .interactionType(ServiceOrderService.ORDER_TYPE)
                .status(InteractionStatus.PENDING)
                .detail(detailJson)
                .build();
        order.setInteractionUid(UUID.randomUUID());
        return order;
    }

    private void sessionOf(Identity identity, SessionMode mode) {
        when(sessionService.current(identity.getIdentityUid()))
                .thenReturn(UserSession.builder().identityUid(identity.getIdentityUid()).mode(mode).build());
    }

    // ------------------------------------------------------------------
    //  Назначение исполнителя (оператор-диспетчер)
    // ------------------------------------------------------------------

    @Test
    void assignSetsExecutorStageAndNotifiesBothSides() {
        Identity op = operator();
        Identity exec = executor();
        Interaction order = orderOf(UUID.randomUUID(),
                "{\"label\":\"Вывоз мусора от двери\",\"price\":\"500 ₸ / заявка\"}");
        sessionOf(op, SessionMode.WORKING);
        when(interactionRepository.findById(order.getInteractionUid())).thenReturn(Optional.of(order));
        when(identityRepository.findById(exec.getIdentityUid())).thenReturn(Optional.of(exec));
        when(userRepository.findDisplayNameByIdentityUid(any())).thenReturn(Optional.of("Арман Бекетов"));
        when(citizenDossierService.find(any())).thenReturn(Optional.empty());

        Map<String, Object> row = service.assign(op, order.getInteractionUid(), exec.getIdentityUid().toString());

        assertThat(order.getTargetIdentityUid()).isEqualTo(exec.getIdentityUid());
        assertThat(order.getStatus()).isEqualTo(InteractionStatus.PENDING); // статусная колонка не тронута
        assertThat(order.getDetail()).contains("\"stage\":\"ASSIGNED\"");
        assertThat(row.get("status")).isEqualTo("ASSIGNED");
        assertThat(row.get("assigneeName")).isEqualTo("Арман Бекетов");
        assertThat(row.get("executorUid")).isEqualTo(exec.getIdentityUid().toString());
        verify(notificationService).notify(eq(order.getIdentityUid()), eq("SERVICE"), contains("назначен исполнитель"));
        verify(notificationService).notify(eq(exec.getIdentityUid()), eq("SERVICE"), contains("наряд"));
    }

    @Test
    void assignIsOperatorOnlyAndNeedsWorkingMode() {
        Identity notOperator = citizen();
        assertThatThrownBy(() -> service.assign(notOperator, UUID.randomUUID(), "executor"))
                .isInstanceOf(AccessDeniedException.class);

        Identity offDuty = operator();
        sessionOf(offDuty, SessionMode.PERSONAL);
        assertThatThrownBy(() -> service.assign(offDuty, UUID.randomUUID(), "executor"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("рабочем режиме");
    }

    @Test
    void assignRejectsStaffWithoutExecutorRole() {
        Identity op = operator();
        Identity plainCitizen = citizen();
        Interaction order = orderOf(UUID.randomUUID(), "{\"label\":\"Вывоз мусора\"}");
        sessionOf(op, SessionMode.WORKING);
        when(interactionRepository.findById(order.getInteractionUid())).thenReturn(Optional.of(order));
        when(identityRepository.findById(plainCitizen.getIdentityUid())).thenReturn(Optional.of(plainCitizen));

        assertThatThrownBy(() -> service.assign(op, order.getInteractionUid(), plainCitizen.getIdentityUid().toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EXECUTOR");
    }

    @Test
    void assignRefusesAlreadyAssignedOrder() {
        Identity op = operator();
        Interaction order = orderOf(UUID.randomUUID(), "{\"label\":\"Вывоз мусора\",\"stage\":\"ASSIGNED\"}");
        order.setTargetIdentityUid(UUID.randomUUID());
        sessionOf(op, SessionMode.WORKING);
        when(interactionRepository.findById(order.getInteractionUid())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.assign(op, order.getInteractionUid(), "executor"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("уже назначен");
    }

    // ------------------------------------------------------------------
    //  Скан №1: подтверждение прихода (сверка личности по QR)
    // ------------------------------------------------------------------

    @Test
    void arrivalRejectsForeignScannedQr() {
        Identity customer = citizen();
        Identity exec = executor();
        Interaction order = orderOf(customer.getIdentityUid(), "{\"label\":\"Вывоз мусора\",\"stage\":\"ASSIGNED\"}");
        order.setTargetIdentityUid(exec.getIdentityUid());
        when(interactionRepository.findById(order.getInteractionUid())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.confirmArrival(customer, order.getInteractionUid(), UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("не совпадает");
    }

    @Test
    void arrivalMovesOrderToInProgress() {
        Identity customer = citizen();
        Identity exec = executor();
        Interaction order = orderOf(customer.getIdentityUid(), "{\"label\":\"Вывоз мусора\",\"stage\":\"ASSIGNED\"}");
        order.setTargetIdentityUid(exec.getIdentityUid());
        when(interactionRepository.findById(order.getInteractionUid())).thenReturn(Optional.of(order));
        when(userRepository.findDisplayNameByIdentityUid(any())).thenReturn(Optional.of("Арман Бекетов"));
        when(citizenDossierService.find(any())).thenReturn(Optional.empty());

        Map<String, Object> row = service.confirmArrival(customer, order.getInteractionUid(), exec.getIdentityUid());

        assertThat(order.getStatus()).isEqualTo(InteractionStatus.PENDING);
        assertThat(order.getDetail()).contains("\"stage\":\"IN_PROGRESS\"");
        assertThat(row.get("status")).isEqualTo("IN_PROGRESS");
        verify(notificationService).notify(eq(exec.getIdentityUid()), eq("SERVICE"), contains("подтвердил ваш приход"));
    }

    @Test
    void arrivalRequiresAssignedStage() {
        Identity customer = citizen();
        Interaction fresh = orderOf(customer.getIdentityUid(), "{\"label\":\"Вывоз мусора\"}");
        when(interactionRepository.findById(fresh.getInteractionUid())).thenReturn(Optional.of(fresh));

        assertThatThrownBy(() -> service.confirmArrival(customer, fresh.getInteractionUid(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("не назначен");
    }

    // ------------------------------------------------------------------
    //  Скан №2: подтвердить и оплатить
    // ------------------------------------------------------------------

    @Test
    void completeRequiresConfirmedArrivalFirst() {
        Identity customer = citizen();
        Identity exec = executor();
        Interaction assigned = orderOf(customer.getIdentityUid(), "{\"label\":\"Вывоз мусора\",\"stage\":\"ASSIGNED\"}");
        assigned.setTargetIdentityUid(exec.getIdentityUid());
        when(interactionRepository.findById(assigned.getInteractionUid())).thenReturn(Optional.of(assigned));

        assertThatThrownBy(() -> service.completeAndPay(customer, assigned.getInteractionUid(), exec.getIdentityUid()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("приход");
    }

    @Test
    void completeAndPayClosesOrderAndNotifiesBothSides() {
        Identity customer = citizen();
        Identity exec = executor();
        Interaction order = orderOf(customer.getIdentityUid(),
                "{\"label\":\"Вывоз мусора\",\"price\":\"500 ₸ / заявка\",\"stage\":\"IN_PROGRESS\"}");
        order.setTargetIdentityUid(exec.getIdentityUid());
        when(interactionRepository.findById(order.getInteractionUid())).thenReturn(Optional.of(order));
        when(userRepository.findDisplayNameByIdentityUid(any())).thenReturn(Optional.of("Арман Бекетов"));
        when(citizenDossierService.find(any())).thenReturn(Optional.empty());

        Map<String, Object> row = service.completeAndPay(customer, order.getInteractionUid(), exec.getIdentityUid());

        assertThat(order.getStatus()).isEqualTo(InteractionStatus.CONFIRMED);
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        verify(notificationService).notify(eq(customer.getIdentityUid()), eq("SERVICE"), contains("оплачена"));
        verify(notificationService).notify(eq(exec.getIdentityUid()), eq("SERVICE"), contains("оплатил"));
    }

    @Test
    void completeBelongsToCustomerOnly() {
        Identity customer = citizen();
        Identity stranger = citizen();
        Interaction order = orderOf(customer.getIdentityUid(), "{\"label\":\"Вывоз мусора\",\"stage\":\"IN_PROGRESS\"}");
        when(interactionRepository.findById(order.getInteractionUid())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.completeAndPay(stranger, order.getInteractionUid(), null))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ------------------------------------------------------------------
    //  Борды и контекст скана исполнителя
    // ------------------------------------------------------------------

    @Test
    void boardsAreRoleGated() {
        Identity plain = citizen();
        assertThatThrownBy(() -> service.queue(plain)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.assigned(plain)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.executors(plain)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void queueListsAllOrdersWithCustomerName() {
        Identity op = operator();
        Interaction fresh = orderOf(UUID.randomUUID(), "{\"label\":\"Вывоз мусора\"}");
        Interaction taken = orderOf(UUID.randomUUID(), "{\"label\":\"Вывоз мусора\",\"stage\":\"ASSIGNED\"}");
        taken.setTargetIdentityUid(UUID.randomUUID());
        when(interactionRepository.findByInteractionTypeOrderByCreatedAtDesc(ServiceOrderService.ORDER_TYPE))
                .thenReturn(List.of(fresh, taken));
        when(userRepository.findDisplayNameByIdentityUid(any())).thenReturn(Optional.of("Дамир Оспанов"));
        when(citizenDossierService.find(any())).thenReturn(Optional.empty());

        List<Map<String, Object>> rows = service.queue(op);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("status")).isEqualTo("NEW");
        assertThat(rows.get(0).get("customerName")).isEqualTo("Дамир Оспанов");
        assertThat(rows.get(1).get("status")).isEqualTo("ASSIGNED");
    }

    @Test
    void activeVisitOrderMatchesAssignedExecutorOnly() {
        Identity customer = citizen();
        Identity exec = executor();
        Interaction order = orderOf(customer.getIdentityUid(), "{\"label\":\"Вывоз мусора\",\"stage\":\"ASSIGNED\"}");
        order.setTargetIdentityUid(exec.getIdentityUid());
        when(interactionRepository.findByIdentityUidAndInteractionTypeOrderByCreatedAtDesc(
                customer.getIdentityUid(), ServiceOrderService.ORDER_TYPE)).thenReturn(List.of(order));

        assertThat(service.activeVisitOrder(customer.getIdentityUid(), exec.getIdentityUid())).isSameAs(order);
        assertThat(service.activeVisitOrder(customer.getIdentityUid(), UUID.randomUUID())).isNull();
    }

    @Test
    void visitPayloadOffersArrivalThenPayment() {
        Identity customer = citizen();
        Identity exec = executor();
        when(userRepository.findDisplayNameByIdentityUid(any())).thenReturn(Optional.of("Арман Бекетов"));
        when(citizenDossierService.find(any())).thenReturn(Optional.empty());

        Interaction assigned = orderOf(customer.getIdentityUid(), "{\"label\":\"Вывоз мусора\",\"stage\":\"ASSIGNED\"}");
        assigned.setTargetIdentityUid(exec.getIdentityUid());
        assertThat(service.visitPayload(assigned).get("action")).isEqualTo("CONFIRM_ARRIVAL");

        Interaction inProgress = orderOf(customer.getIdentityUid(), "{\"label\":\"Вывоз мусора\",\"stage\":\"IN_PROGRESS\"}");
        inProgress.setTargetIdentityUid(exec.getIdentityUid());
        Map<String, Object> visit = service.visitPayload(inProgress);
        assertThat(visit.get("action")).isEqualTo("CONFIRM_COMPLETE");
        assertThat(visit.get("status")).isEqualTo("IN_PROGRESS");
    }
}
