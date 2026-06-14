package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.entity.Decision;
import com.ideaqr.gateway.enums.DecisionResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface DecisionRepository extends JpaRepository<Decision, UUID> {

    Optional<Decision> findByRequestUid(UUID requestUid);

    boolean existsByRequestUidAndResult(UUID requestUid, DecisionResult result);
}
