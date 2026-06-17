package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.domain.Assignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AssignmentRepository extends JpaRepository<Assignment, UUID> {
}
