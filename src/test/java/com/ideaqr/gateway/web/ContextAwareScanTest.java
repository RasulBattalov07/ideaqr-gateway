package com.ideaqr.gateway.web;

import com.ideaqr.gateway.domain.User;
import com.ideaqr.gateway.repository.UserRepository;
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
 * Phase 2 — «Единый национальный QR»: ОДИН и тот же личный QR гражданина раскрывает
 * разные данные в зависимости от роли и режима сканирующего.
 *
 * <ul>
 *   <li>полицейский при исполнении → правовое досье (APPROVED, категория LEGAL);</li>
 *   <li>фармацевт при исполнении → только срез рецептов (без полной карты);</li>
 *   <li>врач при исполнении → медкарта ТОЛЬКО через согласие пациента (REVIEW) —
 *       конвейер Owner-Approval не обходится контекстной маршрутизацией;</li>
 *   <li>гражданин (и любой специалист в личном режиме) → публичная визитка + запрос
 *       полного профиля владельцу.</li>
 * </ul>
 *
 * <p>Рабочие часы гарантированы детерминированным тестовым clock (10:00).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ContextAwareScanTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository userRepository;

    private String citizenIdentityQr() {
        User citizen = userRepository.findByUsername("citizen").orElseThrow();
        return "IDENTITY:" + citizen.getIdentityUid();
    }

    private MockHttpSession workingSession(String modePath) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post(modePath).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        return session;
    }

    @Test
    @WithMockUser(username = "police", roles = "USER")
    void policeOnDutySeesLegalDossierByPersonalQr() throws Exception {
        MockHttpSession session = workingSession("/api/v2/mode/work");
        mvc.perform(post("/api/v2/scan").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectUid\":\"" + citizenIdentityQr() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPROVED"))
                .andExpect(jsonPath("$.category").value("LEGAL"))
                .andExpect(jsonPath("$.contextView").value("LEGAL"))
                .andExpect(jsonPath("$.dataClassification").value("SECRET"))
                .andExpect(jsonPath("$.policy").value("LEGAL_ACCESS"))
                .andExpect(jsonPath("$.data.criminalRecord.status").exists())
                .andExpect(jsonPath("$.data.fines").isArray())
                // Правовое досье — не медкарта: медицинские поля не раскрываются.
                .andExpect(jsonPath("$.data.allergies").doesNotExist());
    }

    @Test
    @WithMockUser(username = "pharmacist", roles = "USER")
    void pharmacistOnDutySeesOnlyPrescriptionsByPersonalQr() throws Exception {
        MockHttpSession session = workingSession("/api/v2/mode/work");
        mvc.perform(post("/api/v2/scan").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectUid\":\"" + citizenIdentityQr() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPROVED"))
                .andExpect(jsonPath("$.contextView").value("PRESCRIPTIONS"))
                .andExpect(jsonPath("$.data.prescriptions").isArray())
                // Сидер выписывает гражданину стартовый рецепт — фармацевту есть что выдавать.
                .andExpect(jsonPath("$.data.prescriptions[0].name").exists())
                // Минимальный доступ по роли: полная карта фармацевту не раскрывается.
                .andExpect(jsonPath("$.data.allergies").doesNotExist())
                .andExpect(jsonPath("$.data.chronicConditions").doesNotExist());
    }

    @Test
    @WithMockUser(username = "doctor", roles = "USER")
    void doctorStillNeedsPatientConsentThroughContextRouting() throws Exception {
        MockHttpSession session = workingSession("/api/v2/mode/work");
        mvc.perform(post("/api/v2/scan").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectUid\":\"" + citizenIdentityQr() + "\"}"))
                .andExpect(status().isOk())
                // Контекст врача распознан, но карта НЕ открыта — ждём согласия пациента.
                .andExpect(jsonPath("$.outcome").value("REVIEW"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.category").value("MEDICAL"))
                .andExpect(jsonPath("$.contextView").value("MEDICAL"))
                .andExpect(jsonPath("$.data.allergies").doesNotExist())
                .andExpect(jsonPath("$.data.iinMasked").doesNotExist());
    }

    @Test
    @WithMockUser(username = "citizen", roles = "USER")
    void citizenSeesBusinessCardInstantlyAndFullProfileStaysGated() throws Exception {
        mvc.perform(post("/api/v2/scan").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectUid\":\"IDENTITY:aaaaaaaa-0000-0000-0000-000000000007\"}"))
                .andExpect(status().isOk())
                // Визитка видна сразу, полный профиль — только после решения владельца.
                .andExpect(jsonPath("$.outcome").value("REVIEW"))
                .andExpect(jsonPath("$.contextView").value("BUSINESS_CARD"))
                .andExpect(jsonPath("$.data.fullName").exists())
                .andExpect(jsonPath("$.data.trustLevel").exists())
                .andExpect(jsonPath("$.interactionUid").exists())
                // Никаких чувствительных срезов на визитке нет.
                .andExpect(jsonPath("$.data.criminalRecord").doesNotExist())
                .andExpect(jsonPath("$.data.prescriptions").doesNotExist());
    }

    @Test
    @WithMockUser(username = "police", roles = "USER")
    void offDutyPoliceIsJustACitizenAndGetsBusinessCard() throws Exception {
        MockHttpSession session = workingSession("/api/v2/mode/personal");
        mvc.perform(post("/api/v2/scan").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectUid\":\"" + citizenIdentityQr() + "\"}"))
                .andExpect(status().isOk())
                // Контекст = роль × режим: вне смены правовое досье недоступно.
                .andExpect(jsonPath("$.contextView").value("BUSINESS_CARD"))
                .andExpect(jsonPath("$.outcome").value("REVIEW"))
                .andExpect(jsonPath("$.data.criminalRecord").doesNotExist());
    }

    @Test
    @WithMockUser(username = "citizen", roles = "USER")
    void ownerOpensOwnMedicalCardWithoutProfessionalRole() throws Exception {
        User citizen = userRepository.findByUsername("citizen").orElseThrow();
        String medUid = com.ideaqr.gateway.service.CitizenDossierService
                .medicalUidFor(citizen.getIdentityUid());
        mvc.perform(post("/api/v2/scan").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectUid\":\"" + medUid + "\"}"))
                .andExpect(status().isOk())
                // Owner-override: субъект данных читает свою карту без роли врача и без согласий.
                .andExpect(jsonPath("$.outcome").value("APPROVED"))
                .andExpect(jsonPath("$.data.patientName").exists())
                .andExpect(jsonPath("$.data.prescriptions").isArray());
    }
}
