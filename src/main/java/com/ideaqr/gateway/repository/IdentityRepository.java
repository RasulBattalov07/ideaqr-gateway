package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.enums.IdentityType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface IdentityRepository extends JpaRepository<Identity, UUID> {

    List<Identity> findByIdentityType(IdentityType identityType);

    long countByIdentityType(IdentityType identityType);

    /**
     * Existence check across ALL tenants (native, filter-bypassing). Ownership transfer must
     * be able to verify a recipient from another tenant (audit H-3: no orphaned owners) —
     * a citizen legitimately hands an object to an org-tenant specialist. An exact-uid
     * existence probe is not enumeration; no identity data is returned.
     */
    @org.springframework.data.jpa.repository.Query(
            value = "select count(*) from identities where identity_uid = :uid",
            nativeQuery = true)
    long countByIdentityUidAnyTenant(
            @org.springframework.data.repository.query.Param("uid") UUID identityUid);
}
