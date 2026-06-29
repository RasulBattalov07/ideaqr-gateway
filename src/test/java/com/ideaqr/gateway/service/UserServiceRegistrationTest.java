package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.Qr;
import com.ideaqr.gateway.domain.User;
import com.ideaqr.gateway.domain.enums.IdentityStatus;
import com.ideaqr.gateway.domain.enums.IdentityType;
import com.ideaqr.gateway.domain.enums.RoleType;
import com.ideaqr.gateway.dto.RegistrationRequest;
import com.ideaqr.gateway.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks in the most critical fix (audit 4.1 / 4.2): a public self-registration can
 * NEVER derive a privileged role from client input, while the trusted server path
 * still can.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceRegistrationTest {

    @Mock private UserRepository userRepository;
    @Mock private IdentityService identityService;
    @Mock private QrService qrService;
    @Mock private AuditService auditService;
    @Mock private EmploymentService employmentService;
    @Mock private PasswordEncoder passwordEncoder;

    @InjectMocks private UserService userService;

    private RegistrationRequest request(String profession) {
        RegistrationRequest r = new RegistrationRequest();
        r.setUsername("eviladmin");
        r.setPassword("StrongPassw0rd");
        r.setFirstName("E");
        r.setLastName("V");
        r.setEmploymentStatus("EMPLOYED");
        r.setProfession(profession);
        return r;
    }

    private void stubCommonProvisioning() {
        when(userRepository.existsByUsername(any())).thenReturn(false);
        Identity dummy = Identity.builder()
                .identityType(IdentityType.PRIMARY).status(IdentityStatus.ACTIVE)
                .roles(new LinkedHashSet<>(Set.of(RoleType.CITIZEN))).trustLevel(50).build();
        dummy.setIdentityUid(UUID.randomUUID());
        when(identityService.createPrimaryIdentity(any(), anyInt())).thenReturn(dummy);
        when(qrService.createPrimaryQr(any())).thenReturn(Qr.builder().qrUid(UUID.randomUUID()).build());
        when(passwordEncoder.encode(any())).thenReturn("bcrypt-hash");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void selfRegistrationWithAdminProfessionStillYieldsCitizen() {
        stubCommonProvisioning();

        User user = userService.register(request("RETAIL_ADMIN"));

        assertThat(user.isAdmin()).isFalse();
        assertThat(user.getProfession()).isEqualTo("CITIZEN");

        // The identity must have been provisioned as a plain citizen — never admin.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<RoleType>> roles = ArgumentCaptor.forClass(Set.class);
        verify(identityService).createPrimaryIdentity(roles.capture(), eq(IdentityService.TRUST_CITIZEN));
        assertThat(roles.getValue()).containsExactly(RoleType.CITIZEN);
    }

    @Test
    void selfRegistrationWithDoctorProfessionStillYieldsCitizen() {
        stubCommonProvisioning();

        User user = userService.register(request("DOCTOR"));

        assertThat(user.getProfession()).isEqualTo("CITIZEN");
        verify(identityService).createPrimaryIdentity(any(), eq(IdentityService.TRUST_CITIZEN));
    }

    @Test
    void trustedProvisioningCanCreateAnAdmin() {
        stubCommonProvisioning();

        User user = userService.provisionTrusted(request("RETAIL_ADMIN"), "RETAIL_ADMIN");

        assertThat(user.isAdmin()).isTrue();
        assertThat(user.getProfession()).isEqualTo("RETAIL_ADMIN");
        verify(identityService).createPrimaryIdentity(any(), eq(IdentityService.TRUST_VERIFIED));
    }
}
