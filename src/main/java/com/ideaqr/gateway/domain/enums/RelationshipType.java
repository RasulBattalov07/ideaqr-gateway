package com.ideaqr.gateway.domain.enums;

/**
 * Semantic kind of a {@code Relationship} (Document 22 — Relationship). A small,
 * open vocabulary for the ties the platform understands between participants
 * (doctor↔patient, employee↔organization, owner↔object, organization↔product …).
 * New kinds are added as enum values without touching the universal relationship
 * table (платформенный принцип расширяемости).
 */
public enum RelationshipType {

    DOCTOR_PATIENT,
    EMPLOYEE_ORGANIZATION,
    OWNER_OBJECT,
    CLIENT_COMPANY,
    ORGANIZATION_PRODUCT,
    ORGANIZATION_SERVICE,
    GUEST_ALIAS,
    GENERIC
}
