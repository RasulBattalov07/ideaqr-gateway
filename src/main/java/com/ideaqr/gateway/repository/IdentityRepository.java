package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.domain.Identity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface IdentityRepository extends JpaRepository<Identity, UUID> {
}
