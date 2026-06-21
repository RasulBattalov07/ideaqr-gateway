package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    /**
     * Resolve the account behind an identity (e.g. to show a scanned profile's name).
     * Navigates the {@code User → Identity} association (audit 3.6); the method name is
     * kept so existing callers are unchanged.
     */
    @Query("select u from User u where u.identity.identityUid = :identityUid")
    Optional<User> findByIdentityUid(@Param("identityUid") UUID identityUid);
}
