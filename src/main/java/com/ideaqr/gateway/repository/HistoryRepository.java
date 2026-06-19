package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.domain.History;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Append-only journal access. Reads are ordered newest-first for the audit views;
 * there are intentionally no update/delete helpers — history is immutable.
 */
public interface HistoryRepository extends JpaRepository<History, UUID> {

    List<History> findAllByOrderByCreatedAtDesc();

    List<History> findByIdentityUidOrderByCreatedAtDesc(UUID identityUid);

    /** Used by the guest-merge flow to re-point a guest identity's history. */
    List<History> findByIdentityUid(UUID identityUid);
}
