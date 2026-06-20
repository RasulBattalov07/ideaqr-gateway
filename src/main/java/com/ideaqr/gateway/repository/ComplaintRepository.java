package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.domain.Complaint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ComplaintRepository extends JpaRepository<Complaint, UUID> {

    List<Complaint> findAllByOrderByCreatedAtDesc();

    List<Complaint> findByIdentityUidOrderByCreatedAtDesc(UUID identityUid);
}
