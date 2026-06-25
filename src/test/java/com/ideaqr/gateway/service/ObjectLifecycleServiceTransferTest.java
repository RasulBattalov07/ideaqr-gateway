package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.Decision;
import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.Interaction;
import com.ideaqr.gateway.domain.RegistryObject;
import com.ideaqr.gateway.domain.RequestRecord;
import com.ideaqr.gateway.domain.enums.EventType;
import com.ideaqr.gateway.domain.enums.HistoryEventType;
import com.ideaqr.gateway.domain.enums.IdentityStatus;
import com.ideaqr.gateway.domain.enums.IdentityType;
import com.ideaqr.gateway.domain.enums.ObjectCategory;
import com.ideaqr.gateway.domain.enums.ObjectStatus;
import com.ideaqr.gateway.repository.DecisionRepository;
import com.ideaqr.gateway.repository.IdentityRepository;
import com.ideaqr.gateway.repository.InteractionRepository;
import com.ideaqr.gateway.repository.RegistryObjectRepository;
import com.ideaqr.gateway.repository.RequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks in the object-ownership transfer (FINAL ТЗ — смена владельца объекта). A
 * transfer must NOT re-create the object: it reassigns the owner in place, moves the
 * object to MODIFIED and appends an {@code OBJECT_TRANSFERRED} record so the chain of
 * custody is never lost.
 */
@ExtendWith(MockitoExtension.class)
class ObjectLifecycleServiceTransferTest {

    @Mock private RegistryObjectRepository registryObjectRepository;
    @Mock private RequestRepository requestRepository;
    @Mock private DecisionRepository decisionRepository;
    @Mock private InteractionRepository interactionRepository;
    @Mock private IdentityRepository identityRepository;
    @Mock private AuditService auditService;
    @Mock private EventService eventService;
    @Mock private NotificationService notificationService;

    @InjectMocks private ObjectLifecycleService service;

    private Identity actor() {
        Identity i = Identity.builder()
                .identityType(IdentityType.PRIMARY).status(IdentityStatus.ACTIVE).trustLevel(60).build();
        i.setIdentityUid(UUID.randomUUID());
        return i;
    }

    private RegistryObject car(UUID owner, ObjectStatus status) {
        return RegistryObject.builder()
                .objectUid("CAR_FLYBO_01").category(ObjectCategory.GENERAL).displayName("Автомобиль Flybo")
                .dataJson("{}").createdByIdentityUid(owner).ownerIdentityUid(owner).status(status).build();
    }

    @Test
    void transferReassignsOwnerInPlaceAndRecordsTransfer() {
        UUID oldOwner = UUID.randomUUID();
        UUID newOwner = UUID.randomUUID();
        Identity actor = actor();
        RegistryObject car = car(oldOwner, ObjectStatus.ACTIVE);

        when(registryObjectRepository.findByObjectUid("CAR_FLYBO_01")).thenReturn(Optional.of(car));
        when(identityRepository.existsById(newOwner)).thenReturn(true);   // audit H-3: owner must exist
        when(requestRepository.save(any(RequestRecord.class))).thenAnswer(a -> a.getArgument(0));
        when(decisionRepository.save(any(Decision.class))).thenAnswer(a -> a.getArgument(0));
        when(interactionRepository.save(any(Interaction.class))).thenAnswer(a -> a.getArgument(0));
        when(interactionRepository.countByObjectUid("CAR_FLYBO_01")).thenReturn(3L);

        RegistryObject result = service.transfer(actor, "CAR_FLYBO_01", newOwner, "Продажа");

        assertThat(result).isSameAs(car);                          // not re-created
        assertThat(result.getOwnerIdentityUid()).isEqualTo(newOwner);
        assertThat(result.getStatus()).isEqualTo(ObjectStatus.MODIFIED);

        verify(auditService).record(eq(actor.getIdentityUid()), eq("CAR_FLYBO_01"),
                eq(HistoryEventType.OBJECT_TRANSFERRED), anyString(), any(), any(), any());
        verify(eventService).record(eq(EventType.OBJECT_TRANSFERRED), eq(actor.getIdentityUid()),
                eq("CAR_FLYBO_01"), any(), anyString());
    }

    @Test
    void transferToTheCurrentOwnerIsRejected() {
        UUID owner = UUID.randomUUID();
        when(registryObjectRepository.findByObjectUid("CAR_FLYBO_01"))
                .thenReturn(Optional.of(car(owner, ObjectStatus.ACTIVE)));

        assertThatThrownBy(() -> service.transfer(actor(), "CAR_FLYBO_01", owner, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void archivedObjectsCannotBeTransferred() {
        when(registryObjectRepository.findByObjectUid("CAR_FLYBO_01"))
                .thenReturn(Optional.of(car(UUID.randomUUID(), ObjectStatus.ARCHIVED)));

        assertThatThrownBy(() -> service.transfer(actor(), "CAR_FLYBO_01", UUID.randomUUID(), null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void transferToANonexistentOwnerIsRejected() {
        UUID phantomOwner = UUID.randomUUID();
        when(registryObjectRepository.findByObjectUid("CAR_FLYBO_01"))
                .thenReturn(Optional.of(car(UUID.randomUUID(), ObjectStatus.ACTIVE)));
        when(identityRepository.existsById(phantomOwner)).thenReturn(false); // audit H-3

        assertThatThrownBy(() -> service.transfer(actor(), "CAR_FLYBO_01", phantomOwner, "Продажа"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("новый владелец");
    }
}
