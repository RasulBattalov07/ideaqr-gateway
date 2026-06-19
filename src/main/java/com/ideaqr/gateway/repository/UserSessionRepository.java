package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.domain.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {

    Optional<UserSession> findByIdentityUid(UUID identityUid);
}
