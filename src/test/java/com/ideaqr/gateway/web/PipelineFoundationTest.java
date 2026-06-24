package com.ideaqr.gateway.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration check for the Document 22 additions:
 * <ul>
 *   <li>the golden pipeline carries the explicit <b>Organization</b> element — a scan by a
 *       doctor (hospital tenant) resolves and surfaces the governing organisation, plus the
 *       Data Classification and the governing Policy code;</li>
 *   <li>the read-only foundation API stubs (Policy catalog, Relationship projection) are
 *       reachable and engine-less.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PipelineFoundationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @WithMockUser(username = "doctor", roles = "USER")
    void scanThreadsOrganizationDataClassificationAndPolicy() throws Exception {
        mvc.perform(post("/api/v2/scan").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectUid\":\"RETAIL_NIKE_AF1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPROVED"))
                // Organization element of the pipeline — the doctor's governing org.
                .andExpect(jsonPath("$.organizationName").value("Городская больница"))
                .andExpect(jsonPath("$.organizationUid").exists())
                // Document 22: Data Classification + Policy surfaced on the decision.
                .andExpect(jsonPath("$.dataClassification").value("PUBLIC"))
                .andExpect(jsonPath("$.policy").value("PUBLIC_ACCESS"));
    }

    @Test
    @WithMockUser(username = "doctor", roles = "USER")
    void policyCatalogStubIsReadable() throws Exception {
        mvc.perform(get("/api/v2/foundation/policies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", hasItem("MEDICAL_ACCESS")))
                .andExpect(jsonPath("$[*].code", hasItem("ORGANIZATION_ACCESS")));
    }

    @Test
    @WithMockUser(username = "doctor", roles = "USER")
    void relationshipStubProjectsMembershipAsUniversalEdge() throws Exception {
        mvc.perform(get("/api/v2/foundation/relationships"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].relationshipType", hasItem("EMPLOYEE_ORGANIZATION")));
    }

    @Test
    void foundationStubsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v2/foundation/policies")).andExpect(status().isUnauthorized());
    }
}
