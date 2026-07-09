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

    /**
     * Display name for the requester of an access/consent request addressed to the caller.
     * Native on purpose: it bypasses the tenant {@code @Filter} so a patient can see WHO is
     * asking for their medical card even when that requester (a hospital doctor) belongs to
     * another tenant — you cannot consent blindly. Scoped to one resolved identity, so it is
     * not a cross-tenant enumeration.
     */
    @Query(value = "select trim(first_name || ' ' || last_name) from users where identity_uid = :uid",
            nativeQuery = true)
    Optional<String> findDisplayNameByIdentityUid(@Param("uid") UUID uid);

    /**
     * Resolve a transfer recipient by username across ALL tenants (native, filter-bypassing):
     * handing an object over works like a phone-number transfer — the recipient may live in
     * any tenant. Returns only the identity uid (no profile data), so it is an existence
     * probe no stronger than the public registration username check.
     */
    @Query(value = "select cast(identity_uid as varchar(36)) from users where username = :username",
            nativeQuery = true)
    Optional<String> findIdentityUidByUsernameAnyTenant(@Param("username") String username);
}
