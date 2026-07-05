package com.ideaqr.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.Interaction;
import com.ideaqr.gateway.domain.enums.IdentityStatus;
import com.ideaqr.gateway.domain.enums.IdentityType;
import com.ideaqr.gateway.domain.enums.InteractionStatus;
import com.ideaqr.gateway.domain.enums.RoleType;
import com.ideaqr.gateway.repository.DecisionRepository;
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
 * Locks in the two-way «Услуги и быт» flow (P0 from the ZAKAZDAR product audit):
 * customer orders → the order lands in the executor queue (role SERVICE_OPERATOR) →
 * executor accepts (second party recorded in {@code target_identity_uid}) → executor
 * finishes ({@code stage=DONE} inside the detail JSON — the status column never leaves
 * the V1 CHECK set) → customer confirms (CONFIRMED). No schema change is involved.
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

    private Identity citizen() {
        return identityWithRoles(Set.of(RoleType.CITIZEN));
    }

    private Interaction orderOf(UUID customerUid) {
        Interaction order = Interaction.builder()
                .identityUid(customerUid)
                .requestUid(UUID.randomUUID())
                .objectUid("SERVICE_TRASH_PICKUP")
                .interactionType(ServiceOrderService.ORDER_TYPE)
                .status(InteractionStatus.PENDING)
                .detail("{\"label\":\"Вывоз мусора от двери\"}")
                .build();
        order.setInteractionUid(UUID.randomUUID());
        return order;
    }

    @Test
    void acceptAssignsExecutorAndNotifiesCustomer() {
        Identity op = operator();
        Interaction order = orderOf(UUID.randomUUID());
        when(interactionRepository.findById(order.getInteractionUid())).thenReturn(Optional.of(order));
        when(userRepository.findDisplayNameByIdentityUid(any())).thenReturn(Optional.of("Багдат Жумабаев"));

        Map<String, Object> row = service.accept(op, order.getInteractionUid());

        assertThat(order.getTargetIdentityUid()).isEqualTo(op.getIdentityUid());
        assertThat(order.getStatus()).isEqualTo(InteractionStatus.PENDING); // статусная колонка не тронута
        assertThat(row.get("status")).isEqualTo("ACCEPTED");
        assertThat(row.get("assigneeName")).isEqualTo("Багдат Жумабаев");
        verify(notificationService).notify(eq(order.getIdentityUid()), eq("SERVICE"), contains("принял заявку"));
    }

    @Test
    void queueAndAcceptAreOperatorOnly() {
        Identity notOperator = citizen();
        assertThatThrownBy(() -> service.queue(notOperator)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.accept(notOperator, UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void acceptRefusesOrderTakenByAnotherExecutor() {
        Identity op = operator();
        Interaction order = orderOf(UUID.randomUUID());
        order.setTargetIdentityUid(UUID.randomUUID()); // уже в работе у другого
        when(interactionRepository.findById(order.getInteractionUid())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.accept(op, order.getInteractionUid()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("другой исполнитель");
    }

    @Test
    void finishMarksStageDoneAndAsksCustomerConfirmation() {
        Identity op = operator();
        Interaction order = orderOf(UUID.randomUUID());
        order.setTargetIdentityUid(op.getIdentityUid());
        when(interactionRepository.findById(order.getInteractionUid())).thenReturn(Optional.of(order));
        when(userRepository.findDisplayNameByIdentityUid(any())).thenReturn(Optional.of("Багдат Жумабаев"));

        Map<String, Object> row = service.finish(op, order.getInteractionUid());

        assertThat(order.getStatus()).isEqualTo(InteractionStatus.PENDING); // ждёт заказчика, не CONFIRMED
        assertThat(order.getDetail()).contains("\"stage\":\"DONE\"");
        assertThat(row.get("status")).isEqualTo("DONE");
        verify(notificationService).notify(eq(order.getIdentityUid()), eq("SERVICE"), contains("Подтвердите выполнение"));
    }

    @Test
    void finishRequiresTheAssignee() {
        Identity op = operator();
        Interaction order = orderOf(UUID.randomUUID());
        order.setTargetIdentityUid(UUID.randomUUID()); // принял другой исполнитель
        when(interactionRepository.findById(order.getInteractionUid())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.finish(op, order.getInteractionUid()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void completeConfirmsAndThanksExecutor() {
        Identity customer = citizen();
        Identity op = operator();
        Interaction order = orderOf(customer.getIdentityUid());
        order.setTargetIdentityUid(op.getIdentityUid());
        order.setDetail("{\"label\":\"Вывоз мусора от двери\",\"stage\":\"DONE\"}");
        when(interactionRepository.findById(order.getInteractionUid())).thenReturn(Optional.of(order));
        when(userRepository.findDisplayNameByIdentityUid(any())).thenReturn(Optional.of("Багдат Жумабаев"));

        Map<String, Object> row = service.complete(customer, order.getInteractionUid());

        assertThat(order.getStatus()).isEqualTo(InteractionStatus.CONFIRMED);
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        verify(notificationService).notify(eq(customer.getIdentityUid()), eq("SERVICE"), contains("выполненной"));
        verify(notificationService).notify(eq(op.getIdentityUid()), eq("SERVICE"), contains("подтвердил выполнение"));
    }

    @Test
    void completeStillBelongsToTheCustomerOnly() {
        Identity stranger = citizen();
        Interaction order = orderOf(UUID.randomUUID());
        when(interactionRepository.findById(order.getInteractionUid())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.complete(stranger, order.getInteractionUid()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void queueListsAllOrdersWithCustomerNameAndOwnership() {
        Identity op = operator();
        Interaction fresh = orderOf(UUID.randomUUID());
        Interaction mine = orderOf(UUID.randomUUID());
        mine.setTargetIdentityUid(op.getIdentityUid());
        when(interactionRepository.findByInteractionTypeOrderByCreatedAtDesc(ServiceOrderService.ORDER_TYPE))
                .thenReturn(List.of(fresh, mine));
        when(userRepository.findDisplayNameByIdentityUid(any())).thenReturn(Optional.of("Дамир Оспанов"));

        List<Map<String, Object>> rows = service.queue(op);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("status")).isEqualTo("NEW");
        assertThat(rows.get(0).get("customerName")).isEqualTo("Дамир Оспанов");
        assertThat(rows.get(0).get("assigneeMe")).isEqualTo(false);
        assertThat(rows.get(1).get("status")).isEqualTo("ACCEPTED");
        assertThat(rows.get(1).get("assigneeMe")).isEqualTo(true);
    }
}
