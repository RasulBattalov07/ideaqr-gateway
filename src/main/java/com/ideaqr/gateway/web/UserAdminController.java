package com.ideaqr.gateway.web;

import com.ideaqr.gateway.domain.User;
import com.ideaqr.gateway.dto.ApiResponse;
import com.ideaqr.gateway.service.UserAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Map;

/**
 * Administrator user-management actions (ban / unban, change access level, reset
 * password). Restricted to {@code ROLE_ADMIN} by the security configuration
 * ({@code /api/admin/**}). The read-only user list lives in {@link AdminController}
 * ({@code GET /api/admin/users}); this controller carries the write actions.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")  // method-level guard beyond the URL matcher (audit 3.8)
public class UserAdminController {

    private final UserAdminService userAdminService;
    private final AuthSupport authSupport;

    @PostMapping("/users/{username}/block")
    public ResponseEntity<ApiResponse> block(@PathVariable String username,
                                             @RequestBody(required = false) Map<String, Object> body,
                                             Authentication authentication) {
        String admin = authSupport.requireUser(authentication).getUsername();
        User user = userAdminService.block(admin, username, str(body, "reason"));
        return ResponseEntity.ok(ApiResponse.ok("Пользователь заблокирован.")
                .with("username", user.getUsername())
                .with("blocked", user.isBlocked()));
    }

    @PostMapping("/users/{username}/unblock")
    public ResponseEntity<ApiResponse> unblock(@PathVariable String username, Authentication authentication) {
        String admin = authSupport.requireUser(authentication).getUsername();
        User user = userAdminService.unblock(admin, username);
        return ResponseEntity.ok(ApiResponse.ok("Пользователь разблокирован.")
                .with("username", user.getUsername())
                .with("blocked", user.isBlocked()));
    }

    /**
     * Change access level. Body accepts either {@code {"admin": true|false}} or
     * {@code {"role": "ADMIN"|"USER"}}.
     */
    @PostMapping("/users/{username}/role")
    public ResponseEntity<ApiResponse> role(@PathVariable String username,
                                            @RequestBody(required = false) Map<String, Object> body,
                                            Authentication authentication) {
        String admin = authSupport.requireUser(authentication).getUsername();
        boolean makeAdmin = resolveMakeAdmin(body);
        User user = userAdminService.setAdmin(admin, username, makeAdmin);
        return ResponseEntity.ok(ApiResponse.ok("Уровень доступа обновлён. Активные сессии пользователя завершены.")
                .with("username", user.getUsername())
                .with("admin", user.isAdmin()));
    }

    /**
     * Assign a profession (and its derived roles / trust / admin flag) to a user.
     * This is the privileged path that grants specialist or administrator access —
     * public registration can only create a CITIZEN. Body: {@code {"profession": "DOCTOR"}}.
     */
    @PostMapping("/users/{username}/profession")
    public ResponseEntity<ApiResponse> profession(@PathVariable String username,
                                                  @RequestBody(required = false) Map<String, Object> body,
                                                  Authentication authentication) {
        String admin = authSupport.requireUser(authentication).getUsername();
        String profession = str(body, "profession");
        if (profession == null || profession.isBlank()) {
            throw new IllegalArgumentException("Укажите профессию.");
        }
        User user = userAdminService.setProfession(admin, username, profession);
        return ResponseEntity.ok(ApiResponse.ok("Профессия обновлена. Активные сессии пользователя завершены.")
                .with("username", user.getUsername())
                .with("admin", user.isAdmin())
                .with("profession", user.getProfession()));
    }

    @PostMapping("/users/{username}/reset-password")
    public ResponseEntity<ApiResponse> resetPassword(@PathVariable String username, Authentication authentication) {
        String admin = authSupport.requireUser(authentication).getUsername();
        String temp = userAdminService.resetPassword(admin, username);
        return ResponseEntity.ok(ApiResponse.ok("Пароль сброшен. Передайте временный пароль пользователю.")
                .with("username", username)
                .with("temporaryPassword", temp));
    }

    // ------------------------------------------------------------------

    private boolean resolveMakeAdmin(Map<String, Object> body) {
        if (body == null) {
            throw new IllegalArgumentException("Укажите параметр admin (true/false) или role (ADMIN/USER).");
        }
        Object admin = body.get("admin");
        if (admin instanceof Boolean b) {
            return b;
        }
        if (admin != null) {
            return Boolean.parseBoolean(admin.toString().trim());
        }
        Object role = body.get("role");
        if (role != null) {
            String r = role.toString().trim().toUpperCase(Locale.ROOT);
            if (r.equals("ADMIN")) return true;
            if (r.equals("USER")) return false;
        }
        throw new IllegalArgumentException("Укажите параметр admin (true/false) или role (ADMIN/USER).");
    }

    private String str(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        Object v = body.get(key);
        return v != null ? v.toString() : null;
    }
}
