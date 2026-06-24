package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.domain.Complaint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ComplaintRepository extends JpaRepository<Complaint, UUID> {

    List<Complaint> findAllByOrderByCreatedAtDesc();

    /** Server-paginated complaint list for the admin panel (audit M-2). */
    Page<Complaint> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<Complaint> findByIdentityUidOrderByCreatedAtDesc(UUID identityUid);
}
