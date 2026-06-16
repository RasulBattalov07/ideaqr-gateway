package com.ideaqr.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Stage 3 security. Real authentication backed by BCrypt and persistent
 * accounts. The application is a single-page app talking to a JSON API, so:
 *
 * <ul>
 *   <li>form login posts to {@code /login} and returns JSON (200 / 401) instead
 *       of redirecting;</li>
 *   <li>unauthenticated API calls receive a clean 401 (via {@link HttpStatusEntryPoint})
 *       which the SPA uses to show the login screen;</li>
 *   <li>{@code /api/admin/**} requires {@code ROLE_ADMIN} (the governance panel),
 *       everything else under {@code /api/**} requires authentication.</li>
 * </ul>
 *
 * <p><b>Demo note:</b> CSRF protection is disabled to keep the prototype's
 * fetch-based flows simple, and the H2 console is permitted with same-origin
 * framing. Both would be revisited before any production deployment.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        // Public shell + static assets.
                        .requestMatchers(
                                "/", "/index.html", "/styles.css", "/app.js",
                                "/favicon.ico", "/assets/**", "/error").permitAll()
                        // Public endpoints.
                        .requestMatchers("/api/health", "/api/auth/register").permitAll()
                        .requestMatchers("/login", "/logout").permitAll()
                        // H2 console (demo only).
                        .requestMatchers("/h2-console/**").permitAll()
                        // Governance panel is admin-only.
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // Everything else under the API requires a session.
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().authenticated())
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

    private void writeJson(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(body);
    }
}
