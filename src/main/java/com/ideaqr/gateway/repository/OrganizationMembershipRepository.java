package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.domain.OrganizationMembership;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationMembershipRepository extends JpaRepository<OrganizationMembership, UUID> {

    List<OrganizationMembership> findByIdentityUid(UUID identityUid);

    Optional<OrganizationMembership> findByIdentityUidAndOrganizationUid(UUID identityUid, UUID organizationUid);

    /** Employment requests awaiting an admin decision — feeds the admin "Трудоустройство" queue. */
    List<OrganizationMembership> findByStatusOrderByCreatedAtDesc(String status);
}
