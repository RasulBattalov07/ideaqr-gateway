package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.entity.History;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface HistoryRepository extends JpaRepository<History, UUID> {

    List<History> findByInteractionUidOrderByCreatedAtDesc(UUID interactionUid);

    List<History> findAllByOrderByCreatedAtDesc();
}
