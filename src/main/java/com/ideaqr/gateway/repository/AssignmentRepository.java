package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.domain.Assignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AssignmentRepository extends JpaRepository<Assignment, UUID> {

    List<Assignment> findByIdentityUid(UUID identityUid);
}
