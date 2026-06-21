package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.User;
import com.ideaqr.gateway.domain.enums.EventType;
import com.ideaqr.gateway.domain.enums.HistoryEventType;
import com.ideaqr.gateway.domain.enums.IdentityStatus;
import com.ideaqr.gateway.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.LinkedHashSet;

/**
 * Administrator user-management operations: block / unblock an account, change a
 * user's access level (promote to admin / demote to user) and reset a password.
 *
 * <p>Every action is appended to the immutable History and the unified Event log
 * (the platform's governance trail), and a few guard rails prevent an admin from
 * locking themselves out (no self-block, no self-demotion). Enforcement of a
 * block lives in {@code CustomUserDetailsService} (login) and {@code AuthSupport}
 * (every authenticated request) — this service only flips the state and records
 * the decision.</p>
 */
@Service
@RequiredArgsConstructor
public class UserAdminService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PW_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    private final UserRepository userRepository;
    private final IdentityService identityService;
    private final UserService userService;
    private final AuditService auditService;
    private final EventService eventService;
    private final PasswordEncoder passwordEncoder;
    private final SessionRegistry sessionRegistry;

    /** Block (ban) an account. The target cannot log in or call the API afterwards. */
    @Transactional
    public User block(String actingAdminUsername, String targetUsername, String reason) {
        User target = require(targetUsername);
        guardNotSelf(actingAdminUsername, targetUsername, "Нельзя заблокировать собственный аккаунт.");
        if (target.isBlocked()) {
            throw new IllegalStateException("Пользователь уже заблокирован.");
        }
        target.setBlocked(true);
        userRepository.save(target);

        // Reflect the ban in the governance layer too.
        Identity identity = identityService.findById(target.getIdentityUid());
        identity.setStatus(IdentityStatus.SUSPENDED);
        identityService.save(identity);

        String note = "Пользователь «" + targetUsername + "» заблокирован администратором «"
                + actingAdminUsername + "»" + (reason != null && !reason.isBlank() ? ": " + reason.trim() : ".");
        auditService.record(identity.getIdentityUid(), null, HistoryEventType.USER_BLOCKED, note);
        eventService.record(EventType.USER_BLOCKED, identity.getIdentityUid(), note);
        // Kill any live session immediately so the ban takes effect now (audit 3.9).
        revokeSessions(targetUsername);
        return target;
    }

    /** Lift a block (unban) and restore the identity to active. */
    @Transactional
    public User unblock(String actingAdminUsername, String targetUsername) {
        User target = require(targetUsername);
        if (!target.isBlocked()) {
            throw new IllegalStateException("Пользователь не заблокирован.");
        }
        target.setBlocked(false);
        userRepository.save(target);

        Identity identity = identityService.findById(target.getIdentityUid());
        identity.setStatus(IdentityStatus.ACTIVE);
        identityService.save(identity);

        String note = "Пользователь «" + targetUsername + "» разблокирован администратором «"
                + actingAdminUsername + "».";
        auditService.record(identity.getIdentityUid(), null, HistoryEventType.USER_UNBLOCKED, note);
        eventService.record(EventType.USER_UNBLOCKED, identity.getIdentityUid(), note);
        return target;
    }

    /**
     * Change the access level: {@code makeAdmin=true} promotes the user to the
     * governance panel (ROLE_ADMIN), {@code false} demotes them to ROLE_USER. The
     * user's active sessions are revoked so the change takes effect immediately
     * (audit 3.9), not at their next login.
     */
    @Transactional
    public User setAdmin(String actingAdminUsername, String targetUsername, boolean makeAdmin) {
        User target = require(targetUsername);
        if (!makeAdmin) {
            guardNotSelf(actingAdminUsername, targetUsername, "Нельзя понизить собственные права администратора.");
        }
        if (target.isAdmin() == makeAdmin) {
            throw new IllegalStateException(makeAdmin
                    ? "Пользователь уже является администратором."
                    : "Пользователь уже имеет роль обычного пользователя.");
        }
        target.setAdmin(makeAdmin);
        userRepository.save(target);

        String role = makeAdmin ? "ADMIN" : "USER";
        String note = "Роль пользователя «" + targetUsername + "» изменена на " + role
                + " администратором «" + actingAdminUsername + "».";
        auditService.record(target.getIdentityUid(), null, HistoryEventType.USER_ROLE_CHANGED, note);
        eventService.record(EventType.USER_ROLE_CHANGED, target.getIdentityUid(), note);
        // Revoke active sessions so the new authority level applies immediately,
        // not at next login (audit 3.9).
        revokeSessions(targetUsername);
        return target;
    }

    /**
     * Assign a profession to a user — the privileged counterpart to public sign-up,
     * which can only ever create a CITIZEN (audit 4.1 / 4.2). Re-derives the identity's
     * business roles, trust level and the admin flag from the profession, so granting
     * {@code DOCTOR} actually unlocks medical access for that account. The user's
     * active sessions are revoked so the change applies immediately (audit 3.9).
     */
    @Transactional
    public User setProfession(String actingAdminUsername, String targetUsername, String professionKey) {
        User target = require(targetUsername);
        String normalized = userService.normalizeProfession(professionKey);
        UserService.ProfessionProfile profile = userService.profileFor(normalized);

        Identity identity = identityService.findById(target.getIdentityUid());
        identity.setRoles(new LinkedHashSet<>(profile.roles()));
        identity.setTrustLevel(profile.trustLevel());
        identityService.save(identity);

        target.setProfession(normalized);
        target.setAdmin(profile.admin());
        userRepository.save(target);

        String note = "Профессия пользователя «" + targetUsername + "» изменена на «"
                + userService.professionLabel(normalized) + "» администратором «" + actingAdminUsername + "».";
        auditService.record(target.getIdentityUid(), null, HistoryEventType.USER_ROLE_CHANGED, note);
        eventService.record(EventType.USER_ROLE_CHANGED, target.getIdentityUid(), note);
        revokeSessions(targetUsername);
        return target;
    }

    /**
     * Reset a user's password to a freshly generated temporary one. The plaintext
     * is returned <b>once</b> so the admin can hand it over; only the BCrypt hash
     * is stored.
     */
    @Transactional
    public String resetPassword(String actingAdminUsername, String targetUsername) {
        User target = require(targetUsername);
        String tempPassword = generateTempPassword();
        target.setPasswordHash(passwordEncoder.encode(tempPassword));
        userRepository.save(target);

        String note = "Пароль пользователя «" + targetUsername + "» сброшен администратором «"
                + actingAdminUsername + "».";
        auditService.record(target.getIdentityUid(), null, HistoryEventType.USER_PASSWORD_RESET, note);
        eventService.record(EventType.USER_PASSWORD_RESET, target.getIdentityUid(), note);
        return tempPassword;
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private User require(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден: " + username));
    }

    private void guardNotSelf(String actingAdminUsername, String targetUsername, String message) {
        if (actingAdminUsername != null && actingAdminUsername.equalsIgnoreCase(targetUsername)) {
            throw new IllegalStateException(message);
        }
    }

    /**
     * Immediately expire every active session of {@code username} (audit 3.9). The
     * next request on an expired session is rejected, forcing re-authentication, so a
     * ban or privilege change takes effect now instead of at the user's next login.
     */
    private void revokeSessions(String username) {
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            String name = (principal instanceof UserDetails ud) ? ud.getUsername() : String.valueOf(principal);
            if (username.equalsIgnoreCase(name)) {
                for (SessionInformation info : sessionRegistry.getAllSessions(principal, false)) {
                    info.expireNow();
                }
            }
        }
    }

    private String generateTempPassword() {
        StringBuilder sb = new StringBuilder("Tmp-");
        for (int i = 0; i < 8; i++) {
            sb.append(PW_ALPHABET.charAt(RANDOM.nextInt(PW_ALPHABET.length())));
        }
        return sb.toString();
    }
}
