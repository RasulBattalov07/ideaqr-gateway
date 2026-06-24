package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.domain.Policy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Read access to the Policy catalog (Document 22 — foundation, no engine). */
public interface PolicyRepository extends JpaRepository<Policy, UUID> {

    List<Policy> findAllByOrderByCodeAsc();
}
