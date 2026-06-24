package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.domain.Relationship;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Foundation access for the universal {@code Relationship} graph (Document 22). */
public interface RelationshipRepository extends JpaRepository<Relationship, UUID> {

    List<Relationship> findByFromUidOrderByCreatedAtDesc(UUID fromUid);

    List<Relationship> findByToUidOrderByCreatedAtDesc(UUID toUid);
}
