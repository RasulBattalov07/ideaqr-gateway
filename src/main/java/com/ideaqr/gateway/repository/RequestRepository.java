package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.domain.RequestRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RequestRepository extends JpaRepository<RequestRecord, UUID> {

    /** Used by the guest-merge flow to re-point a guest identity's requests. */
    List<RequestRecord> findByIdentityUid(UUID identityUid);
}
