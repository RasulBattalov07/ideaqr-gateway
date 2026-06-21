package com.ideaqr.gateway.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-context authorization tests — the negative cases that any vendor security
 * questionnaire asks for. They assert the URL/role matrix (audit 3.8) and that CSRF
 * is enforced on state-changing requests (audit 4.7). The demo accounts are seeded
 * into the in-memory test database by {@code DataSeeder}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void unauthenticatedApiCallIs401() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void publicHealthEndpointIsOpen() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "citizen", roles = "USER")
    void nonAdminCannotReachAdminApi() throws Exception {
        mvc.perform(get("/api/admin/users")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void adminCanListUsers() throws Exception {
        mvc.perform(get("/api/admin/users")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "citizen", roles = "USER")
    void stateChangingPostWithoutCsrfIsForbidden() throws Exception {
        mvc.perform(post("/api/v2/sos").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "citizen", roles = "USER")
    void stateChangingPostWithCsrfIsAccepted() throws Exception {
        mvc.perform(post("/api/v2/sos").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }
}
