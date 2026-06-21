package com.ideaqr.gateway.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Materializes the deferred {@link CsrfToken} on every request so the
 * {@code XSRF-TOKEN} cookie is actually written to the response. The SPA reads that
 * cookie and echoes it back in the {@code X-XSRF-TOKEN} header on state-changing
 * calls (audit 4.7). Without this, Spring Security 6's lazy token would only be
 * issued the first time a handler touches it.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken != null) {
            // Touching the value triggers CookieCsrfTokenRepository to emit Set-Cookie.
            csrfToken.getToken();
        }
        chain.doFilter(request, response);
    }
}
