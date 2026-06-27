package com.ideaqr.gateway.web;

import com.ideaqr.gateway.domain.Complaint;
import com.ideaqr.gateway.domain.Event;
import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.Interaction;
import com.ideaqr.gateway.domain.RequestRecord;
import com.ideaqr.gateway.domain.User;
import com.ideaqr.gateway.domain.Workflow;
import com.ideaqr.gateway.domain.enums.ComplaintStatus;
import com.ideaqr.gateway.domain.enums.HistoryEventType;
import com.ideaqr.gateway.dto.ApiResponse;
import com.ideaqr.gateway.dto.PageResponse;
import com.ideaqr.gateway.repository.InteractionRepository;
import com.ideaqr.gateway.repository.RequestRepository;
import com.ideaqr.gateway.repository.UserRepository;
import com.ideaqr.gateway.repository.WorkflowRepository;
import com.ideaqr.gateway.service.AuditService;
import com.ideaqr.gateway.service.ComplaintService;
import com.ideaqr.gateway.service.EventService;
import com.ideaqr.gateway.service.IdentityService;
import com.ideaqr.gateway.service.StatsService;
import com.ideaqr.gateway.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Administrator-only data: the full audit, statistics, analytics, the user list,
 * modules and complaints. Per the customer's explicit instruction this is the
 * "admin sees everything" side — ordinary users only get their own history
 * (see {@link HistoryController}). Restricted to {@code ROLE_ADMIN} by the
 * security configuration ({@code /api/admin/**}).
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")  // second line of defence beyond the URL matcher (audit 3.8)
public class AdminController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final StatsService statsService;
    private final ComplaintService complaintService;
    private final EventService eventService;
    private final UserRepository userRepository;
    private final IdentityService identityService;
    private final UserService userService;
    private final AuditService auditService;
    private final AuthSupport authSupport;
    private final WorkflowRepository workflowRepository;
    private final RequestRepository requestRepository;
    private final InteractionRepository interactionRepository;

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats(Authentication authentication) {
        authSupport.requireUser(authentication);
        return ResponseEntity.ok(statsService.statistics());
    }

    @GetMapping("/analytics")
    public ResponseEntity<Map<String, Object>> analytics(Authentication authentication) {
        authSupport.requireUser(authentication);
        return ResponseEntity.ok(statsService.analytics());
    }

    /**
     * Paginated user list — name, profession, identity, trust level, roles, risk.
     * Pagination (audit 3.1) bounds the result set; identities are batch-loaded
     * (audit 3.2 N+1) and trust is the single provisioned {@code trustLevel} the policy
     * engine actually gates on (audit-fix: the duplicate gamified score is gone).
     * Query params: {@code ?page=0&size=25}.
     */
    @GetMapping("/users")
    public ResponseEntity<PageResponse<Map<String, Object>>> users(
            @PageableDefault(size = 25) Pageable pageable, Authentication authentication) {
        authSupport.requireUser(authentication);
        Page<User> page = userRepository.findAll(pageable);

        // One query for the page's identities instead of one per user.
        List<UUID> identityUids = page.getContent().stream().map(User::getIdentityUid).toList();
        Map<UUID, Identity> identities = identityService.findAllByIds(identityUids).stream()
                .collect(Collectors.toMap(Identity::getIdentityUid, i -> i));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (User u : page.getContent()) {
            Identity id = identities.get(u.getIdentityUid());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("username", u.getUsername());
            m.put("fullName", (u.getFirstName() + " " + u.getLastName()).trim());
            m.put("profession", u.getProfession());
            m.put("professionLabel", userService.professionLabel(u.getProfession()));
            m.put("employmentStatus", u.getEmploymentStatus().name());
            m.put("admin", u.isAdmin());
            m.put("blocked", u.isBlocked());
            m.put("blockedReason", u.getBlockedReason());
            m.put("blockedAt", u.getBlockedAt() != null ? u.getBlockedAt().format(TS) : null);
            m.put("identityUid", u.getIdentityUid().toString());
            m.put("trustLevel", id != null ? id.getTrustLevel() : null);
            m.put("riskScore", id != null ? id.getRiskScore() : null);
            m.put("guest", id != null ? id.getIdentityType().name() : null);
            m.put("roles", id != null
                    ? id.getRoles().stream().map(Enum::name).collect(Collectors.toList())
                    : List.of());
            m.put("createdAt", u.getCreatedAt() != null ? u.getCreatedAt().format(TS) : null);
            rows.add(m);
        }
        return ResponseEntity.ok(PageResponse.of(page, rows));
    }

    /**
     * Verify the integrity of the append-only journal's hash chain (audit 4.5).
     * Makes the "immutable audit" claim demonstrable: returns whether the chain is
     * intact, how many entries were checked, and the first broken link if any.
     */
    @GetMapping("/audit/verify")
    public ResponseEntity<Map<String, Object>> verifyAudit(Authentication authentication) {
        authSupport.requireUser(authentication);
        AuditService.ChainVerification result = auditService.verifyChain();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("valid", result.valid());
        m.put("entriesChecked", result.entriesChecked());
        m.put("brokenAtHistoryUid", result.brokenAtHistoryUid());
        return ResponseEntity.ok(m);
    }

    /**
     * SOS alert queue (P0 — the SOS button now means something). Every SOS request seeds a
     * {@link Workflow} of type {@code SOS_ESCALATION}; this surfaces them — across every tenant,
     * since the admin runs unscoped — so an administrator actually sees the emergency instead of
     * the request dead-ending in the sender's own notifications. Newest first.
     */
    @GetMapping("/sos")
    public ResponseEntity<List<Map<String, Object>>> sosQueue(Authentication authentication) {
        authSupport.requireUser(authentication);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Workflow w : workflowRepository.findByWorkflowTypeOrderByCreatedAtDesc("SOS_ESCALATION")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("workflowUid", w.getWorkflowUid().toString());
            m.put("status", w.getStatus());
            m.put("createdAt", w.getCreatedAt() != null ? w.getCreatedAt().format(TS) : null);
            RequestRecord req = requestRepository.findById(w.getRequestUid()).orElse(null);
            String fromName = "—";
            String objectUid = null;
            String message = null;
            if (req != null) {
                objectUid = req.getObjectUid();
                fromName = displayName(req.getIdentityUid());
                List<Interaction> ints = interactionRepository.findByRequestUid(req.getRequestUid());
                if (!ints.isEmpty()) {
                    message = ints.get(0).getDetail();
                }
            }
            m.put("fromName", fromName);
            m.put("objectUid", objectUid);
            m.put("message", message);
            rows.add(m);
        }
        return ResponseEntity.ok(rows);
    }

    /** Administrator marks an SOS request handled (closes the escalation workflow). */
    @PostMapping("/sos/{workflowUid}/resolve")
    @Transactional
    public ResponseEntity<ApiResponse> resolveSos(@PathVariable("workflowUid") String workflowUid,
                                                  Authentication authentication) {
        authSupport.requireUser(authentication);
        Workflow w = workflowRepository.findById(UUID.fromString(workflowUid))
                .orElseThrow(() -> new IllegalArgumentException("SOS-запрос не найден."));
        w.setStatus("RESOLVED");
        workflowRepository.save(w);
        requestRepository.findById(w.getRequestUid()).ifPresent(r ->
                auditService.record(r.getIdentityUid(), r.getObjectUid(), HistoryEventType.SOS_CREATED,
                        "SOS-запрос обработан администратором."));
        return ResponseEntity.ok(ApiResponse.ok("SOS-запрос отмечен как обработанный."));
    }

    @GetMapping("/complaints")
    public ResponseEntity<PageResponse<Map<String, Object>>> complaints(
            @PageableDefault(size = 50) Pageable pageable, Authentication authentication) {
        authSupport.requireUser(authentication);
        Page<Complaint> page = complaintService.all(pageable);
        return ResponseEntity.ok(PageResponse.of(page,
                page.getContent().stream().map(this::complaintRow).collect(Collectors.toList())));
    }

    @PostMapping("/complaints/{id}/status")
    public ResponseEntity<ApiResponse> complaintStatus(@PathVariable("id") String id,
                                                       @RequestBody Map<String, String> body,
                                                       Authentication authentication) {
        authSupport.requireUser(authentication);
        ComplaintStatus status;
        try {
            status = ComplaintStatus.valueOf(body.getOrDefault("status", "").trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Недопустимый статус жалобы."));
        }
        Complaint c = complaintService.updateStatus(UUID.fromString(id), status);
        return ResponseEntity.ok(ApiResponse.ok("Статус жалобы обновлён.").with("status", c.getStatus().name()));
    }

    /** Open a complaint and reply: delivers a response to the author and updates the status. */
    @PostMapping("/complaints/{id}/respond")
    public ResponseEntity<ApiResponse> respondComplaint(@PathVariable("id") String id,
                                                       @RequestBody Map<String, String> body,
                                                       Authentication authentication) {
        authSupport.requireUser(authentication);
        ComplaintStatus status = null;
        String raw = body.getOrDefault("status", "").trim();
        if (!raw.isEmpty()) {
            try {
                status = ComplaintStatus.valueOf(raw.toUpperCase(Locale.ROOT));
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(ApiResponse.error("Недопустимый статус жалобы."));
            }
        }
        Complaint c = complaintService.respond(UUID.fromString(id), body.get("message"), status);
        return ResponseEntity.ok(ApiResponse.ok("Ответ отправлен пользователю.")
                .with("status", c.getStatus().name()));
    }

    /** Machine event log (the unified Event model) — admin only. Server-paginated (audit M-2). */
    @GetMapping("/events")
    public ResponseEntity<PageResponse<Map<String, Object>>> events(
            @PageableDefault(size = 50) Pageable pageable, Authentication authentication) {
        authSupport.requireUser(authentication);
        Page<Event> page = eventService.globalLog(pageable);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Event e : page.getContent()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("eventUid", e.getEventUid().toString());
            m.put("eventType", e.getEventType().name());
            m.put("identityUid", e.getIdentityUid() != null ? e.getIdentityUid().toString() : null);
            m.put("objectUid", e.getObjectUid());
            m.put("summary", e.getSummary());
            m.put("createdAt", e.getCreatedAt() != null ? e.getCreatedAt().format(TS) : null);
            rows.add(m);
        }
        return ResponseEntity.ok(PageResponse.of(page, rows));
    }

    private Map<String, Object> complaintRow(Complaint c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("complaintUid", c.getComplaintUid().toString());
        m.put("subject", c.getSubject());
        m.put("category", c.getCategory());
        m.put("description", c.getDescription());
        m.put("status", c.getStatus().name());
        m.put("interactionUid", c.getInteractionUid() != null ? c.getInteractionUid().toString() : null);
        m.put("createdAt", c.getCreatedAt() != null ? c.getCreatedAt().format(TS) : null);
        return m;
    }

    private String displayName(UUID identityUid) {
        if (identityUid == null) {
            return "—";
        }
        return userRepository.findByIdentityUid(identityUid)
                .map(u -> (u.getFirstName() + " " + u.getLastName()).trim())
                .orElse("Пользователь");
    }
}
