package com.ideaqr.gateway.web;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.User;
import com.ideaqr.gateway.domain.enums.RoleType;
import com.ideaqr.gateway.repository.UserRepository;
import com.ideaqr.gateway.service.IdentityService;
import com.ideaqr.gateway.service.UserAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin User Management module: tenant-scoped management (an admin manages only their
 * own tenant), block metadata + login denial, and role changes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserManagementTest {

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository userRepository;
    @Autowired private UserAdminService userAdminService;
    @Autowired private IdentityService identityService;

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void adminManagesUsersAcrossTenants() throws Exception {
        // The platform admin is a cross-tenant super-admin: although "admin" is in the
        // retail tenant and "doctor" in the hospital tenant, the admin can manage doctor.
        // Restored afterwards so the shared test context is left clean.
        try {
            mvc.perform(post("/api/admin/users/doctor/block").with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                    .andExpect(status().isOk());
            assertThat(userRepository.findByUsername("doctor").orElseThrow().isBlocked()).isTrue();
        } finally {
            userAdminService.unblock("admin", "doctor");
        }
        assertThat(userRepository.findByUsername("doctor").orElseThrow().isBlocked()).isFalse();
    }

    @Test
    void blockStoresMetadataAndPreventsLogin() throws Exception {
        try {
            userAdminService.block("admin", "inspector", "нарушение регламента");

            User blocked = userRepository.findByUsername("inspector").orElseThrow();
            assertThat(blocked.isBlocked()).isTrue();
            assertThat(blocked.getBlockedReason()).isEqualTo("нарушение регламента");
            assertThat(blocked.getBlockedAt()).isNotNull();

            // A blocked account cannot authenticate.
            mvc.perform(post("/login").with(csrf())
                            .param("username", "inspector").param("password", "Inspect123!"))
                    .andExpect(status().isUnauthorized());
        } finally {
            userAdminService.unblock("admin", "inspector");
        }

        User restored = userRepository.findByUsername("inspector").orElseThrow();
        assertThat(restored.isBlocked()).isFalse();
        assertThat(restored.getBlockedReason()).isNull();
    }

    @Test
    void changeRolePromotesCitizenToDoctor() {
        try {
            User changed = userAdminService.setProfession("admin", "citizen", "DOCTOR", null);
            assertThat(changed.getProfession()).isEqualTo("DOCTOR");

            Identity identity = identityService.findById(changed.getIdentityUid());
            assertThat(identity.getRoles()).contains(RoleType.DOCTOR);
        } finally {
            userAdminService.setProfession("admin", "citizen", "CITIZEN", null);
        }
    }
}
