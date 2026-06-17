package com.ideaqr.gateway.config;

import com.ideaqr.gateway.dto.RegistrationRequest;
import com.ideaqr.gateway.repository.UserRepository;
import com.ideaqr.gateway.service.UserService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds a small set of demonstration accounts on first start so investors can
 * sign in immediately and walk every scenario. Accounts are created only when
 * absent, so the seeder is safe to run against the persistent database on every
 * restart.
 */
@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final UserService userService;
    private final UserRepository userRepository;

    @Override
    public void run(String... args) {
        seed("admin", "Admin123!", "Аружан", "Сапарова", "EMPLOYED", UserService.PROFESSION_RETAIL_ADMIN);
        seed("doctor", "Doctor123!", "Санжар", "Ким", "EMPLOYED", UserService.PROFESSION_DOCTOR);
        seed("inspector", "Inspect123!", "Гульнара", "Ахметова", "EMPLOYED", UserService.PROFESSION_INSPECTOR);
        seed("citizen", "Citizen123!", "Дамир", "Оспанов", "EMPLOYED", UserService.PROFESSION_CITIZEN);
    }

    private void seed(String username, String password, String firstName, String lastName,
                      String employmentStatus, String profession) {
        if (userRepository.existsByUsername(username)) {
            return;
        }
        RegistrationRequest req = new RegistrationRequest();
        req.setUsername(username);
        req.setPassword(password);
        req.setFirstName(firstName);
        req.setLastName(lastName);
        req.setEmploymentStatus(employmentStatus);
        req.setProfession(profession);
        userService.register(req);
        log.info("Создан демонстрационный аккаунт: {} ({})", username, profession);
    }
}
