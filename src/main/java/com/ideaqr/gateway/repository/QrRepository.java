package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.domain.Qr;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface QrRepository extends JpaRepository<Qr, UUID> {

    Optional<Qr> findByQrValue(String qrValue);
}
