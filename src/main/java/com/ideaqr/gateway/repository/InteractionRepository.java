package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.domain.Interaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InteractionRepository extends JpaRepository<Interaction, UUID> {

    /** Used by the guest-merge flow to re-point a guest identity's interactions. */
    List<Interaction> findByIdentityUid(UUID identityUid);
}
