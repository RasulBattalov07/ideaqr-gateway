package com.ideaqr.gateway.web;

import com.ideaqr.gateway.domain.Organization;
import com.ideaqr.gateway.domain.User;
import com.ideaqr.gateway.repository.OrganizationRepository;
import com.ideaqr.gateway.repository.UserRepository;
import jakarta.servlet.http.Cookie;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Демо-сценарий показа: «наём специалиста» из свежего eGov-аккаунта одним действием админа.
 *
 * <p>Ловушка, которую закрывает этот флоу: eGov-регистрация создаёт гражданина БЕЗ
 * организации (он не подавал заявку на трудоустройство), а выдача профессии сама по себе
 * не создаёт членства. Такой «врач» имел роль, но не мог включить рабочий режим — и весь
 * профессиональный доступ оставался закрыт. Теперь админ передаёт {@code organizationUid}
 * вместе с профессией, и членство ACTIVE создаётся тем же действием.</p>
 *
 * <p>Тест гоняет живой HTTP-флоу (cookie Spring Session, CSRF, OTP-вход) — ровно то, что
 * произойдёт на демонстрации. Рабочие часы детерминированы тестовым clock (10:00).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DemoSpecialistOnboardingTest {

    /** Оканчивается на 3 → персона «Алишер Касымов»; номер уникален для этого теста. */
    private static final String PHONE = "+7 700 880 90 13";
    private static final String PHONE_NORMALIZED = "77008809013";
    private static final String HOSPITAL = "Городская больница";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Test
    void adminAttachesOrganizationSoFreshEgovDoctorCanEnterWorkingMode() throws Exception {
        // ---- Шаг 1. Свежий гражданин регистрируется через eGov (только номер телефона).
        mvc.perform(post("/api/auth/egov/register").with(csrf())
                        .header("X-Forwarded-For", "10.77.1.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + PHONE + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.profession").value("CITIZEN"));

        // ---- Шаг 2. Ловушка воспроизводится: роль ЕСТЬ, организации НЕТ → рабочий режим закрыт.
        mvc.perform(post("/api/admin/users/" + PHONE_NORMALIZED + "/profession")
                        .with(user("admin").roles("USER", "ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profession\":\"DOCTOR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.details.organizationAttached").value(false));

        Cookie roleOnly = egovLogin("10.77.1.2");
        mvc.perform(post("/api/v2/mode/work").cookie(roleOnly).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message",
                        Matchers.containsString("Рабочий режим недоступен")));

        // ---- Шаг 3. Исправление: админ назначает профессию И организацию одним действием.
        Organization hospital = organizationRepository.findByName(HOSPITAL).orElseThrow();
        mvc.perform(post("/api/admin/users/" + PHONE_NORMALIZED + "/profession")
                        .with(user("admin").roles("USER", "ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profession\":\"DOCTOR\",\"organizationUid\":\""
                                + hospital.getOrganizationUid() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.details.profession").value("DOCTOR"))
                .andExpect(jsonPath("$.details.organizationAttached").value(true));

        // ---- Шаг 4. Врач входит заново (сессии отозваны сменой роли) — вход по демо-SMS-коду.
        Cookie doctor = egovLogin("10.77.1.3");
        mvc.perform(get("/api/auth/me").cookie(doctor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profession").value("DOCTOR"));

        // Организация видна в селекторе рабочего режима.
        mvc.perform(get("/api/v2/session").cookie(doctor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizations[0].name").value(HOSPITAL));

        // ---- Шаг 5. Главная проверка демо: рабочий режим УСПЕШНО включается.
        mvc.perform(post("/api/v2/mode/work").cookie(doctor).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("WORKING"))
                .andExpect(jsonPath("$.activeOrganizationName").value(HOSPITAL));

        // ---- Шаг 6. Контрольный выстрел: свежий врач сканирует личный QR пациента —
        // все профгейты (роль + доверие + режим + часы) пройдены, конвейер дошёл до
        // согласия пациента (REVIEW/MEDICAL), а не отбился «рабочим режимом».
        User citizen = userRepository.findByUsername("citizen").orElseThrow();
        mvc.perform(post("/api/v2/scan").cookie(doctor).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectUid\":\"IDENTITY:" + citizen.getIdentityUid() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REVIEW"))
                .andExpect(jsonPath("$.category").value("MEDICAL"))
                .andExpect(jsonPath("$.contextView").value("MEDICAL"));

        // Членство создано именно ACTIVE — это то, что открыло рабочий режим.
        assertThat(userRepository.findByUsername(PHONE_NORMALIZED)).isPresent();
    }

    @Test
    void pendingEmploymentClaimAloneDoesNotOpenWorkingMode() throws Exception {
        // Самозаявленное трудоустройство (PENDING) не должно открывать рабочий режим:
        // членство начинает управлять доступом только после подтверждения (ACTIVE).
        Organization hospital = organizationRepository.findByName(HOSPITAL).orElseThrow();
        mvc.perform(post("/api/auth/register").with(csrf())
                        .header("X-Forwarded-For", "10.77.1.4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"pending_claimant\",\"password\":\"PendingClaim123!\","
                                + "\"firstName\":\"Тест\",\"lastName\":\"Претендент\","
                                + "\"employmentStatus\":\"EMPLOYED\",\"profession\":\"CITIZEN\","
                                + "\"organizationUid\":\"" + hospital.getOrganizationUid() + "\"}"))
                .andExpect(status().isCreated());

        var login = mvc.perform(post("/login").with(csrf())
                        .header("X-Forwarded-For", "10.77.1.4")
                        .param("username", "pending_claimant").param("password", "PendingClaim123!"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie session = login.getResponse().getCookie("JSESSIONID");
        assertThat(session).isNotNull();

        mvc.perform(post("/api/v2/mode/work").cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message",
                        Matchers.containsString("Рабочий режим недоступен")));
    }

    /** OTP-вход по демо-коду 1234; возвращает cookie живой Spring Session. */
    private Cookie egovLogin(String ip) throws Exception {
        var result = mvc.perform(post("/api/auth/egov/login").with(csrf())
                        .header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + PHONE + "\",\"code\":\"1234\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = result.getResponse().getCookie("JSESSIONID");
        assertThat(cookie).as("eGov login must issue a session cookie").isNotNull();
        return cookie;
    }
}
