package com.ideaqr.gateway.tenant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end isolation over HTTP (audit 5.3): an authenticated admin's
 * {@code GET /api/admin/users} returns only users from the admin's own tenant. This
 * exercises the full path — {@code TenantInterceptor} resolves the tenant from the
 * caller and enables the Hibernate filter on the request session. The seeded "admin"
 * is in the retail tenant; "doctor" / "inspector" are in other tenants and must be
 * invisible.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TenantHttpIsolationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void adminListsOnlyOwnTenantUsers() throws Exception {
        mvc.perform(get("/api/admin/users").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].username", hasItem("admin")))
                .andExpect(jsonPath("$.content[*].username", not(hasItem("doctor"))))
                .andExpect(jsonPath("$.content[*].username", not(hasItem("inspector"))))
                .andExpect(jsonPath("$.content[*].username", not(hasItem("citizen"))));
    }
}
