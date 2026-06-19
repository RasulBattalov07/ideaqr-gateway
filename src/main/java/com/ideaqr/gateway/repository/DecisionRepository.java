package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.domain.Decision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DecisionRepository extends JpaRepository<Decision, UUID> {

    /** Used by the guest-merge flow to re-point a guest identity's decisions. */
    List<Decision> findByIdentityUid(UUID identityUid);
}
