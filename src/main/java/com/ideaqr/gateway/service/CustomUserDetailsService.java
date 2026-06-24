package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.User;
import com.ideaqr.gateway.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Loads accounts for Spring Security. The single granted authority decides which
 * interface the user reaches: {@code ROLE_ADMIN} → the governance panel,
 * {@code ROLE_USER} → the citizen terminal. Fine-grained business rules are
 * handled separately by the identity's {@code RoleType} set in the rules engine.
 */
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Пользователь не найден."));

        String authority = user.isAdmin() ? "ROLE_ADMIN" : "ROLE_USER";

        // A blocked account is reported as locked, so Spring Security rejects the
        // login attempt before the password is even checked (LockedException → 401).
        return org.springframework.security.core.userdetails.User
                .withUsername(user.getUsername())
                .password(user.getPasswordHash())
                .authorities(List.of(new SimpleGrantedAuthority(authority)))
                .accountExpired(false)
                .accountLocked(user.isBlocked())
                .credentialsExpired(false)
                .disabled(false)
                .build();
    }
}
