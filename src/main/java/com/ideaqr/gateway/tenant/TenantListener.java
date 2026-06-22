package com.ideaqr.gateway.tenant;

import jakarta.persistence.PrePersist;

/**
 * Stamps the tenant on every new {@link TenantScoped} row from the current
 * {@link TenantContext} (audit 5.3), so callers never have to set it by hand and
 * cannot accidentally create cross-tenant data. Runs at {@code @PrePersist}, so the
 * tenant is fixed at insert time and (for the append-only journal) never mutated
 * afterwards. Uses only the static context — no Spring injection — so it works in
 * plain JPA slices too.
 */
public class TenantListener {

    @PrePersist
    public void stampTenant(Object entity) {
        if (entity instanceof TenantScoped scoped && scoped.getTenantId() == null) {
            scoped.setTenantId(TenantContext.currentOrPublic());
        }
    }
}
