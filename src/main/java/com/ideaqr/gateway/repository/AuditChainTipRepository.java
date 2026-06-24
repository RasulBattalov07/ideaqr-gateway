package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.domain.AuditChainTip;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

/**
 * Access to the single-row audit chain tip (audit H-1). {@link #lockTip()} takes a
 * {@code PESSIMISTIC_WRITE} lock (SQL {@code SELECT ... FOR UPDATE}) so concurrent
 * journal appends serialize at the database and the hash chain stays linear.
 */
public interface AuditChainTipRepository extends JpaRepository<AuditChainTip, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AuditChainTip t where t.id = 1")
    Optional<AuditChainTip> lockTip();
}
