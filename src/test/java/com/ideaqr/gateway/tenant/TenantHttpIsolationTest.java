package com.ideaqr.gateway.tenant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The platform administrator is a cross-tenant super-admin (product rule —
 * "admin sees everyone"): {@code GET /api/admin/users} lists every account across ALL
 * tenants, so newly self-registered users (which land in the public tenant) are always
 * visible in the User Management panel. The seeded "admin" is in the retail tenant,
 * while "doctor" / "inspector" / "citizen" live in other tenants and must all appear.
 *
 * <p>Per-tenant data isolation for <b>non-admin</b> users — the actual SaaS guarantee
 * (audit 5.3) — is proved at the data layer by {@link TenantIsolationTest}: with the
 * tenant filter enabled for tenant A, a query returns only tenant A's rows.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TenantHttpIsolationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void adminSeesUsersAcrossAllTenants() throws Exception {
        mvc.perform(get("/api/admin/users").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].username", hasItem("admin")))
                .andExpect(jsonPath("$.content[*].username", hasItem("doctor")))
                .andExpect(jsonPath("$.content[*].username", hasItem("inspector")))
                .andExpect(jsonPath("$.content[*].username", hasItem("citizen")));
    }
}
