package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.domain.History;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * History is append-only: the application only inserts and reads. No update or
 * delete operations are ever invoked against this repository.
 */
public interface HistoryRepository extends JpaRepository<History, UUID> {

    List<History> findTop50ByOrderByCreatedAtDesc();

    List<History> findByIdentityUidOrderByCreatedAtDesc(UUID identityUid);
}
