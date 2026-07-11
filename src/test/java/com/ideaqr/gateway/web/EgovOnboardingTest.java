package com.ideaqr.gateway.web;

import com.ideaqr.gateway.repository.RegistryObjectRepository;
import com.ideaqr.gateway.repository.UserRepository;
import com.ideaqr.gateway.service.CitizenDossierService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 — «умная регистрация» через mock-eGov: пользователь вводит ТОЛЬКО номер
 * телефона; система находит гражданина (детерминированная персона), по «Да, это я»
 * создаёт аккаунт + личность + полный цифровой пакет (медкарта, правовое досье,
 * визитка) и сразу открывает сессию; повторный вход — по демо-SMS-коду.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EgovOnboardingTest {

    /** Демо-флагман: последняя цифра 7 → персона «Расул Батталов» (ИИН начинается с 070420). */
    private static final String RASUL_PHONE = "+7 700 555 12 47";
    private static final String RASUL_PHONE_NORMALIZED = "77005551247";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RegistryObjectRepository registryObjectRepository;

    // Тестовый профиль доверяет одному прокси-хопу: каждый тест шлёт свой X-Forwarded-For,
    // чтобы жёсткие per-IP бакеты (login/register) не пересекались между тестами на loopback.

    @Test
    void lookupFindsRasulBattalovByPhoneEndingIn7() throws Exception {
        mvc.perform(post("/api/auth/egov/lookup").with(csrf())
                        .header("X-Forwarded-For", "10.77.0.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + RASUL_PHONE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.person.fullName").value("Расул Батталов"))
                .andExpect(jsonPath("$.person.iin", Matchers.startsWith("070420")))
                .andExpect(jsonPath("$.person.address", Matchers.containsString("Астана")))
                .andExpect(jsonPath("$.phone").value(RASUL_PHONE_NORMALIZED));
    }

    @Test
    void confirmingIdentityCreatesAccountDossierAndLiveSession() throws Exception {
        String phone = "+7 701 200 30 47"; // тоже оканчивается на 7 → Расул Батталов
        String normalized = "77012003047";

        var registered = mvc.perform(post("/api/auth/egov/register").with(csrf())
                        .header("X-Forwarded-For", "10.77.0.2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.user.username").value(normalized))
                .andExpect(jsonPath("$.user.firstName").value("Расул"))
                .andExpect(jsonPath("$.user.lastName").value("Батталов"))
                .andExpect(jsonPath("$.user.profession").value("CITIZEN"))
                .andReturn();

        // Сессия открыта программно и живёт в Spring Session (JDBC): переносится cookie
        // JSESSIONID из ответа — ровно как в браузере. /me аутентифицирован без пароля.
        var sessionCookie = registered.getResponse().getCookie("JSESSIONID");
        assertThat(sessionCookie).as("Spring Session must issue the session cookie").isNotNull();
        mvc.perform(get("/api/auth/me").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.username").value(normalized));

        // Полный пакет данных создан и привязан: медкарта + правовое досье + визитка.
        var user = userRepository.findByUsername(normalized).orElseThrow();
        var uid = user.getIdentityUid();
        assertThat(registryObjectRepository
                .findByObjectUidAnyTenant(CitizenDossierService.medicalUidFor(uid))).isPresent();
        assertThat(registryObjectRepository
                .findByObjectUidAnyTenant(CitizenDossierService.legalUidFor(uid))).isPresent();
        assertThat(registryObjectRepository
                .findByObjectUidAnyTenant(CitizenDossierService.vcardUidFor(uid))).isPresent();

        // Повторная регистрация того же номера — 409 с подсказкой про SMS-вход.
        mvc.perform(post("/api/auth/egov/register").with(csrf())
                        .header("X-Forwarded-For", "10.77.0.2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void otpLoginAcceptsDemoCodeAndRejectsWrongCode() throws Exception {
        String phone = "+7 702 900 11 22";
        mvc.perform(post("/api/auth/egov/register").with(csrf())
                        .header("X-Forwarded-For", "10.77.0.3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\"}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/auth/egov/login").with(csrf())
                        .header("X-Forwarded-For", "10.77.0.3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"code\":\"0000\"}"))
                .andExpect(status().isUnauthorized());

        var loggedIn = mvc.perform(post("/api/auth/egov/login").with(csrf())
                        .header("X-Forwarded-For", "10.77.0.3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"code\":\"1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.username").value("77029001122"))
                .andReturn();

        var sessionCookie = loggedIn.getResponse().getCookie("JSESSIONID");
        assertThat(sessionCookie).as("Spring Session must issue the session cookie").isNotNull();
        mvc.perform(get("/api/auth/me").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true));
    }

    @Test
    void formLoginAcceptsPhoneShapedIdentifier() throws Exception {
        // Аккаунт с username = нормализованный номер (как у eGov-аккаунтов), но с ИЗВЕСТНЫМ
        // паролем: проверяем, что formLogin резолвит «+7 703 111-22-33» в этот username.
        mvc.perform(post("/api/auth/register").with(csrf())
                        .header("X-Forwarded-For", "10.77.0.5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"77031112233\",\"password\":\"PhonePass123456\","
                                + "\"firstName\":\"Тест\",\"lastName\":\"Телефонов\","
                                + "\"employmentStatus\":\"UNEMPLOYED\",\"profession\":\"CITIZEN\"}"))
                .andExpect(status().isCreated());

        var loggedIn = mvc.perform(post("/login").with(csrf())
                        .header("X-Forwarded-For", "10.77.0.5")
                        .param("username", "+7 703 111-22-33")
                        .param("password", "PhonePass123456"))
                .andExpect(status().isOk())
                .andReturn();
        var sessionCookie = loggedIn.getResponse().getCookie("JSESSIONID");
        assertThat(sessionCookie).as("phone-form login must open a session").isNotNull();
        mvc.perform(get("/api/auth/me").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("77031112233"));

        // Буквенно-цифровой идентификатор НЕ переинтерпретируется по содержащимся цифрам.
        mvc.perform(post("/login").with(csrf())
                        .header("X-Forwarded-For", "10.77.0.6")
                        .param("username", "user77031112233")
                        .param("password", "PhonePass123456"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidPhoneIsRejectedWithLocalizedError() throws Exception {
        mvc.perform(post("/api/auth/egov/lookup").with(csrf())
                        .header("X-Forwarded-For", "10.77.0.4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"12345\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", Matchers.containsString("номер")));
    }
}
