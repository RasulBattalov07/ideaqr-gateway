package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.domain.History;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Append-only journal access. Reads are ordered newest-first for the audit views;
 * there are intentionally no update/delete helpers — history is immutable.
 */
public interface HistoryRepository extends JpaRepository<History, UUID> {

    List<History> findAllByOrderByCreatedAtDesc();

    /** Server-paginated global journal (admin view) — see audit 3.1. */
    Page<History> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<History> findByIdentityUidOrderByCreatedAtDesc(UUID identityUid);

    /** Journal across an identity and its merged guest aliases (newest first). */
    List<History> findByIdentityUidInOrderByCreatedAtDesc(Collection<UUID> identityUids);

    /** Server-paginated variant of the alias-aware journal. */
    Page<History> findByIdentityUidInOrderByCreatedAtDesc(Collection<UUID> identityUids, Pageable pageable);

    /** Newest entry — the chain tip used to link a new append (audit 4.5). */
    History findTopByOrderByCreatedAtDescHistoryUidDesc();

    /** Whole journal in insert order, for hash-chain verification. */
    List<History> findAllByOrderByCreatedAtAscHistoryUidAsc();

    List<History> findByIdentityUid(UUID identityUid);
}
