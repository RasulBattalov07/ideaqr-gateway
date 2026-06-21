package com.ideaqr.gateway.web;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.User;
import com.ideaqr.gateway.dto.ApiResponse;
import com.ideaqr.gateway.dto.CurrentUserResponse;
import com.ideaqr.gateway.service.CustomUserDetailsService;
import com.ideaqr.gateway.service.GuestService;
import com.ideaqr.gateway.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Guest access: enter without registration (a guest identity + session is created),
 * and later merge a guest's history into a registered identity.
 */
@RestController
@RequiredArgsConstructor
public class GuestController {

    private final UserService userService;
    private final CustomUserDetailsService userDetailsService;
    private final GuestService guestService;
    private final AuthSupport authSupport;

    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    /** Public: provision a guest account and start an authenticated session. */
    @PostMapping("/api/auth/guest")
    public ResponseEntity<CurrentUserResponse> guest(HttpServletRequest request, HttpServletResponse response) {
        UserService.GuestAccount guestAccount = userService.createGuestAccount();
        User user = guestAccount.user();
        UserDetails details = userDetailsService.loadUserByUsername(user.getUsername());
        Authentication auth = new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());

        // Rotate the session id on authentication to defeat session fixation (audit 4.10).
        request.getSession(true);
        request.changeSessionId();

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        // Hand the one-time merge token to this browser only; only its hash is stored.
        CurrentUserResponse body = userService.buildCurrentUser(user);
        body.setMergeToken(guestAccount.mergeToken());
        return ResponseEntity.ok(body);
    }

    /**
     * Merge a guest identity's history into the currently authenticated identity.
     * Requires the one-time {@code mergeToken} that was issued to the guest's browser
     * at creation — proof of ownership of the guest session (audit 4.6).
     */
    @PostMapping("/api/v2/guest/merge")
    public ResponseEntity<ApiResponse> merge(@RequestBody Map<String, String> body, Authentication authentication) {
        Identity target = authSupport.requireIdentity(authentication);
        String raw = body != null ? body.get("guestIdentityUid") : null;
        String mergeToken = body != null ? body.get("mergeToken") : null;
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Не указан идентификатор гостевой личности.");
        }
        int moved = guestService.merge(target, UUID.fromString(raw.trim()), mergeToken);
        return ResponseEntity.ok(ApiResponse.ok("История гостя перенесена. Записей: " + moved).with("moved", moved));
    }
}
