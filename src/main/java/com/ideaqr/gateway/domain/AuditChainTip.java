package com.ideaqr.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Single-row pointer to the tip of the audit hash chain (audit H-1). Every append
 * locks this row {@code FOR UPDATE} before reading {@link #lastHash} / {@link #lastSeq}
 * and writing the new values back, so concurrent appends serialize at the database and
 * the chain can never fork — the previous app-level {@code synchronized} released its
 * lock before the surrounding transaction committed, which let two threads link to the
 * same predecessor.
 *
 * <p>There is exactly one row, {@code id = 1}, seeded by migration {@code V7}.</p>
 */
@Entity
@Table(name = "audit_chain_tip")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditChainTip {

    /** The id of the singleton tip row. */
    public static final long SINGLETON_ID = 1L;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** {@code entry_hash} of the last appended entry ({@code "GENESIS"} when empty). */
    @Column(name = "last_hash", nullable = false, length = 64)
    private String lastHash;

    /** Highest chain sequence number assigned so far (0 when empty). */
    @Column(name = "last_seq", nullable = false)
    private long lastSeq;
}
