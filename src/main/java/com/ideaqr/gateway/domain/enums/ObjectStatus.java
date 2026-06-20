package com.ideaqr.gateway.domain.enums;

/**
 * Lifecycle status of a {@link com.ideaqr.gateway.domain.RegistryObject}
 * (OBJECT LIFECYCLE requirement). Every governed object — a product, an
 * apartment, a vehicle, a service, an organisation, an infrastructure asset,
 * etc. — moves through this lifecycle, and every transition is appended to the
 * immutable History so the full change-history of the object is preserved
 * ("Система должна сохранять полную историю изменения объекта").
 *
 * <pre>
 *   CREATED → ACTIVE → MODIFIED → ARCHIVED
 * </pre>
 */
public enum ObjectStatus {
    /** Just minted; the QR exists but the object is not yet in circulation. */
    CREATED,
    /** In active use / circulation — the normal operating state. */
    ACTIVE,
    /** Its data or ownership has been changed at least once. */
    MODIFIED,
    /** Retired from circulation; history is kept, but it is no longer active. */
    ARCHIVED
}
