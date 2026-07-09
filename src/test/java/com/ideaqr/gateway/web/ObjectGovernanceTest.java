package com.ideaqr.gateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideaqr.gateway.domain.History;
import com.ideaqr.gateway.domain.User;
import com.ideaqr.gateway.domain.enums.HistoryEventType;
import com.ideaqr.gateway.repository.HistoryRepository;
import com.ideaqr.gateway.repository.UserRepository;
import com.ideaqr.gateway.service.AuditService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * УНИВЕРСАЛЬНОЕ ПРАВИЛО ОБЪЕКТОВ: у каждой вещи один неизменяемый QR, а карточка
 * определяется тем, КТО сканирует.
 *
 * <ul>
 *   <li>гость → только публичная карточка, НОЛЬ сведений о владельце (ни имени, ни UID,
 *       ни даже факта владения);</li>
 *   <li>зарегистрированный пользователь → расширенная карточка + AI-подбор + кнопка
 *       «Профиль владельца» (P2P-согласие через Request → Decision);</li>
 *   <li>владелец → мгновенный полный доступ и управление, без запросов;</li>
 *   <li>полиция при исполнении → данные владельца БЕЗ согласия, но с независимой записью
 *       в hash-chain журнале владельца и уведомлением (след нельзя оставить незаметно);</li>
 *   <li>pre-ownership → покупка/привязка и передача прав НЕ меняют QR объекта.</li>
 * </ul>
 *
 * <p>Демо-вещь {@code ITEM_JACKET_UNIQLO} посеяна с владельцем-«citizen»; рабочие часы
 * гарантированы детерминированным тестовым clock (10:00).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ObjectGovernanceTest {

    private static final String JACKET = "ITEM_JACKET_UNIQLO";
    private static final String CATALOG_DOC = "DOC_STUDENT_AITU";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private HistoryRepository historyRepository;

    @Autowired
    private AuditService auditService;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID citizenUid() {
        return userRepository.findByUsername("citizen").orElseThrow().getIdentityUid();
    }

    private String citizenLastName() {
        return userRepository.findByUsername("citizen").map(User::getLastName).orElseThrow();
    }

    private MockHttpSession workingSession(String modePath) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post(modePath).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        return session;
    }

    private String scanBody(String objectUid) {
        return "{\"objectUid\":\"" + objectUid + "\"}";
    }

    // ------------------------------------------------------------------
    //  1. ГОСТЬ: публичная карточка, никакой информации о владельце
    // ------------------------------------------------------------------

    @Test
    void guestSeesPublicCardAndZeroOwnerInformation() throws Exception {
        // Гостевая сессия — как в браузере: программный вход, JSESSIONID из ответа.
        // Уникальный X-Forwarded-For изолирует rate-limit-бакет этого теста.
        MvcResult guest = mvc.perform(post("/api/auth/guest").with(csrf())
                        .header("X-Forwarded-For", "10.77.42.1"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie sid = guest.getResponse().getCookie("JSESSIONID");
        assertThat(sid).as("guest session cookie").isNotNull();

        MvcResult scan = mvc.perform(post("/api/v2/scan").cookie(sid).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(scanBody(JACKET)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPROVED"))
                .andExpect(jsonPath("$.contextView").value("OBJECT_PUBLIC"))
                .andExpect(jsonPath("$.accessTier").value("PUBLIC"))
                .andExpect(jsonPath("$.registrationRequired").value(true))
                // Публичная проекция: название/бренд/описание/рейтинг есть, цена срезана.
                .andExpect(jsonPath("$.data.productName").exists())
                .andExpect(jsonPath("$.data.price").doesNotExist())
                // КАТЕГОРИЧЕСКИ никакой информации о владельце и никакого AI-профилирования.
                .andExpect(jsonPath("$.ownership").doesNotExist())
                .andExpect(jsonPath("$.ownerDisclosure").doesNotExist())
                .andExpect(jsonPath("$.aiCard").doesNotExist())
                .andReturn();

        // Страховка сильнее схемы: во всём ответе нет ни UID владельца, ни его фамилии,
        // ни даже намёка на существование владельца (кнопок/флагов владения).
        String body = scan.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain(citizenUid().toString());
        assertThat(body).doesNotContain(citizenLastName());
        assertThat(body).doesNotContain("ownerRequestAvailable");
    }

    // ------------------------------------------------------------------
    //  2. ПОЛЬЗОВАТЕЛЬ: расширенная карточка + AI, владелец скрыт до согласия
    // ------------------------------------------------------------------

    @Test
    @WithMockUser(username = "seller", roles = "USER")
    void registeredUserGetsExtendedCardWithAiButOwnerStaysHidden() throws Exception {
        MvcResult scan = mvc.perform(post("/api/v2/scan").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(scanBody(JACKET)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPROVED"))
                .andExpect(jsonPath("$.contextView").value("OBJECT_EXTENDED"))
                .andExpect(jsonPath("$.accessTier").value("FULL"))
                .andExpect(jsonPath("$.data.price").exists())
                // Кнопка «Профиль владельца» доступна, но сам владелец не раскрыт.
                .andExpect(jsonPath("$.ownership.ownerRequestAvailable").value(true))
                .andExpect(jsonPath("$.ownership.isOwner").value(false))
                .andExpect(jsonPath("$.ownerDisclosure").doesNotExist())
                // BLOCK 3: интеллектуальная AI-карточка по типу вещи (одежда).
                .andExpect(jsonPath("$.aiCard.itemType").value("CLOTHING"))
                .andExpect(jsonPath("$.aiCard.headline").exists())
                .andExpect(jsonPath("$.aiCard.items").isArray())
                .andReturn();

        String body = scan.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain(citizenUid().toString());
        assertThat(body).doesNotContain(citizenLastName());
    }

    // ------------------------------------------------------------------
    //  3. ВЛАДЕЛЕЦ: мгновенный полный доступ, без запросов
    // ------------------------------------------------------------------

    @Test
    @WithMockUser(username = "citizen", roles = "USER")
    void ownerGetsInstantFullManagementView() throws Exception {
        mvc.perform(post("/api/v2/scan").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(scanBody(JACKET)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPROVED"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.contextView").value("OBJECT_OWNER"))
                .andExpect(jsonPath("$.ownership.isOwner").value(true))
                .andExpect(jsonPath("$.ownership.transferAvailable").value(true))
                .andExpect(jsonPath("$.data.price").exists())
                .andExpect(jsonPath("$.aiCard.headline").exists());
    }

    // ------------------------------------------------------------------
    //  4. ПОЛИЦИЯ ПРИ ИСПОЛНЕНИИ: раскрытие владельца + жёсткий след в журнале
    // ------------------------------------------------------------------

    @Test
    @WithMockUser(username = "police", roles = "USER")
    void policeOnDutyGetsOwnerDisclosureAndLeavesTamperEvidentAuditTrail() throws Exception {
        UUID owner = citizenUid();
        long disclosuresBefore = authorityDisclosureEntriesFor(owner);

        MockHttpSession session = workingSession("/api/v2/mode/work");
        mvc.perform(post("/api/v2/scan").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(scanBody(JACKET)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPROVED"))
                .andExpect(jsonPath("$.contextView").value("OBJECT_AUTHORITY"))
                // Установленный законом объём — без согласия владельца.
                .andExpect(jsonPath("$.ownerDisclosure.fullName").value("Дамир Оспанов"))
                .andExpect(jsonPath("$.ownerDisclosure.iin").exists())
                .andExpect(jsonPath("$.ownerDisclosure.registrationAddress").exists())
                // AI-профилирование не сопровождает служебный доступ.
                .andExpect(jsonPath("$.aiCard").doesNotExist());

        // СТРОГОЕ ТРЕБОВАНИЕ: раскрытие зафиксировано независимой записью в журнале
        // ВЛАДЕЛЬЦА (он видит его в своей истории), а hash-chain остаётся валидным —
        // след уполномоченного лица тампер-очевиден.
        assertThat(authorityDisclosureEntriesFor(owner)).isGreaterThan(disclosuresBefore);
        AuditService.ChainVerification chain = auditService.verifyChain();
        assertThat(chain.valid()).as("audit hash-chain must stay intact").isTrue();
    }

    private long authorityDisclosureEntriesFor(UUID ownerUid) {
        return historyRepository.findByIdentityUid(ownerUid).stream()
                .filter(h -> h.getEventType() == HistoryEventType.ACCESS_GRANTED)
                .map(History::getDescription)
                .filter(d -> d != null && d.startsWith("СЛУЖЕБНЫЙ ДОСТУП"))
                .count();
    }

    @Test
    @WithMockUser(username = "police", roles = "USER")
    void policeOffDutyIsJustARegisteredUserWithoutDisclosure() throws Exception {
        MockHttpSession session = workingSession("/api/v2/mode/personal");
        mvc.perform(post("/api/v2/scan").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(scanBody(JACKET)))
                .andExpect(status().isOk())
                // Контекст = роль × режим: вне смены полицейский видит обычную карточку.
                .andExpect(jsonPath("$.contextView").value("OBJECT_EXTENDED"))
                .andExpect(jsonPath("$.ownerDisclosure").doesNotExist())
                .andExpect(jsonPath("$.ownership.ownerRequestAvailable").value(true));
    }

    // ------------------------------------------------------------------
    //  P2P Consent Flow: «Профиль владельца» открывается только по решению владельца
    // ------------------------------------------------------------------

    @Test
    void ownerProfileOpensOnlyAfterOwnerConfirms() throws Exception {
        // Пользователь нажимает «Профиль владельца»: информация НЕ открывается — создаётся Request.
        MvcResult requested = mvc.perform(post("/api/v2/objects/" + JACKET + "/owner-request")
                        .with(user("seller").roles("USER")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REVIEW"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.interactionUid").exists())
                .andReturn();
        String interactionUid = objectMapper
                .readTree(requested.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("interactionUid").asText();

        // Пока владелец молчит — профиля нет.
        mvc.perform(get("/api/v2/access/" + interactionUid + "/result")
                        .with(user("seller").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REVIEW"))
                .andExpect(jsonPath("$.data").doesNotExist());

        // Владелец видит адресный запрос с контекстом объекта…
        mvc.perform(get("/api/v2/access/pending").with(user("citizen").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].kind", hasItem("OWNER_PROFILE")));

        // …и подтверждает (Decision).
        mvc.perform(post("/api/v2/access/" + interactionUid + "/confirm")
                        .with(user("citizen").roles("USER")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPROVED"));

        // Только теперь сканирующий получает профиль владельца (в пределах разрешённых прав).
        mvc.perform(get("/api/v2/access/" + interactionUid + "/result")
                        .with(user("seller").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPROVED"))
                .andExpect(jsonPath("$.data.fullName").value("Дамир Оспанов"))
                .andExpect(jsonPath("$.data.fullProfile").value(true));
    }

    @Test
    void ownerProfileStaysClosedWhenOwnerRejects() throws Exception {
        MvcResult requested = mvc.perform(post("/api/v2/objects/" + JACKET + "/owner-request")
                        .with(user("pharmacist").roles("USER")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REVIEW"))
                .andReturn();
        String interactionUid = objectMapper
                .readTree(requested.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("interactionUid").asText();

        mvc.perform(post("/api/v2/access/" + interactionUid + "/reject")
                        .with(user("citizen").roles("USER")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REJECTED"));

        mvc.perform(get("/api/v2/access/" + interactionUid + "/result")
                        .with(user("pharmacist").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REJECTED"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    // ------------------------------------------------------------------
    //  Pre-ownership → покупка → передача: QR объекта никогда не меняется
    // ------------------------------------------------------------------

    @Test
    void claimAndTransferRebindOwnerWhileQrStaysTheSame() throws Exception {
        // Каталожная вещь без владельца: стандартная карточка + доступное оформление.
        mvc.perform(post("/api/v2/scan").with(user("citizen").roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(scanBody(CATALOG_DOC)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextView").value("OBJECT_EXTENDED"))
                .andExpect(jsonPath("$.ownership.state").value("UNOWNED"))
                .andExpect(jsonPath("$.ownership.claimAvailable").value(true))
                .andExpect(jsonPath("$.ownership.ownerRequestAvailable").value(false));

        // Покупка: объект привязывается к Identity покупателя.
        mvc.perform(post("/api/v2/objects/" + CATALOG_DOC + "/claim")
                        .with(user("citizen").roles("USER")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // Тот же самый идентификатор (QR не менялся) теперь открывает карточку владельца.
        mvc.perform(post("/api/v2/scan").with(user("citizen").roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(scanBody(CATALOG_DOC)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextView").value("OBJECT_OWNER"))
                .andExpect(jsonPath("$.ownership.isOwner").value(true));

        // Повторно оформить чужую вещь нельзя.
        mvc.perform(post("/api/v2/objects/" + CATALOG_DOC + "/claim")
                        .with(user("seller").roles("USER")).with(csrf()))
                .andExpect(status().is4xxClientError());

        // Передача прав владельцем: получатель по имени пользователя.
        mvc.perform(post("/api/v2/objects/" + CATALOG_DOC + "/transfer")
                        .with(user("citizen").roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newOwner\":\"seller\",\"note\":\"Продажа (демо)\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // Прежний владелец стал обычным пользователем, новый — владельцем; QR всё тот же.
        mvc.perform(post("/api/v2/scan").with(user("citizen").roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(scanBody(CATALOG_DOC)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextView").value("OBJECT_EXTENDED"))
                .andExpect(jsonPath("$.ownership.ownerRequestAvailable").value(true));
        mvc.perform(post("/api/v2/scan").with(user("seller").roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(scanBody(CATALOG_DOC)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextView").value("OBJECT_OWNER"));

        // Не-владелец передать вещь не может.
        mvc.perform(post("/api/v2/objects/" + CATALOG_DOC + "/transfer")
                        .with(user("citizen").roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newOwner\":\"pharmacist\"}"))
                .andExpect(status().isForbidden());

        // Цепочка владения — в неизменяемом журнале.
        assertThat(historyRepository.findByIdentityUid(citizenUid()).stream()
                .anyMatch(h -> h.getEventType() == HistoryEventType.OBJECT_TRANSFERRED
                        && h.getObjectUid() != null && h.getObjectUid().equals(CATALOG_DOC)))
                .isTrue();
    }
}
