package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.domain.RegistryObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RegistryObjectRepository extends JpaRepository<RegistryObject, UUID> {

    Optional<RegistryObject> findByObjectUid(String objectUid);

    /**
     * Resolve an object by its exact identifier across ALL tenants. Native on purpose: it
     * bypasses the tenant {@code @Filter} so a citizen's dossier (public tenant) resolves for
     * a hospital-tenant doctor or a police-tenant officer. Callers that serve the result to a
     * user MUST re-apply the tenant guard by hand (see {@code RegistryClient.resolve}) — an
     * exact-uid lookup is not enumeration, but cross-tenant private objects stay invisible.
     */
    @org.springframework.data.jpa.repository.Query(
            value = "select * from registry_objects where object_uid = :uid",
            nativeQuery = true)
    Optional<RegistryObject> findByObjectUidAnyTenant(
            @org.springframework.data.repository.query.Param("uid") String objectUid);

    boolean existsByObjectUid(String objectUid);

    List<RegistryObject> findByCreatedByIdentityUidOrderByCreatedAtDesc(UUID createdByIdentityUid);

    /** Objects an identity currently OWNS (post-transfer) — feeds the "Мои объекты" view. */
    List<RegistryObject> findByOwnerIdentityUidOrderByCreatedAtDesc(UUID ownerIdentityUid);

    List<RegistryObject> findAllByOrderByCreatedAtDesc();

    /** Server-paginated object list for the admin panel (audit M-2). */
    Page<RegistryObject> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
