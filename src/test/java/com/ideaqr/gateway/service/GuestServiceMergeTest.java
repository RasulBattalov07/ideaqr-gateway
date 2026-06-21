package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.enums.IdentityStatus;
import com.ideaqr.gateway.domain.enums.IdentityType;
import com.ideaqr.gateway.repository.HistoryRepository;
import com.ideaqr.gateway.repository.IdentityRepository;
import com.ideaqr.gateway.util.Hashing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Locks in the guest-merge IDOR fix (audit 4.6): a merge requires the one-time token
 * that proves ownership of the guest session, and it aliases the guest rather than
 * rewriting the append-only journal (audit 4.5).
 */
@ExtendWith(MockitoExtension.class)
class GuestServiceMergeTest {

    @Mock private IdentityRepository identityRepository;
    @Mock private HistoryRepository historyRepository;
    @Mock private AuditService auditService;
    @Mock private EventService eventService;
    @Mock private NotificationService notificationService;

    @InjectMocks private GuestService guestService;

    private Identity primary() {
        Identity i = Identity.builder()
                .identityType(IdentityType.PRIMARY).status(IdentityStatus.ACTIVE).trustLevel(50).build();
        i.setIdentityUid(UUID.randomUUID());
        return i;
    }

    private Identity guestWithToken(String plaintextToken) {
        Identity g = Identity.builder()
                .identityType(IdentityType.GUEST).status(IdentityStatus.ACTIVE).trustLevel(10)
                .mergeTokenHash(Hashing.sha256Hex(plaintextToken)).build();
        g.setIdentityUid(UUID.randomUUID());
        return g;
    }

    @Test
    void mergeRejectsAWrongToken() {
        Identity target = primary();
        Identity guest = guestWithToken("real-token");
        when(identityRepository.findById(guest.getIdentityUid())).thenReturn(Optional.of(guest));

        assertThatThrownBy(() -> guestService.merge(target, guest.getIdentityUid(), "WRONG-TOKEN"))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(target.getLinkedGuestUids()).isEmpty();
    }

    @Test
    void mergeWithCorrectTokenAliasesGuestAndBurnsTheToken() {
        Identity target = primary();
        Identity guest = guestWithToken("real-token");
        when(identityRepository.findById(guest.getIdentityUid())).thenReturn(Optional.of(guest));
        when(historyRepository.findByIdentityUid(guest.getIdentityUid())).thenReturn(List.of());

        guestService.merge(target, guest.getIdentityUid(), "real-token");

        assertThat(target.getLinkedGuestUids()).contains(guest.getIdentityUid());
        assertThat(guest.getStatus()).isEqualTo(IdentityStatus.SUSPENDED);
        assertThat(guest.getMergeTokenHash()).isNull(); // single use — token burned
    }
}
