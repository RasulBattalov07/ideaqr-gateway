package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.domain.Interaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface InteractionRepository extends JpaRepository<Interaction, UUID> {
}
