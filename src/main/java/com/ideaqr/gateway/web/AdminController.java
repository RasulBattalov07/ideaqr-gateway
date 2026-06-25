package com.ideaqr.gateway.web;

import com.ideaqr.gateway.domain.Complaint;
import com.ideaqr.gateway.domain.Event;
import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.PlatformModule;
import com.ideaqr.gateway.domain.User;
import com.ideaqr.gateway.domain.enums.ComplaintStatus;
import com.ideaqr.gateway.dto.ApiResponse;
import com.ideaqr.gateway.dto.PageResponse;
import com.ideaqr.gateway.repository.UserRepository;
import com.ideaqr.gateway.service.AuditService;
import com.ideaqr.gateway.service.ComplaintService;
import com.ideaqr.gateway.service.EventService;
import com.ideaqr.gateway.service.IdentityService;
import com.ideaqr.gateway.service.ModuleService;
import com.ideaqr.gateway.service.StatsService;
import com.ideaqr.gateway.service.TrustScoreService;
import com.ideaqr.gateway.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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
    private final ModuleService moduleService;
    private final ComplaintService complaintService;
    private final EventService eventService;
    private final UserRepository userRepository;
    private final IdentityService identityService;
    private final TrustScoreService trustScoreService;
    private final UserService userService;
    private final AuditService auditService;
    private final AuthSupport authSupport;

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
     * Paginated user list — name, profession, identity, trust score, roles, risk.
     * Pagination (audit 3.1) bounds the result set; identities are batch-loaded and
     * the Trust Score is read from its cached column rather than recomputed per row
     * (audit 3.2 N+1 / 3.3 no work on GET). Query params: {@code ?page=0&size=25}.
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
            m.put("trustScore", id != null ? trustScoreService.cachedOrCompute(id) : null);
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

    @GetMapping("/modules")
    public ResponseEntity<List<Map<String, Object>>> modules(Authentication authentication) {
        authSupport.requireUser(authentication);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PlatformModule mod : moduleService.list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("moduleUid", mod.getModuleUid().toString());
            m.put("code", mod.getCode());
            m.put("name", mod.getName());
            m.put("description", mod.getDescription());
            m.put("status", mod.getStatus().name());
            rows.add(m);
        }
        return ResponseEntity.ok(rows);
    }

    @PostMapping("/modules/{id}/toggle")
    public ResponseEntity<ApiResponse> toggleModule(@PathVariable("id") String id, Authentication authentication) {
        authSupport.requireUser(authentication);
        PlatformModule mod = moduleService.toggle(UUID.fromString(id));
        return ResponseEntity.ok(ApiResponse.ok("Статус модуля обновлён: " + mod.getStatus().name())
                .with("status", mod.getStatus().name()));
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
        m.put("interactionUid", c.getInteractionUid().toString());
        m.put("createdAt", c.getCreatedAt() != null ? c.getCreatedAt().format(TS) : null);
        return m;
    }
}
