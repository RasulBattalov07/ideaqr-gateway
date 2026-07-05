package com.ideaqr.gateway.web;

import com.ideaqr.gateway.domain.Organization;
import com.ideaqr.gateway.domain.User;
import com.ideaqr.gateway.dto.ApiResponse;
import com.ideaqr.gateway.dto.ChangePasswordRequest;
import com.ideaqr.gateway.dto.CurrentUserResponse;
import com.ideaqr.gateway.dto.RegistrationRequest;
import com.ideaqr.gateway.service.OrganizationService;
import com.ideaqr.gateway.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Account endpoints: register a new user and report the current session's
 * profile. Login and logout are handled by Spring Security ({@code /login},
 * {@code /logout}).
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final OrganizationService organizationService;
    private final AuthSupport authSupport;
    private final com.ideaqr.gateway.service.CitizenDossierService citizenDossierService;

    /**
     * Public list of organizations for the sign-up "employer" picker (shown when the
     * applicant chooses «Трудоустроен»). Read-only and unauthenticated by design — it only
     * exposes an organization's id and display name so a new citizen can request to join;
     * approval still rests with that organization's administrator.
     */
    @GetMapping("/organizations")
    public ResponseEntity<List<Map<String, Object>>> organizations() {
        List<Map<String, Object>> rows = organizationService.listOrganizations().stream()
                .map(this::organizationRow).toList();
        return ResponseEntity.ok(rows);
    }

    private Map<String, Object> organizationRow(Organization org) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("organizationUid", org.getOrganizationUid().toString());
        m.put("name", org.getName());
        m.put("type", org.getType());
        return m;
    }

    /**
     * Register a new account. Returns the username and Russian profession label;
     * the SPA then performs an automatic login.
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse> register(@Valid @RequestBody RegistrationRequest request) {
        User user = userService.register(request);
        // Phase 2 (единый QR): каждый гражданин сразу получает цифровой пакет —
        // медкарту, правовое досье и визитку, — на который маршрутизируется его личный QR.
        citizenDossierService.ensureFor(user, userService.identityOf(user), null);
        ApiResponse body = ApiResponse.ok("Регистрация прошла успешно. Выполняется вход…")
                .with("username", user.getUsername())
                .with("admin", user.isAdmin())
                .with("professionLabel", userService.professionLabel(user.getProfession()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /**
     * Profile of the authenticated user. Unauthenticated requests are stopped by
     * the security entry point with 401, which the SPA uses to show the login
     * screen.
     */
    @GetMapping("/me")
    public ResponseEntity<CurrentUserResponse> me(Authentication authentication) {
        User user = authSupport.requireUser(authentication);
        return ResponseEntity.ok(userService.buildCurrentUser(user));
    }

    /**
     * Change the current user's own password (audit 1.7), also used to satisfy a
     * forced change after an admin reset (audit 4.9). Requires the current password.
     */
    @PostMapping("/change-password")
    public ResponseEntity<ApiResponse> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                                      Authentication authentication) {
        User user = authSupport.requireUser(authentication);
        userService.changePassword(user, request.getCurrentPassword(), request.getNewPassword());
        return ResponseEntity.ok(ApiResponse.ok("Пароль изменён."));
    }
}
