package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.domain.PlatformModule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlatformModuleRepository extends JpaRepository<PlatformModule, UUID> {

    List<PlatformModule> findAllByOrderByCreatedAtAsc();

    Optional<PlatformModule> findByCode(String code);

    boolean existsByCode(String code);
}
