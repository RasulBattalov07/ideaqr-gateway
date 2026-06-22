package com.ideaqr.gateway.tenant;

import java.util.UUID;

/**
 * Marker for entities that are isolated per tenant (audit 5.3). Every implementing
 * entity carries a {@code tenant_id} column, is auto-stamped on insert by
 * {@link TenantListener}, and is auto-filtered on read by the Hibernate
 * {@code tenantFilter}.
 */
public interface TenantScoped {

    UUID getTenantId();

    void setTenantId(UUID tenantId);
}
