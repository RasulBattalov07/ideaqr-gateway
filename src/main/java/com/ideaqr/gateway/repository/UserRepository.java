package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    /** Resolve the account behind an identity (e.g. to show a scanned profile's name). */
    Optional<User> findByIdentityUid(UUID identityUid);
}
