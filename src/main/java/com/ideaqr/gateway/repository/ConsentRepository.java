package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.domain.Consent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Foundation access for {@code Consent} (Document 22). No consent engine in the MVP. */
public interface ConsentRepository extends JpaRepository<Consent, UUID> {

    List<Consent> findByGrantorUidOrderByCreatedAtDesc(UUID grantorUid);

    List<Consent> findByGranteeUidOrderByCreatedAtDesc(UUID granteeUid);
}
