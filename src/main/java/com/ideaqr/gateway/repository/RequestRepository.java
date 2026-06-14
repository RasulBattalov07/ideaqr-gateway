package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.entity.RequestRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RequestRepository extends JpaRepository<RequestRecord, UUID> {

    List<RequestRecord> findByIdentityUidOrderByCreatedAtDesc(UUID identityUid);
}
