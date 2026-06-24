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

    boolean existsByObjectUid(String objectUid);

    List<RegistryObject> findByCreatedByIdentityUidOrderByCreatedAtDesc(UUID createdByIdentityUid);

    List<RegistryObject> findAllByOrderByCreatedAtDesc();

    /** Server-paginated object list for the admin panel (audit M-2). */
    Page<RegistryObject> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
