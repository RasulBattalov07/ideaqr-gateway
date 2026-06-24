package com.ideaqr.gateway.domain.enums;

/**
 * Data sensitivity level of an object's payload (Document 22 — Data Classification).
 * Architectural foundation only: the level is recorded and surfaced, the field-level
 * enforcement engine is a future phase. The MVP uses {@code PUBLIC} / {@code RESTRICTED};
 * {@code CONFIDENTIAL} / {@code SECRET} are reserved for medicine / government tiers.
 *
 * <p>Extensibility (платформенный принцип): the level is <b>derived</b> from
 * {@link ObjectCategory} by {@link #forCategory}, so a new category maps to a level here
 * in one place — no core logic changes.</p>
 */
public enum DataClassification {

    PUBLIC,
    RESTRICTED,
    CONFIDENTIAL,
    SECRET;

    /** Default classification for a category. Unknown/unmapped categories fail safe to RESTRICTED. */
    public static DataClassification forCategory(ObjectCategory category) {
        if (category == null) {
            return RESTRICTED;
        }
        return switch (category) {
            case MEDICAL -> CONFIDENTIAL;
            case INFRASTRUCTURE -> RESTRICTED;
            case RETAIL, ECO, GENERAL -> PUBLIC;
            default -> RESTRICTED;
        };
    }
}
