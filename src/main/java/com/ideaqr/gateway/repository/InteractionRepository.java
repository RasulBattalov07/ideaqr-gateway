package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.entity.Interaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface InteractionRepository extends JpaRepository<Interaction, UUID> {

    List<Interaction> findByIdentityUidOrderByCreatedAtDesc(UUID identityUid);
}
