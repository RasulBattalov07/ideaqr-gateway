package com.ideaqr.gateway.domain.enums;

/**
 * Domain roles attached to an {@code Identity}. One identity may hold several
 * roles simultaneously (e.g. {@code DOCTOR} + {@code CITIZEN}).
 *
 * <p>These are <b>business</b> roles used by the policy / context engine
 * ({@code ValidationService}) to decide whether an action is permitted. They are
 * distinct from Spring Security URL authorities (ROLE_ADMIN / ROLE_USER), which
 * only gate which interface a user can reach.</p>
 */
public enum RoleType {
    CITIZEN,
    DOCTOR,
    ENGINEER,
    INSPECTOR,
    RETAIL_ADMIN,
    OBJECT_OWNER,
    ORG_STAFF,
    ADMIN,

    // Specialised roles (minimal-access principle): each sees only what its
    // function needs once the data owner has granted access.
    PHARMACIST,       // prescriptions and appointments only — not the full medical card
    SELLER,           // order / delivery data only
    SERVICE_OPERATOR, // dispatcher of household service orders (assigns executors)
    POLICE,           // legal dossier (criminal record, fines) only — on duty, working hours

    // Трёхсторонние сценарии (V10): универсальный исполнитель бытовых заявок
    // (сантехник/электрик/уборка — одна роль без разделения) и кассир магазина
    // (видит корзину/оплаченные товары клиента по его личному QR).
    EXECUTOR,
    CASHIER
}
