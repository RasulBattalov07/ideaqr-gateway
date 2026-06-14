package com.ideaqr.gateway.config;

import com.ideaqr.gateway.entity.Identity;
import com.ideaqr.gateway.enums.IdentityStatus;
import com.ideaqr.gateway.enums.IdentityType;
import com.ideaqr.gateway.enums.Role;
import com.ideaqr.gateway.repository.IdentityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * Seeds a couple of demo identities on startup so the in-memory H2 deployment is
 * immediately testable. Disabled under the "test" profile.
 */
@Component
@Slf4j
@Profile("!test")
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final IdentityRepository identityRepository;

    @Override
    public void run(String... args) {
        if (identityRepository.count() > 0) {
            return;
        }

        Identity engineerCitizen = Identity.builder()
                .identityUid(UUID.fromString("11111111-1111-1111-1111-111111111111"))
                .identityType(IdentityType.PRIMARY)
                .status(IdentityStatus.ACTIVE)
                .roles(Set.of(Role.ENGINEER, Role.CITIZEN))
                .createdAt(LocalDateTime.now())
                .build();

        Identity doctor = Identity.builder()
                .identityUid(UUID.fromString("22222222-2222-2222-2222-222222222222"))
                .identityType(IdentityType.PRIMARY)
                .status(IdentityStatus.ACTIVE)
                .roles(Set.of(Role.DOCTOR, Role.CITIZEN))
                .createdAt(LocalDateTime.now())
                .build();

        identityRepository.save(engineerCitizen);
        identityRepository.save(doctor);

        log.info("Seeded demo identities: ENGINEER+CITIZEN={}, DOCTOR+CITIZEN={}",
                engineerCitizen.getIdentityUid(), doctor.getIdentityUid());
    }
}
