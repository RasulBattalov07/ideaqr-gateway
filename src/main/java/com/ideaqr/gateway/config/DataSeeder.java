package com.ideaqr.gateway.config;

import com.ideaqr.gateway.domain.Organization;
import com.ideaqr.gateway.domain.User;
import com.ideaqr.gateway.dto.RegistrationRequest;
import com.ideaqr.gateway.repository.UserRepository;
import com.ideaqr.gateway.service.OrganizationService;
import com.ideaqr.gateway.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds demo organizations, the four demo accounts and their memberships on first
 * run (idempotent). The four demo registry objects used by the quick scenarios are
 * served by {@link com.ideaqr.gateway.service.RegistryClient} and need no seeding.
 * No real personal data is stored — credentials are throwaway demo values
 * documented in the README.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final UserService userService;
    private final OrganizationService organizationService;

    @Override
    public void run(String... args) {
        Organization hospital = organizationService.ensureOrganization("Городская больница", "MEDICAL");
        Organization grid = organizationService.ensureOrganization("АО «Астана-РЭК»", "INFRASTRUCTURE");
        Organization retail = organizationService.ensureOrganization("IDEAQR Retail", "RETAIL");

        seed("admin", "Admin123!", "Аружан", "Сапарова", "EMPLOYED",
                UserService.PROFESSION_RETAIL_ADMIN, retail, "RETAIL_ADMIN");
        seed("doctor", "Doctor123!", "Санжар", "Ким", "EMPLOYED",
                UserService.PROFESSION_DOCTOR, hospital, "DOCTOR");
        seed("inspector", "Inspect123!", "Гульнара", "Ахметова", "EMPLOYED",
                UserService.PROFESSION_INSPECTOR, grid, "INSPECTOR");
        seed("citizen", "Citizen123!", "Дамир", "Оспанов", "UNEMPLOYED",
                UserService.PROFESSION_CITIZEN, null, null);
    }

    private void seed(String username, String password, String firstName, String lastName,
                      String employmentStatus, String profession, Organization org, String workRole) {
        User user;
        if (userRepository.existsByUsername(username)) {
            user = userRepository.findByUsername(username).orElse(null);
        } else {
            RegistrationRequest request = new RegistrationRequest();
            request.setUsername(username);
            request.setPassword(password);
            request.setFirstName(firstName);
            request.setLastName(lastName);
            request.setEmploymentStatus(employmentStatus);
            request.setProfession(profession);
            user = userService.register(request);
            log.info("DataSeeder: provisioned demo account '{}'.", username);
        }
        if (user != null && org != null && workRole != null) {
            organizationService.ensureMembership(user.getIdentityUid(), org.getOrganizationUid(), workRole);
        }
    }
}
