package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.Notification;
import com.ideaqr.gateway.domain.enums.NotificationStatus;
import com.ideaqr.gateway.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * The notification center. Raises in-app notifications on noteworthy events and
 * lets a user list them and mark them read.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;

    @Transactional
    public Notification notify(UUID identityUid, String type, String title) {
        return notificationRepository.save(Notification.builder()
                .identityUid(identityUid)
                .notificationType(type)
                .title(title)
                .status(NotificationStatus.NEW)
                .build());
    }

    public List<Notification> list(UUID identityUid) {
        return notificationRepository.findByIdentityUidOrderByCreatedAtDesc(identityUid);
    }

    public long unreadCount(UUID identityUid) {
        return notificationRepository.countByIdentityUidAndStatus(identityUid, NotificationStatus.NEW);
    }

    @Transactional
    public boolean markRead(UUID identityUid, UUID notificationUid) {
        return notificationRepository.findById(notificationUid)
                .filter(n -> n.getIdentityUid().equals(identityUid))
                .map(n -> {
                    n.setStatus(NotificationStatus.READ);
                    notificationRepository.save(n);
                    return true;
                })
                .orElse(false);
    }
}
