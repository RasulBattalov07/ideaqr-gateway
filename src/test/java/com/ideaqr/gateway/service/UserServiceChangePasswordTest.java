package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.ideaqr.gateway.repository.UserRepository;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the self-service password change (audit 1.7 / 4.9): the current
 * password is verified and the forced-change flag is cleared on success.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceChangePasswordTest {

    @Mock private UserRepository userRepository;
    @Mock private IdentityService identityService;
    @Mock private QrService qrService;
    @Mock private AuditService auditService;
    @Mock private PasswordEncoder passwordEncoder;

    @InjectMocks private UserService userService;

    private User user() {
        Identity identity = Identity.builder().build();
        identity.setIdentityUid(UUID.randomUUID());
        return User.builder()
                .username("u").passwordHash("OLD_HASH").firstName("A").lastName("B")
                .mustChangePassword(true).identity(identity).build();
    }

    @Test
    void wrongCurrentPasswordIsRejectedAndNothingIsSaved() {
        User u = user();
        when(passwordEncoder.matches("wrong", "OLD_HASH")).thenReturn(false);

        assertThatThrownBy(() -> userService.changePassword(u, "wrong", "BrandNewPass1"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(userRepository, never()).save(any());
        assertThat(u.isMustChangePassword()).isTrue();
    }

    @Test
    void correctCurrentPasswordUpdatesHashAndClearsForcedFlag() {
        User u = user();
        when(passwordEncoder.matches("Current123456", "OLD_HASH")).thenReturn(true);
        when(passwordEncoder.matches("BrandNewPass1", "OLD_HASH")).thenReturn(false);
        when(passwordEncoder.encode("BrandNewPass1")).thenReturn("NEW_HASH");

        userService.changePassword(u, "Current123456", "BrandNewPass1");

        assertThat(u.getPasswordHash()).isEqualTo("NEW_HASH");
        assertThat(u.isMustChangePassword()).isFalse();
        verify(userRepository).save(u);
    }
}
