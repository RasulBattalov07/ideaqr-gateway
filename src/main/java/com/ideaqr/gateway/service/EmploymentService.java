package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.Organization;
import com.ideaqr.gateway.domain.OrganizationMembership;
import com.ideaqr.gateway.domain.enums.HistoryEventType;
import com.ideaqr.gateway.repository.OrganizationMembershipRepository;
import com.ideaqr.gateway.repository.OrganizationRepository;
import com.ideaqr.gateway.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Gives the registration-time "Трудоустроен / Не трудоустроен" choice real business meaning.
 *
 * <p>A public sign-up never derives a privileged role from client input (audit 4.1/4.2), so the
 * employment flag cannot grant one. Instead, choosing <b>Трудоустроен</b> + an employer raises a
 * verification request: a {@link OrganizationMembership} in the {@link #STATUS_PENDING} state that
 * the employer's administrator must approve. Until then the applicant is an ordinary citizen —
 * {@code OrganizationService.resolveActingOrganization} only ever selects an {@code ACTIVE}
 * membership, so a pending claim governs nothing. Choosing <b>Не трудоустроен</b> creates no
 * membership at all.</p>
 *
 * <p>Approval flips the membership to {@link #STATUS_ACTIVE}: the person is now a verified member
 * of that organization, acts under it in the governance pipeline and can enter working mode for it.
 * Rejection records {@link #STATUS_REJECTED}. Both notify the applicant and append to the immutable
 * journal, reusing the {@code USER_ROLE_CHANGED} event already allowed by the History schema.</p>
 */
@Service
@RequiredArgsConstructor
public class EmploymentService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REJECTED = "REJECTED";

    /** Default work role for a self-service employment claim; a specialist role is still admin-granted. */
    private static final String DEFAULT_WORK_ROLE = "EMPLOYEE";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final OrganizationRepository organizationRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final UserRepository userRepository;

    /** Employment affiliation surfaced on the user's profile: NONE / PENDING / ACTIVE + org name. */
    public record Affiliation(String state, String organizationName) {}

    /** A pending employment request as shown in the admin queue. */
    public record PendingRequest(String membershipUid, String identityUid, String userName,
                                 String username, String organizationUid, String organizationName,
                                 String workRole, String createdAt) {}

    /**
     * Raise an employment-verification request for a freshly registered, EMPLOYED citizen. No-op
     * when the chosen organization is missing/invalid or a membership already exists (idempotent),
     * so a failure here never blocks the registration itself.
     */
    @Transactional
    public void submitRequest(UUID identityUid, String organizationUidRaw) {
        UUID orgUid = parseUuid(organizationUidRaw);
        if (orgUid == null) {
            return;
        }
        Organization org = organizationRepository.findById(orgUid).orElse(null);
        if (org == null) {
            return;
        }
        if (membershipRepository.findByIdentityUidAndOrganizationUid(identityUid, orgUid).isPresent()) {
            return; // already affiliated or already requested
        }
        membershipRepository.save(OrganizationMembership.builder()
                .identityUid(identityUid)
                .organizationUid(orgUid)
                .workRole(DEFAULT_WORK_ROLE)
                .status(STATUS_PENDING)
                .build());
        notificationService.notify(identityUid, "EMPLOYMENT",
                "Заявка на трудоустройство в «" + org.getName()
                        + "» отправлена. Ожидает подтверждения администратора компании.");
        auditService.record(identityUid, null, HistoryEventType.USER_ROLE_CHANGED,
                "Заявка на трудоустройство в «" + org.getName() + "» (ожидает подтверждения).");
    }

    /** The affiliation to show on a profile: an ACTIVE membership wins, else a PENDING one. */
    public Affiliation affiliationOf(UUID identityUid) {
        List<OrganizationMembership> memberships = membershipRepository.findByIdentityUid(identityUid);
        OrganizationMembership active = firstWithStatus(memberships, STATUS_ACTIVE);
        if (active != null) {
            return new Affiliation(STATUS_ACTIVE, orgName(active.getOrganizationUid()));
        }
        OrganizationMembership pending = firstWithStatus(memberships, STATUS_PENDING);
        if (pending != null) {
            return new Affiliation(STATUS_PENDING, orgName(pending.getOrganizationUid()));
        }
        return new Affiliation("NONE", null);
    }

    /** Pending employment requests across organizations (admin runs unscoped), newest first. */
    public List<PendingRequest> pendingRequests() {
        List<PendingRequest> rows = new ArrayList<>();
        for (OrganizationMembership m : membershipRepository.findByStatusOrderByCreatedAtDesc(STATUS_PENDING)) {
            rows.add(new PendingRequest(
                    m.getMembershipUid().toString(),
                    m.getIdentityUid().toString(),
                    userName(m.getIdentityUid()),
                    username(m.getIdentityUid()),
                    m.getOrganizationUid().toString(),
                    orgName(m.getOrganizationUid()),
                    m.getWorkRole(),
                    m.getCreatedAt() != null ? m.getCreatedAt().format(TS) : null));
        }
        return rows;
    }

    public long pendingCount() {
        return membershipRepository.findByStatusOrderByCreatedAtDesc(STATUS_PENDING).size();
    }

    /** Admin confirms the claim → the applicant becomes a verified, ACTIVE member of the organization. */
    @Transactional
    public void approve(UUID membershipUid) {
        OrganizationMembership m = require(membershipUid);
        m.setStatus(STATUS_ACTIVE);
        membershipRepository.save(m);
        String orgName = orgName(m.getOrganizationUid());
        notificationService.notify(m.getIdentityUid(), "EMPLOYMENT",
                "Ваше трудоустройство в «" + orgName + "» подтверждено. Теперь доступен рабочий режим.");
        auditService.record(m.getIdentityUid(), null, HistoryEventType.USER_ROLE_CHANGED,
                "Трудоустройство в «" + orgName + "» подтверждено администратором.");
    }

    /** Admin declines the claim → the membership is marked rejected; the applicant stays a citizen. */
    @Transactional
    public void reject(UUID membershipUid) {
        OrganizationMembership m = require(membershipUid);
        m.setStatus(STATUS_REJECTED);
        membershipRepository.save(m);
        String orgName = orgName(m.getOrganizationUid());
        notificationService.notify(m.getIdentityUid(), "EMPLOYMENT",
                "Заявка на трудоустройство в «" + orgName + "» отклонена администратором.");
        auditService.record(m.getIdentityUid(), null, HistoryEventType.USER_ROLE_CHANGED,
                "Заявка на трудоустройство в «" + orgName + "» отклонена администратором.");
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private OrganizationMembership require(UUID membershipUid) {
        OrganizationMembership m = membershipRepository.findById(membershipUid)
                .orElseThrow(() -> new IllegalArgumentException("Заявка на трудоустройство не найдена."));
        if (!STATUS_PENDING.equalsIgnoreCase(nz(m.getStatus()))) {
            throw new IllegalStateException("Заявка уже обработана.");
        }
        return m;
    }

    private OrganizationMembership firstWithStatus(List<OrganizationMembership> memberships, String status) {
        return memberships.stream()
                .filter(m -> status.equalsIgnoreCase(nz(m.getStatus())))
                .findFirst().orElse(null);
    }

    private String orgName(UUID organizationUid) {
        return organizationRepository.findById(organizationUid)
                .map(Organization::getName).orElse("Организация");
    }

    private String userName(UUID identityUid) {
        return userRepository.findByIdentityUid(identityUid)
                .map(u -> (u.getFirstName() + " " + u.getLastName()).trim())
                .orElse("Пользователь");
    }

    private String username(UUID identityUid) {
        return userRepository.findByIdentityUid(identityUid)
                .map(com.ideaqr.gateway.domain.User::getUsername).orElse("—");
    }

    private UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }
}
