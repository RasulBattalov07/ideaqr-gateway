package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.domain.Notification;
import com.ideaqr.gateway.domain.enums.NotificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findByIdentityUidOrderByCreatedAtDesc(UUID identityUid);

    long countByIdentityUidAndStatus(UUID identityUid, NotificationStatus status);
}
