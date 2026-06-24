package com.ideaqr.gateway.domain.enums;

/**
 * The kind of entity a polymorphic reference points at (Document 22 — used by the
 * universal Consent / Relationship / Delegation entities). Storing a
 * {@code (PartyType, uuid)} pair instead of a typed foreign key is what lets these
 * entities span Identity / Object / Organization / Request without a separate join
 * table per pairing — so new object types never require a new relationship table.
 */
public enum PartyType {

    IDENTITY,
    OBJECT,
    ORGANIZATION,
    REQUEST
}
