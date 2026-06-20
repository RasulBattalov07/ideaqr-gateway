package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.enums.IdentityType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface IdentityRepository extends JpaRepository<Identity, UUID> {

    List<Identity> findByIdentityType(IdentityType identityType);

    long countByIdentityType(IdentityType identityType);
}
