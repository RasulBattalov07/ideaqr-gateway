package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.entity.Identity;
import com.ideaqr.gateway.enums.IdentityType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface IdentityRepository extends JpaRepository<Identity, UUID> {

    List<Identity> findByIdentityType(IdentityType identityType);
}
