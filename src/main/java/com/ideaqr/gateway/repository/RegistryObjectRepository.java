package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.domain.RegistryObject;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RegistryObjectRepository extends JpaRepository<RegistryObject, UUID> {

    Optional<RegistryObject> findByObjectUid(String objectUid);

    boolean existsByObjectUid(String objectUid);

    List<RegistryObject> findByCreatedByIdentityUidOrderByCreatedAtDesc(UUID createdByIdentityUid);

    List<RegistryObject> findAllByOrderByCreatedAtDesc();
}
