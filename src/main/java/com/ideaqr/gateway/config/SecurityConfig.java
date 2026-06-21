package com.ideaqr.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.session.HttpSessionEventPublisher;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Application security. Real authentication backed by BCrypt and persistent
 * accounts, with the hardening called out in the audit applied:
 *
 * <ul>
 *   <li><b>CSRF on (4.7):</b> a double-submit cookie token ({@code XSRF-TOKEN}) that
 *       the SPA echoes in the {@code X-XSRF-TOKEN} header, so cookie-session POSTs
 *       can no longer be forged by a third-party site;</li>
 *   <li><b>H2 console gated (4.4):</b> the console is only routable and same-origin
 *       framed when {@code spring.h2.console.enabled} is true (local dev). In prod the
 *       path is not permitted and framing is denied;</li>
 *   <li><b>Security headers (4.10):</b> {@code frame-options: DENY} by default, HSTS,
 *       a strict Content-Security-Policy and a Referrer-Policy;</li>
 *   <li><b>Rate limiting (4.8 / 4.9):</b> a per-IP filter throttles the public
 *       guest / register / login endpoints.</li>
 * </ul>
 *
 * <p>Method-level authorization is enabled ({@link EnableMethodSecurity}) so admin
 * controllers carry a second {@code @PreAuthorize} rubber stamp beyond the URL
 * matcher (audit 3.8).</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** True only on the local dev profile; the prod/postgres profiles set it false. */
    @Value("${spring.h2.console.enabled:false}")
    private boolean h2ConsoleEnabled;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Tracks active authenticated sessions so an administrator can revoke them
     * immediately on a privilege change (audit 3.9) — a demoted admin no longer keeps
     * {@code ROLE_ADMIN} until their next login.
     */
    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /** Publishes session lifecycle events so the {@link SessionRegistry} stays accurate. */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   RateLimitingFilter rateLimitingFilter,
                                                   SessionRegistry sessionRegistry) throws Exception {
        CookieCsrfTokenRepository csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokenRepository.setCookiePath("/");
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        // The SPA reads the raw token from the cookie and returns it verbatim in a
        // header, so opt out of the BREACH per-request token encoding.
        csrfRequestHandler.setCsrfRequestAttributeName(null);

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(csrfRequestHandler)
                        // The dev H2 console posts its own forms; exclude it from CSRF.
                        .ignoringRequestMatchers("/h2-console/**"))
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .addFilterBefore(rateLimitingFilter, UsernamePasswordAuthenticationFilter.class)
                .headers(headers -> {
                    headers.frameOptions(frame -> {
                        if (h2ConsoleEnabled) {
                            frame.sameOrigin();   // dev console renders in a frame
                        } else {
                            frame.deny();         // anti-clickjacking in prod
                        }
                    });
                    headers.httpStrictTransportSecurity(hsts -> hsts
                            .includeSubDomains(true).maxAgeInSeconds(31_536_000));
                    headers.referrerPolicy(rp -> rp.policy(ReferrerPolicy.SAME_ORIGIN));
                    // A strict CSP would break the dev H2 console's inline scripts, so
                    // it is only emitted when the console is off (i.e. in production).
                    if (!h2ConsoleEnabled) {
                        headers.contentSecurityPolicy(csp -> csp.policyDirectives(contentSecurityPolicy()));
                    }
                })
                .sessionManagement(sm -> sm
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        // Register sessions (unlimited concurrency) so they can be
                        // revoked on demand when a user's privileges change (audit 3.9).
                        .maximumSessions(-1).sessionRegistry(sessionRegistry))
                .authorizeHttpRequests(auth -> {
                    // Public shell + self-hosted static assets.
                    auth.requestMatchers(
                            "/", "/index.html", "/styles.css", "/app.js",
                            "/favicon.ico", "/assets/**", "/error").permitAll();
                    // Public endpoints.
                    auth.requestMatchers("/api/health", "/api/auth/register", "/api/auth/guest").permitAll();
                    auth.requestMatchers("/login", "/logout").permitAll();
                    // H2 console is routable only when explicitly enabled (local dev).
                    if (h2ConsoleEnabled) {
                        auth.requestMatchers("/h2-console/**").permitAll();
                    }
                    // Governance panel is admin-only.
                    auth.requestMatchers("/api/admin/**").hasRole("ADMIN");
                    // Everything else under the API requires a session.
                    auth.requestMatchers("/api/**").authenticated();
                    auth.anyRequest().authenticated();
                })
                .formLogin(form -> form
                        .loginProcessingUrl("/login")
                        .usernameParameter("username")
                        .passwordParameter("password")
                        .successHandler((request, response, authentication) ->
                                writeJson(response, HttpServletResponse.SC_OK,
                                        "{\"success\":true,\"message\":\"Вход выполнен\"}"))
                        .failureHandler((request, response, exception) ->
                                writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                                        "{\"success\":false,\"message\":\"Неверное имя пользователя или пароль\"}"))
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessHandler((request, response, authentication) ->
                                writeJson(response, HttpServletResponse.SC_OK,
                                        "{\"success\":true,\"message\":\"Выход выполнен\"}"))
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .permitAll())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));

        return http.build();
    }

    /** Strict, self-host-friendly CSP (all assets and the scanner are same-origin). */
    private String contentSecurityPolicy() {
        return "default-src 'self'; "
                + "base-uri 'self'; "
                + "object-src 'none'; "
                + "frame-ancestors 'none'; "
                + "form-action 'self'; "
                + "img-src 'self' data: blob:; "
                + "media-src 'self' blob:; "
                + "worker-src 'self' blob:; "
                + "script-src 'self'; "
                + "style-src 'self' 'unsafe-inline'; "
                + "font-src 'self'; "
                + "connect-src 'self'";
    }

    private void writeJson(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(body);
    }
}
