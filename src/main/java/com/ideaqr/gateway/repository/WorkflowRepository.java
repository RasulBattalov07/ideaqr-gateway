package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.domain.Workflow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WorkflowRepository extends JpaRepository<Workflow, UUID> {

    List<Workflow> findByRequestUid(UUID requestUid);

    /** SOS escalation queue for the admin "Тревоги" tab, newest first (not tenant-scoped). */
    List<Workflow> findByWorkflowTypeOrderByCreatedAtDesc(String workflowType);

    long countByWorkflowTypeAndStatus(String workflowType, String status);
}
