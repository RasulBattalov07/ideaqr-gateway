package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.Organization;
import com.ideaqr.gateway.domain.OrganizationMembership;
import com.ideaqr.gateway.domain.UserSession;
import com.ideaqr.gateway.domain.enums.HistoryEventType;
import com.ideaqr.gateway.domain.enums.SessionMode;
import com.ideaqr.gateway.repository.UserSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Manages the active session context (personal vs working mode). Switching to
 * working mode keeps the same identity and QR — only the context changes — and is
 * recorded as an event in the immutable journal with a notification.
 */
@Service
@RequiredArgsConstructor
public class SessionService {

    private final UserSessionRepository sessionRepository;
    private final OrganizationService organizationService;
    private final AuditService auditService;
    private final NotificationService notificationService;

    @Transactional
    public UserSession current(UUID identityUid) {
        return sessionRepository.findByIdentityUid(identityUid)
                .orElseGet(() -> sessionRepository.save(UserSession.builder()
                        .identityUid(identityUid)
                        .mode(SessionMode.PERSONAL)
                        .build()));
    }

    @Transactional
    public UserSession enterWorkingMode(Identity identity, UUID organizationUid) {
        List<OrganizationMembership> memberships = organizationService.membershipsOf(identity.getIdentityUid());
        if (memberships.isEmpty()) {
            throw new IllegalStateException("Рабочий режим недоступен: вы не связаны ни с одной организацией.");
        }
        OrganizationMembership chosen;
        if (organizationUid != null) {
            chosen = memberships.stream()
                    .filter(m -> m.getOrganizationUid().equals(organizationUid))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Вы не состоите в выбранной организации."));
        } else {
            chosen = memberships.get(0);
        }

        UserSession session = current(identity.getIdentityUid());
        session.setMode(SessionMode.WORKING);
        session.setActiveOrganizationUid(chosen.getOrganizationUid());
        session.setActiveRole(chosen.getWorkRole());
        session.setUpdatedAt(LocalDateTime.now());
        session = sessionRepository.save(session);

        Organization org = organizationService.find(chosen.getOrganizationUid());
        String orgName = org != null ? org.getName() : "организация";
        auditService.record(identity.getIdentityUid(), null, HistoryEventType.WORKING_MODE_ACTIVATED,
                "Активирован рабочий режим: " + orgName + " (" + chosen.getWorkRole() + ").");
        notificationService.notify(identity.getIdentityUid(), "WORKING_MODE",
                "Рабочий режим активирован: " + orgName + ".");
        return session;
    }

    @Transactional
    public UserSession exitWorkingMode(Identity identity) {
        UserSession session = current(identity.getIdentityUid());
        if (session.getMode() == SessionMode.WORKING) {
            auditService.record(identity.getIdentityUid(), null, HistoryEventType.WORKING_MODE_DEACTIVATED,
                    "Завершён рабочий режим. Возврат в личный режим.");
            notificationService.notify(identity.getIdentityUid(), "WORKING_MODE", "Рабочий режим завершён.");
        }
        session.setMode(SessionMode.PERSONAL);
        session.setActiveOrganizationUid(null);
        session.setActiveRole(null);
        session.setUpdatedAt(LocalDateTime.now());
        return sessionRepository.save(session);
    }
}
