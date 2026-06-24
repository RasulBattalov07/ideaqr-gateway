package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.domain.Delegation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Foundation access for {@code Delegation} (Document 22). No delegation engine in the MVP. */
public interface DelegationRepository extends JpaRepository<Delegation, UUID> {

    List<Delegation> findByDelegatorUidOrderByCreatedAtDesc(UUID delegatorUid);

    List<Delegation> findByDelegateeUidOrderByCreatedAtDesc(UUID delegateeUid);
}
