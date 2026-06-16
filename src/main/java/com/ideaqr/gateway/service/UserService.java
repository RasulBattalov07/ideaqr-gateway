package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.Qr;
import com.ideaqr.gateway.domain.User;
import com.ideaqr.gateway.domain.enums.EmploymentStatus;
import com.ideaqr.gateway.domain.enums.HistoryEventType;
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
    private final PasswordEncoder passwordEncoder;

    // --- Profession keys -------------------------------------------------
    public static final String PROFESSION_DOCTOR = "DOCTOR";
    public static final String PROFESSION_RETAIL_ADMIN = "RETAIL_ADMIN";
    public static final String PROFESSION_INSPECTOR = "INSPECTOR";
    public static final String PROFESSION_CITIZEN = "CITIZEN";

    @Transactional
    public User register(RegistrationRequest request) {
        String username = request.getUsername().trim();
        if (userRepository.existsByUsername(username)) {
            throw new UsernameAlreadyExistsException("Имя пользователя «" + username + "» уже занято.");
        }

        String professionKey = normalizeProfession(request.getProfession());
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
                .employmentStatus(parseEmployment(request.getEmploymentStatus()))
                .profession(professionKey)
                .admin(profile.admin())
                .identityUid(identity.getIdentityUid())
                .build();
        user = userRepository.save(user);

        // 4. Append registration to the immutable history.
        auditService.record(identity.getIdentityUid(), null, HistoryEventType.USER_REGISTERED,
                "Зарегистрирован пользователь «" + username + "» (" + professionLabel(professionKey) + ")");

        return user;
    }

    public User findByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("Пользователь не найден: " + username));
    }

    public Identity identityOf(User user) {
        return identityService.findById(user.getIdentityUid());
    }

    public CurrentUserResponse buildCurrentUser(User user) {
        Identity identity = identityService.findById(user.getIdentityUid());
        Set<String> roleNames = identity.getRoles().stream()
                .map(Enum::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return CurrentUserResponse.builder()
                .authenticated(true)
                .username(user.getUsername())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .profession(user.getProfession())
                .professionLabel(professionLabel(user.getProfession()))
                .employmentStatus(user.getEmploymentStatus().name())
                .admin(user.isAdmin())
                .identityUid(identity.getIdentityUid().toString())
                .primaryQrUid(identity.getPrimaryQrUid() != null ? identity.getPrimaryQrUid().toString() : null)
                .trustLevel(identity.getTrustLevel())
                .roles(roleNames)
                .build();
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
            case PROFESSION_CITIZEN -> "Гражданин";
            default -> "Гражданин";
        };
    }

    private String normalizeProfession(String raw) {
        if (raw == null || raw.isBlank()) {
            return PROFESSION_CITIZEN;
        }
        String key = raw.trim().toUpperCase(Locale.ROOT);
        return switch (key) {
            case PROFESSION_DOCTOR, PROFESSION_RETAIL_ADMIN, PROFESSION_INSPECTOR, PROFESSION_CITIZEN -> key;
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
