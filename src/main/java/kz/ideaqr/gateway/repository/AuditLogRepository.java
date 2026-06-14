package kz.ideaqr.gateway.repository;

import kz.ideaqr.gateway.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findAllByOrderByTimestampDesc();

    List<AuditLog> findBySubjectIdOrderByTimestampDesc(String subjectId);

    List<AuditLog> findByAccessStatusOrderByTimestampDesc(String accessStatus);

    long countByAccessStatus(String accessStatus);
}
