package com.ideaqr.gateway.web;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.User;
import com.ideaqr.gateway.domain.enums.HistoryEventType;
import com.ideaqr.gateway.dto.ApiResponse;
import com.ideaqr.gateway.dto.RegistrationRequest;
import com.ideaqr.gateway.exception.AccountBlockedException;
import com.ideaqr.gateway.exception.UsernameAlreadyExistsException;
import com.ideaqr.gateway.repository.UserRepository;
import com.ideaqr.gateway.service.AuditService;
import com.ideaqr.gateway.service.CitizenDossierService;
import com.ideaqr.gateway.service.MockEgovService;
import com.ideaqr.gateway.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * «Умная регистрация» через mock-eGov (Phase 2). Пользователь вводит ТОЛЬКО номер
 * телефона: {@code lookup} находит гражданина в имитации госбазы, {@code register}
 * по подтверждению «Да, это я» создаёт аккаунт + личность + полный пакет данных
 * (медкарта, правовое досье, визитка) и сразу открывает сессию. Повторный вход —
 * {@code login} по номеру + SMS-коду (демо-код фиксированный: {@value #DEMO_OTP}).
 *
 * <p>Пароля у eGov-аккаунта нет (генерируется случайный, никому не показывается) —
 * вход только по коду, как в реальных гос-приложениях. Все три эндпоинта публичные,
 * закрыты жёсткими per-IP лимитами в {@code RateLimitingFilter} и CSRF-токеном.</p>
 */
@RestController
@RequestMapping("/api/auth/egov")
@RequiredArgsConstructor
public class EgovAuthController {

    /** Демо-код «из SMS». Показывается подсказкой в интерфейсе — интеграции с SMS-шлюзом нет. */
    public static final String DEMO_OTP = "1234";

    private final MockEgovService egovService;
    private final UserService userService;
    private final CitizenDossierService dossierService;
    private final UserRepository userRepository;
    private final UserDetailsService userDetailsService;
    private final AuditService auditService;

    private final HttpSessionSecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    /** Шаг 1: по номеру телефона «находим» гражданина в eGov и показываем плашку «Это вы?». */
    @PostMapping("/lookup")
    public ResponseEntity<Map<String, Object>> lookup(@RequestBody Map<String, String> body) {
        String phone = egovService.normalizePhone(body != null ? body.get("phone") : null);
        MockEgovService.EgovPerson person = egovService.lookup(phone);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("phone", phone);
        res.put("phoneDisplay", egovService.displayPhone(phone));
        res.put("alreadyRegistered", userRepository.existsByUsername(phone));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("fullName", person.firstName() + " " + person.lastName());
        p.put("firstName", person.firstName());
        p.put("lastName", person.lastName());
        p.put("iin", person.iin());
        p.put("birthDate", person.birthDateDisplay());
        p.put("address", "г. " + person.city() + ", " + person.address());
        p.put("gender", person.gender());
        res.put("person", p);
        return ResponseEntity.ok(res);
    }

    /**
     * Шаг 2: «Да, это я» — создаём аккаунт (username = номер), личность, полный пакет
     * данных из eGov-снимка и сразу открываем сессию. Профессия всегда CITIZEN —
     * публичный путь никогда не выдаёт привилегированную роль (audit 4.1/4.2).
     */
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, String> body,
                                                        HttpServletRequest request,
                                                        HttpServletResponse response) {
        String phone = egovService.normalizePhone(body != null ? body.get("phone") : null);
        if (userRepository.existsByUsername(phone)) {
            throw new UsernameAlreadyExistsException(
                    "Этот номер уже зарегистрирован. Войдите по SMS-коду (демо-код: " + DEMO_OTP + ").");
        }
        MockEgovService.EgovPerson person = egovService.lookup(phone);

        RegistrationRequest r = new RegistrationRequest();
        r.setUsername(phone);
        // Вход только по SMS-коду: пароль случайный и никому не сообщается.
        r.setPassword("Egov-" + UUID.randomUUID() + "-1");
        r.setFirstName(person.firstName());
        r.setLastName(person.lastName());
        r.setEmploymentStatus("UNEMPLOYED");
        r.setProfession(UserService.PROFESSION_CITIZEN);
        User user = userService.register(r);
        Identity identity = userService.identityOf(user);

        dossierService.ensureFor(user, identity, person);
        auditService.record(identity.getIdentityUid(), null, HistoryEventType.IDENTITY_VERIFIED,
                "Личность подтверждена через eGov (демо): " + person.firstName() + " " + person.lastName()
                        + ", ИИН " + person.iin().charAt(0) + "•••••••••" + person.iin().substring(10));

        establishSession(user.getUsername(), request, response);
        return ResponseEntity.status(HttpStatus.CREATED).body(sessionResponse(user,
                "Личность подтверждена. Цифровой профиль создан — добро пожаловать!"));
    }

    /** Повторный вход по номеру + SMS-коду (демо-код {@value #DEMO_OTP}). */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body,
                                   HttpServletRequest request,
                                   HttpServletResponse response) {
        String phone = egovService.normalizePhone(body != null ? body.get("phone") : null);
        String code = body != null && body.get("code") != null ? body.get("code").trim() : "";
        User user = userRepository.findByUsername(phone).orElse(null);
        if (user == null || !DEMO_OTP.equals(code)) {
            // Единое сообщение: не раскрываем, зарегистрирован ли номер (audit M-3).
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("Номер не найден или SMS-код неверен."));
        }
        if (user.isBlocked()) {
            throw new AccountBlockedException("Аккаунт заблокирован администратором.");
        }
        establishSession(user.getUsername(), request, response);
        return ResponseEntity.ok(sessionResponse(user, "Вход выполнен."));
    }

    // ------------------------------------------------------------------

    /**
     * Программный вход: тот же результат, что у formLogin — контекст в Spring Session,
     * смена идентификатора сессии (защита от фиксации), JSON-ответ.
     */
    private void establishSession(String username, HttpServletRequest request, HttpServletResponse response) {
        UserDetails details = userDetailsService.loadUserByUsername(username);
        UsernamePasswordAuthenticationToken auth =
                UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities());
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
        request.getSession(true);
        request.changeSessionId();
        securityContextRepository.saveContext(ctx, request, response);
    }

    private Map<String, Object> sessionResponse(User user, String message) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("message", message);
        res.put("user", userService.buildCurrentUser(user));
        return res;
    }
}
