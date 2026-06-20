package com.ideaqr.gateway.web;

import com.ideaqr.gateway.domain.Complaint;
import com.ideaqr.gateway.domain.Event;
import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.PlatformModule;
import com.ideaqr.gateway.domain.User;
import com.ideaqr.gateway.domain.enums.ComplaintStatus;
import com.ideaqr.gateway.dto.ApiResponse;
import com.ideaqr.gateway.repository.UserRepository;
import com.ideaqr.gateway.service.ComplaintService;
import com.ideaqr.gateway.service.EventService;
import com.ideaqr.gateway.service.IdentityService;
import com.ideaqr.gateway.service.ModuleService;
import com.ideaqr.gateway.service.StatsService;
import com.ideaqr.gateway.service.TrustScoreService;
import com.ideaqr.gateway.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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

    /** Full user list — name, profession, identity, trust score, roles, risk. */
    @GetMapping("/users")
    public ResponseEntity<List<Map<String, Object>>> users(Authentication authentication) {
        authSupport.requireUser(authentication);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (User u : userRepository.findAll()) {
            Identity id = identityService.findById(u.getIdentityUid());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("username", u.getUsername());
            m.put("fullName", (u.getFirstName() + " " + u.getLastName()).trim());
            m.put("profession", u.getProfession());
            m.put("professionLabel", userService.professionLabel(u.getProfession()));
            m.put("employmentStatus", u.getEmploymentStatus().name());
            m.put("admin", u.isAdmin());
            m.put("identityUid", id.getIdentityUid().toString());
            m.put("trustLevel", id.getTrustLevel());
            m.put("trustScore", trustScoreService.compute(id));
            m.put("riskScore", id.getRiskScore());
            m.put("guest", id.getIdentityType().name());
            m.put("roles", id.getRoles().stream().map(Enum::name).collect(Collectors.toList()));
            m.put("createdAt", u.getCreatedAt() != null ? u.getCreatedAt().format(TS) : null);
            rows.add(m);
        }
        return ResponseEntity.ok(rows);
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
    public ResponseEntity<List<Map<String, Object>>> complaints(Authentication authentication) {
        authSupport.requireUser(authentication);
        return ResponseEntity.ok(complaintService.all().stream().map(this::complaintRow).collect(Collectors.toList()));
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

    /** Machine event log (the unified Event model) — admin only. */
    @GetMapping("/events")
    public ResponseEntity<List<Map<String, Object>>> events(Authentication authentication) {
        authSupport.requireUser(authentication);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Event e : eventService.globalLog()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("eventUid", e.getEventUid().toString());
            m.put("eventType", e.getEventType().name());
            m.put("identityUid", e.getIdentityUid() != null ? e.getIdentityUid().toString() : null);
            m.put("objectUid", e.getObjectUid());
            m.put("summary", e.getSummary());
            m.put("createdAt", e.getCreatedAt() != null ? e.getCreatedAt().format(TS) : null);
            rows.add(m);
        }
        return ResponseEntity.ok(rows);
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
