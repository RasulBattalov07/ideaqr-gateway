package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.Qr;
import com.ideaqr.gateway.domain.User;
import com.ideaqr.gateway.domain.enums.EmploymentStatus;
import com.ideaqr.gateway.domain.enums.HistoryEventType;
import com.ideaqr.gateway.domain.enums.IdentityType;
import com.ideaqr.gateway.domain.enums.RoleType;
import com.ideaqr.gateway.dto.CurrentUserResponse;
import com.ideaqr.gateway.dto.RegistrationRequest;
import com.ideaqr.gateway.exception.UsernameAlreadyExistsException;
import com.ideaqr.gateway.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Manages user accounts and the registration flow. Registration maps the chosen
 * profession to a set of business roles, a trust level and the administrator
 * flag, provisions the linked {@link Identity} and its permanent primary QR, and
 * stores the BCrypt password hash.
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final IdentityService identityService;
    private final QrService qrService;
    private final AuditService auditService;
    private final EmploymentService employmentService;
    private final PasswordEncoder passwordEncoder;

    // --- Profession keys -------------------------------------------------
    public static final String PROFESSION_DOCTOR = "DOCTOR";
    public static final String PROFESSION_RETAIL_ADMIN = "RETAIL_ADMIN";
    public static final String PROFESSION_INSPECTOR = "INSPECTOR";
    public static final String PROFESSION_CITIZEN = "CITIZEN";
    public static final String PROFESSION_PHARMACIST = "PHARMACIST";
    public static final String PROFESSION_SELLER = "SELLER";
    public static final String PROFESSION_SERVICE_OPERATOR = "SERVICE_OPERATOR";

    /**
     * Public self-service registration. <b>Security (audit 4.1 / 4.2):</b> a public
     * sign-up NEVER derives a privileged role from client input. Whatever profession
     * the form sends, an anonymous registration always provisions a plain
     * {@code CITIZEN}. Specialist and administrator roles are granted afterwards by a
     * privileged path only — {@link #provisionTrusted} (server-side seeding) or an
     * administrator via {@code POST /api/admin/users/{username}/profession}. This
     * closes "register as admin" and "self-assign DOCTOR / INSPECTOR".
     */
    @Transactional
    public User register(RegistrationRequest request) {
        return provision(request, PROFESSION_CITIZEN, false);
    }

    /**
     * Trusted account provisioning used by the server itself (e.g. {@code DataSeeder})
     * to create the demo specialist / administrator accounts. This is the only way a
     * non-citizen profession can be assigned at creation time and is never reachable
     * from the public API.
     */
    @Transactional
    public User provisionTrusted(RegistrationRequest request, String professionKey) {
        return provision(request, normalizeProfession(professionKey), true);
    }

    private User provision(RegistrationRequest request, String requestedProfession, boolean trusted) {
        String username = request.getUsername().trim();
        if (userRepository.existsByUsername(username)) {
            throw new UsernameAlreadyExistsException("Имя пользователя «" + username + "» уже занято.");
        }

        EmploymentStatus employment = parseEmployment(request.getEmploymentStatus());
        // Only a trusted caller may set a non-citizen profession. Public sign-ups are
        // pinned to CITIZEN; an UNEMPLOYED person is CITIZEN regardless of the caller.
        String professionKey = trusted ? requestedProfession : PROFESSION_CITIZEN;
        if (employment == EmploymentStatus.UNEMPLOYED) {
            professionKey = PROFESSION_CITIZEN;
        }
        ProfessionProfile profile = profileFor(professionKey);

        // 1. Provision the linked identity (Stage 2 layer).
        Identity identity = identityService.createPrimaryIdentity(profile.roles(), profile.trustLevel());

        // 2. Mint the permanent primary QR and link it back to the identity.
        Qr primaryQr = qrService.createPrimaryQr(identity);
        identity.setPrimaryQrUid(primaryQr.getQrUid());
        identityService.save(identity);

        // 3. Persist the account with a BCrypt password hash.
        User user = User.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .firstName(request.getFirstName().trim())
                .lastName(request.getLastName().trim())
                .employmentStatus(employment)
                .profession(professionKey)
                .admin(profile.admin())
                .identity(identity)
                .build();
        user = userRepository.save(user);

        // 4. Append registration to the immutable history.
        auditService.record(identity.getIdentityUid(), null, HistoryEventType.USER_REGISTERED,
                "Зарегистрирован пользователь «" + username + "» (" + professionLabel(professionKey) + ")");

        // 5. Employment intent (public sign-up only). "Трудоустроен" + a chosen employer raises a
        // verification request the company admin must approve — it grants no role by itself, so the
        // choice now carries real business meaning instead of being inert. Trusted/seeded accounts
        // wire their own ACTIVE memberships and are skipped.
        if (!trusted && employment == EmploymentStatus.EMPLOYED) {
            employmentService.submitRequest(identity.getIdentityUid(), request.getOrganizationUid());
        }

        return user;
    }

    /** A provisioned guest account plus the one-time merge token for its browser. */
    public record GuestAccount(User user, String mergeToken) {}

    /**
     * Provision a guest account + guest identity (no registration). The guest can
     * scan and view; later, on registration, the guest's history can be merged into
     * the new primary identity via {@link GuestService} — but only by presenting the
     * returned one-time merge token (audit 4.6).
     */
    @Transactional
    public GuestAccount createGuestAccount() {
        IdentityService.GuestProvision provision = identityService.createGuestIdentity();
        Identity identity = provision.identity();
        Qr primaryQr = qrService.createPrimaryQr(identity);
        identity.setPrimaryQrUid(primaryQr.getQrUid());
        identityService.save(identity);

        String suffix = identity.getIdentityUid().toString().substring(0, 8);
        String username = "guest-" + suffix;
        User user = User.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(UUID.randomUUID().toString()))
                .firstName("Гость")
                .lastName(suffix.toUpperCase(Locale.ROOT))
                .employmentStatus(EmploymentStatus.UNEMPLOYED)
                .profession(PROFESSION_CITIZEN)
                .admin(false)
                .identity(identity)
                .build();
        user = userRepository.save(user);

        auditService.record(identity.getIdentityUid(), null, HistoryEventType.GUEST_CREATED,
                "Создан гостевой доступ «" + username + "».");
        return new GuestAccount(user, provision.mergeToken());
    }

    public User findByUsername(String username) {
        // Audit M-3: keep the message generic — never reflect the looked-up username back.
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("Пользователь не найден."));
    }

    public Identity identityOf(User user) {
        return identityService.findById(user.getIdentityUid());
    }

    public CurrentUserResponse buildCurrentUser(User user) {
        Identity identity = identityService.findById(user.getIdentityUid());
        Set<String> roleNames = identity.getRoles().stream()
                .map(Enum::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        EmploymentService.Affiliation affiliation = employmentService.affiliationOf(identity.getIdentityUid());
        return CurrentUserResponse.builder()
                .authenticated(true)
                .username(user.getUsername())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .profession(user.getProfession())
                .professionLabel(professionLabel(user.getProfession()))
                .employmentStatus(user.getEmploymentStatus().name())
                .employmentState(affiliation.state())
                .organizationName(affiliation.organizationName())
                .admin(user.isAdmin())
                .identityUid(identity.getIdentityUid().toString())
                .primaryQrUid(identity.getPrimaryQrUid() != null ? identity.getPrimaryQrUid().toString() : null)
                .trustLevel(identity.getTrustLevel())
                .riskScore(identity.getRiskScore())
                .guest(identity.getIdentityType() == IdentityType.GUEST)
                .mustChangePassword(user.isMustChangePassword())
                .roles(roleNames)
                .build();
    }

    /**
     * User-initiated password change (audit 1.7 / 4.9). Verifies the current password,
     * stores the new BCrypt hash and clears any forced-change flag. The new password's
     * strength is enforced by bean validation on {@code ChangePasswordRequest}.
     */
    @Transactional
    public void changePassword(User user, String currentPassword, String newPassword) {
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("Текущий пароль указан неверно.");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("Новый пароль должен отличаться от текущего.");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        userRepository.save(user);

        auditService.record(user.getIdentityUid(), null, HistoryEventType.USER_PASSWORD_CHANGED,
                "Пользователь «" + user.getUsername() + "» сменил пароль.");
    }

    // ------------------------------------------------------------------
    //  Profession mapping
    // ------------------------------------------------------------------

    /** Roles + trust + admin flag derived from a profession key. */
    public record ProfessionProfile(Set<RoleType> roles, int trustLevel, boolean admin) {
    }

    public ProfessionProfile profileFor(String professionKey) {
        return switch (normalizeProfession(professionKey)) {
            case PROFESSION_DOCTOR -> new ProfessionProfile(
                    new LinkedHashSet<>(Set.of(RoleType.DOCTOR, RoleType.CITIZEN)),
                    IdentityService.TRUST_SPECIALIST, false);
            case PROFESSION_RETAIL_ADMIN -> new ProfessionProfile(
                    new LinkedHashSet<>(Set.of(RoleType.RETAIL_ADMIN, RoleType.ADMIN, RoleType.CITIZEN)),
                    IdentityService.TRUST_VERIFIED, true);
            case PROFESSION_INSPECTOR -> new ProfessionProfile(
                    new LinkedHashSet<>(Set.of(RoleType.INSPECTOR, RoleType.ENGINEER, RoleType.CITIZEN)),
                    IdentityService.TRUST_VERIFIED, false);
            case PROFESSION_PHARMACIST -> new ProfessionProfile(
                    new LinkedHashSet<>(Set.of(RoleType.PHARMACIST, RoleType.CITIZEN)),
                    IdentityService.TRUST_SPECIALIST, false);
            case PROFESSION_SELLER -> new ProfessionProfile(
                    new LinkedHashSet<>(Set.of(RoleType.SELLER, RoleType.CITIZEN)),
                    IdentityService.TRUST_CITIZEN, false);
            case PROFESSION_SERVICE_OPERATOR -> new ProfessionProfile(
                    new LinkedHashSet<>(Set.of(RoleType.SERVICE_OPERATOR, RoleType.CITIZEN)),
                    IdentityService.TRUST_CITIZEN, false);
            default -> new ProfessionProfile(
                    new LinkedHashSet<>(Set.of(RoleType.CITIZEN)),
                    IdentityService.TRUST_CITIZEN, false);
        };
    }

    public String professionLabel(String professionKey) {
        return switch (normalizeProfession(professionKey)) {
            case PROFESSION_DOCTOR -> "Врач";
            case PROFESSION_RETAIL_ADMIN -> "Администратор торговли";
            case PROFESSION_INSPECTOR -> "Инспектор инфраструктуры";
            case PROFESSION_PHARMACIST -> "Фармацевт";
            case PROFESSION_SELLER -> "Продавец";
            case PROFESSION_SERVICE_OPERATOR -> "Оператор услуг";
            case PROFESSION_CITIZEN -> "Гражданин";
            default -> "Гражданин";
        };
    }

    public String normalizeProfession(String raw) {
        if (raw == null || raw.isBlank()) {
            return PROFESSION_CITIZEN;
        }
        String key = raw.trim().toUpperCase(Locale.ROOT);
        return switch (key) {
            case PROFESSION_DOCTOR, PROFESSION_RETAIL_ADMIN, PROFESSION_INSPECTOR,
                 PROFESSION_PHARMACIST, PROFESSION_SELLER, PROFESSION_SERVICE_OPERATOR,
                 PROFESSION_CITIZEN -> key;
            default -> PROFESSION_CITIZEN;
        };
    }

    private EmploymentStatus parseEmployment(String raw) {
        try {
            return EmploymentStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return EmploymentStatus.UNEMPLOYED;
        }
    }
}
