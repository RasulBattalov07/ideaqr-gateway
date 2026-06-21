package com.ideaqr.gateway.web;

import com.ideaqr.gateway.domain.User;
import com.ideaqr.gateway.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * Server-side enforcement of a forced password change (audit 4.9). When an account
 * has {@code mustChangePassword} set (after an admin reset), any state-changing API
 * call is rejected with {@code 403} until the user picks a new password — so the
 * control is real, not merely a front-end nag. Reads, logout and the change-password
 * call itself stay open so the user can actually complete the flow.
 */
@Component
@RequiredArgsConstructor
public class PasswordChangeEnforcementInterceptor implements HandlerInterceptor {

    private static final Set<String> ALWAYS_ALLOWED = Set.of(
            "/api/auth/change-password", "/api/auth/me", "/logout");

    private final UserRepository userRepository;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String method = request.getMethod();
        // Reads never mutate state — let them through (the SPA needs /me etc.).
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            return true;
        }
        if (ALWAYS_ALLOWED.contains(request.getRequestURI())) {
            return true;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return true; // unauthenticated — the security layer handles it
        }

        User user = userRepository.findByUsername(auth.getName()).orElse(null);
        if (user != null && user.isMustChangePassword()) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"success\":false,"
                    + "\"message\":\"Сначала смените временный пароль.\","
                    + "\"code\":\"PASSWORD_CHANGE_REQUIRED\"}");
            return false;
        }
        return true;
    }
}
