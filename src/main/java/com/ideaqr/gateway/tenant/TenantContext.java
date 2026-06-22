package com.ideaqr.gateway.tenant;

import java.util.UUID;

/**
 * Per-request tenant context (audit 5.3). Holds the tenant (organisation) the
 * current thread is acting for, in a {@link ThreadLocal}. The Hibernate tenant
 * filter is enabled from this value, and new rows are stamped from it — so one
 * customer can never physically read or write another customer's data.
 *
 * <p>The context is <b>opt-in</b>: when no tenant is set the filter is not enabled,
 * which keeps system/bootstrap operations (e.g. resolving a user during login,
 * data seeding) able to run unscoped. New rows created without a tenant are stamped
 * with the {@link #PUBLIC_TENANT} so they remain addressable.</p>
 */
public final class TenantContext {

    /** Fixed tenant for non-organisation data: citizens, guests and system rows. */
    public static final UUID PUBLIC_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void setTenantId(UUID tenantId) {
        CURRENT.set(tenantId);
    }

    public static UUID getTenantId() {
        return CURRENT.get();
    }

    /** The tenant to stamp on a new row: the current tenant, or the public tenant. */
    public static UUID currentOrPublic() {
        UUID tenantId = CURRENT.get();
        return tenantId != null ? tenantId : PUBLIC_TENANT;
    }

    /** Must be called at the end of every request to avoid thread-pool leakage. */
    public static void clear() {
        CURRENT.remove();
    }
}
