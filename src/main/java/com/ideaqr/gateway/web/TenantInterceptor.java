package com.ideaqr.gateway.web;

import com.ideaqr.gateway.domain.User;
import com.ideaqr.gateway.repository.UserRepository;
import com.ideaqr.gateway.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Establishes the per-request tenant (audit 5.3): it resolves the authenticated
 * caller's tenant from their account and records it in {@link TenantContext}. From
 * there, {@code TenantFilterAspect} enables the Hibernate filter before each query
 * and {@code TenantListener} stamps it on each insert — so a client has no physical
 * way to read or write another tenant's rows.
 *
 * <p>The bootstrap lookup below runs while the context is still empty, so it is
 * intentionally unscoped — you must read a user to learn their tenant.</p>
 */
@Component
@RequiredArgsConstructor
public class TenantInterceptor implements HandlerInterceptor {

    private final UserRepository userRepository;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return true; // unauthenticated: no tenant scope; any writes default to PUBLIC
        }
        User user = userRepository.findByUsername(auth.getName()).orElse(null);
        if (user != null && user.getTenantId() != null) {
            TenantContext.setTenantId(user.getTenantId());
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        TenantContext.clear(); // never leak the tenant onto a pooled thread
    }
}
