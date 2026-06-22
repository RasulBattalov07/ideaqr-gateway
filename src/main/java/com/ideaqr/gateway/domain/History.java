package com.ideaqr.gateway.domain;

import com.ideaqr.gateway.domain.enums.HistoryEventType;
import com.ideaqr.gateway.tenant.TenantListener;
import com.ideaqr.gateway.tenant.TenantScoped;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Append-only audit journal. The application only ever inserts rows here — it
 * never updates or deletes them. Every meaningful event in the pipeline lands
 * in this table, forming the immutable history of the platform.
 *
 * <p><b>Tamper-evidence (audit 4.5):</b> the entity is intentionally <i>setter-free</i>
 * (rows are built once via the builder and never mutated), and each row carries a
 * {@code prevHash}/{@code entryHash} forming a SHA-256 hash chain. Any after-the-fact
 * edit or deletion breaks the chain and is detectable by
 * {@code AuditService.verifyChain()}. The earlier guest-merge flow that rewrote rows
 * has been replaced by an append-only alias (see {@code GuestService}).</p>
 */
@Entity
@Table(name = "histories")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(TenantListener.class)
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class History implements TenantScoped {

    @Id
    @Column(name = "history_uid", nullable = false, updatable = false)
    private UUID historyUid;

    /** Owning tenant — stamped once at insert; the journal stays append-only (audit 5.3). */
    @Column(name = "tenant_id", updatable = false)
    private UUID tenantId;

    @Column(name = "identity_uid", nullable = false)
    private UUID identityUid;

    /** Optional links to the chain elements that produced this event. */
    @Column(name = "request_uid")
    private UUID requestUid;

    @Column(name = "decision_uid")
    private UUID decisionUid;

    @Column(name = "interaction_uid")
    private UUID interactionUid;

    @Column(name = "object_uid", length = 120)
    private String objectUid;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private HistoryEventType eventType;

    @Column(name = "description", length = 500)
    private String description;

    /** SHA-256 of the preceding journal entry ({@code "GENESIS"} for the first row). */
    @Column(name = "prev_hash", length = 64, updatable = false)
    private String prevHash;

    /** SHA-256 over this row's content + {@link #prevHash} — the chain link. */
    @Column(name = "entry_hash", length = 64, updatable = false)
    private String entryHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Tenant setter used only by {@link TenantListener} at insert time. The journal is
     * otherwise setter-free; tenant_id is never mutated after the row is written, so the
     * hash chain (which excludes tenant_id) is unaffected.
     */
    @Override
    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    @PrePersist
    void onCreate() {
        if (historyUid == null) {
            historyUid = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
