package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.domain.RequestRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RequestRepository extends JpaRepository<RequestRecord, UUID> {
}
