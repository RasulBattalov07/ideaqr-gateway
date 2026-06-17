package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.domain.Decision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DecisionRepository extends JpaRepository<Decision, UUID> {
}
