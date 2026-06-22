package com.ideaqr.gateway.tenant;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.hibernate.Session;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Transparently enables the Hibernate tenant filter (audit 5.3) immediately before
 * every repository call, on the session that call will actually use. Enabling it here
 * — rather than once per request — guarantees it lands on the live session regardless
 * of how the transaction/EntityManager is bound, so a tenant's reads can never reach
 * another tenant's rows.
 *
 * <p>When no tenant is in context (system tasks, the login bootstrap that must read a
 * user to learn their tenant) the filter is left off and the read runs unscoped.</p>
 */
@Aspect
@Component
public class TenantFilterAspect {

    @PersistenceContext
    private EntityManager entityManager;

    @Before("execution(* com.ideaqr.gateway.repository..*(..))")
    public void enableTenantFilter() {
        UUID tenant = TenantContext.getTenantId();
        if (tenant == null) {
            return;
        }
        try {
            entityManager.unwrap(Session.class)
                    .enableFilter("tenantFilter")
                    .setParameter("tenantId", tenant);
        } catch (Exception ignored) {
            // No session bound (e.g. called outside a request/transaction) — nothing to scope.
        }
    }
}
