package com.ideaqr.gateway.config;

import com.ideaqr.gateway.domain.Organization;
import com.ideaqr.gateway.domain.User;
import com.ideaqr.gateway.dto.RegistrationRequest;
import com.ideaqr.gateway.repository.UserRepository;
import com.ideaqr.gateway.service.ModuleService;
import com.ideaqr.gateway.service.OrganizationService;
import com.ideaqr.gateway.service.UserService;
import com.ideaqr.gateway.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.UUID;

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
    private final ModuleService moduleService;

    @Override
    public void run(String... args) {
        // Base MVP modules (spheres of interaction shown in the admin panel).
        moduleService.ensureModule("USERS", "Пользователи", "Цифровые личности и их основные QR.");
        moduleService.ensureModule("SERVICES", "Услуги и быт", "Бытовые услуги по заявке (Request → Decision → Interaction).");
        moduleService.ensureModule("GOODS", "Товары", "Карточки товаров, происхождение, цены и отзывы.");
        moduleService.ensureModule("MEDICINE", "Медицина", "Медицинские услуги и доступ к карте по разрешению.");
        moduleService.ensureModule("INFRASTRUCTURE", "Инфраструктура", "Объекты инфраструктуры и доступ по политикам.");

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
            // Stamp every row created for this account with the org's tenant (audit 5.3),
            // so each demo organisation becomes its own isolated tenant. Citizens with no
            // organisation fall to the public tenant.
            UUID tenant = org != null ? org.getOrganizationUid() : TenantContext.PUBLIC_TENANT;
            TenantContext.setTenantId(tenant);
            try {
                // Trusted server-side path: only DataSeeder may mint specialist/admin
                // accounts. The public register() endpoint always yields CITIZEN (audit 4.1/4.2).
                user = userService.provisionTrusted(request, profession);
            } finally {
                TenantContext.clear();
            }
            log.info("DataSeeder: provisioned demo account '{}' in tenant {}.", username, tenant);
        }
        if (user != null && org != null && workRole != null) {
            organizationService.ensureMembership(user.getIdentityUid(), org.getOrganizationUid(), workRole);
        }
    }
}
