package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.domain.Assignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface AssignmentRepository extends JpaRepository<Assignment, UUID> {

    /** Navigates the {@code Assignment → Identity} association (audit 3.6); name kept. */
    @Query("select a from Assignment a where a.identity.identityUid = :identityUid")
    List<Assignment> findByIdentityUid(@Param("identityUid") UUID identityUid);
}
