package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.entity.QrCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface QrCodeRepository extends JpaRepository<QrCode, UUID> {

    List<QrCode> findByIdentityUid(UUID identityUid);

    Optional<QrCode> findByIdentityUidAndActiveTrue(UUID identityUid);
}
