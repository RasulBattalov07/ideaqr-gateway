package com.ideaqr.gateway.web;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Locks in the P0 fix for the audit's "consent paradox": clearing the professional gates
 * (role + trust + working mode + hours) is NOT enough to open a medical card — the patient
 * of record must give explicit consent, exactly like a personal-profile QR. A doctor's scan
 * therefore yields REVIEW (and reveals nothing) until the patient approves.
 *
 * <p>The server-side "time machine" pins the session hour into the working window and the
 * doctor goes on the clock, so both deterministic levers are exercised end-to-end.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MedicalConsentTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @WithMockUser(username = "doctor", roles = "USER")
    void doctorMustGetPatientConsentBeforeMedicalCardOpens() throws Exception {
        MockHttpSession session = new MockHttpSession();
        // Force working hours deterministically (server-side session mock, never client-trusted).
        mvc.perform(post("/api/v2/dev/time").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hour\":10}"))
                .andExpect(status().isOk());
        // Doctor goes on the clock — working mode gates professional categories.
        mvc.perform(post("/api/v2/mode/work").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        // Scanning the patient's medical card yields REVIEW (consent required) and reveals nothing.
        mvc.perform(post("/api/v2/scan").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"objectUid\":\"MED_RX_5521\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REVIEW"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.category").value("MEDICAL"))
                .andExpect(jsonPath("$.reason", Matchers.containsString("согласия пациента")))
                // The sensitive card must NOT be exposed before the patient agrees.
                .andExpect(jsonPath("$.data.allergies").doesNotExist())
                .andExpect(jsonPath("$.data.iinMasked").doesNotExist());
    }

    @Test
    @WithMockUser(username = "doctor", roles = "USER")
    void medicalCardIsBlockedOutsideWorkingMode() throws Exception {
        MockHttpSession session = new MockHttpSession();
        // Explicitly personal mode so the test is independent of any prior working-mode state.
        mvc.perform(post("/api/v2/mode/personal").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        // Professional category is blocked BEFORE consent is even considered — gate ordering holds.
        mvc.perform(post("/api/v2/scan").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"objectUid\":\"MED_RX_5521\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REJECTED"))
                .andExpect(jsonPath("$.reason", Matchers.containsString("рабочем режиме")));
    }
}
