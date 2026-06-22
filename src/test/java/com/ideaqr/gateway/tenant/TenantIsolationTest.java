package com.ideaqr.gateway.tenant;

import com.ideaqr.gateway.domain.User;
import com.ideaqr.gateway.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves hard tenant isolation at the data layer (audit 5.3, the step-5 requirement):
 * with the tenant filter set to tenant A, {@code findAll()} returns ONLY tenant A's
 * rows — tenant B's are physically excluded by the generated SQL, not by application
 * code. The two demo tenants are the seeded "retail" (admin) and "hospital" (doctor)
 * organisations.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TenantIsolationTest {

    @Autowired
    private UserRepository userRepository;

    @PersistenceContext
    private EntityManager em;

    @Test
    void findAllIsHardScopedToTheCurrentTenant() {
        // Unscoped read (filter not enabled) to discover the two tenant ids.
        List<User> all = userRepository.findAll();
        UUID retailTenant = tenantOf(all, "admin");
        UUID hospitalTenant = tenantOf(all, "doctor");
        assertThat(retailTenant).isNotNull();
        assertThat(hospitalTenant).isNotNull();
        assertThat(retailTenant).isNotEqualTo(hospitalTenant);

        Session session = em.unwrap(Session.class);

        // --- Admin A (retail tenant) ---
        session.enableFilter("tenantFilter").setParameter("tenantId", retailTenant);
        em.clear();
        List<User> tenantA = userRepository.findAll();
        assertThat(tenantA).isNotEmpty();
        assertThat(tenantA).allMatch(u -> retailTenant.equals(u.getTenantId()));
        assertThat(tenantA).extracting(User::getUsername).contains("admin").doesNotContain("doctor");

        // --- Admin B (hospital tenant) — must never see tenant A ---
        session.disableFilter("tenantFilter");
        session.enableFilter("tenantFilter").setParameter("tenantId", hospitalTenant);
        em.clear();
        List<User> tenantB = userRepository.findAll();
        assertThat(tenantB).isNotEmpty();
        assertThat(tenantB).allMatch(u -> hospitalTenant.equals(u.getTenantId()));
        assertThat(tenantB).extracting(User::getUsername).contains("doctor").doesNotContain("admin");
    }

    private UUID tenantOf(List<User> users, String username) {
        return users.stream()
                .filter(u -> username.equals(u.getUsername()))
                .findFirst().map(User::getTenantId).orElse(null);
    }
}
