package com.ideaqr.gateway.web;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.User;
import com.ideaqr.gateway.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Resolves the domain {@link User} and {@link Identity} behind the current
 * Spring Security {@link Authentication}. Keeps the controllers free of
 * repeated principal-unwrapping logic.
 */
@Component
@RequiredArgsConstructor
public class AuthSupport {

    private final UserService userService;

    public User requireUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getName())) {
            throw new IllegalStateException("Требуется аутентификация");
        }
        return userService.findByUsername(authentication.getName());
    }

    public Identity requireIdentity(Authentication authentication) {
        return userService.identityOf(requireUser(authentication));
    }
}
