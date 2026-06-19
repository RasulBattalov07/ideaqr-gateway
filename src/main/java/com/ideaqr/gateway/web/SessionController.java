package com.ideaqr.gateway.web;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.Organization;
import com.ideaqr.gateway.domain.OrganizationMembership;
import com.ideaqr.gateway.domain.UserSession;
import com.ideaqr.gateway.service.OrganizationService;
import com.ideaqr.gateway.service.SessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Session context: report the current mode and available organizations, and
 * switch between personal and working mode. Switching mode never changes the
 * identity or the primary QR — only the working context.
 */
@RestController
@RequestMapping("/api/v2")
@RequiredArgsConstructor
public class SessionController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final SessionService sessionService;
    private final OrganizationService organizationService;
    private final AuthSupport authSupport;

    @GetMapping("/session")
    public ResponseEntity<Map<String, Object>> current(Authentication authentication) {
        Identity identity = authSupport.requireIdentity(authentication);
        return ResponseEntity.ok(view(identity));
    }

    @PostMapping("/mode/work")
    public ResponseEntity<Map<String, Object>> work(@RequestBody(required = false) Map<String, String> body,
                                                    Authentication authentication) {
        Identity identity = authSupport.requireIdentity(authentication);
        UUID orgUid = null;
        if (body != null && body.get("organizationUid") != null && !body.get("organizationUid").isBlank()) {
            orgUid = UUID.fromString(body.get("organizationUid").trim());
        }
        sessionService.enterWorkingMode(identity, orgUid);
        return ResponseEntity.ok(view(identity));
    }

    @PostMapping("/mode/personal")
    public ResponseEntity<Map<String, Object>> personal(Authentication authentication) {
        Identity identity = authSupport.requireIdentity(authentication);
        sessionService.exitWorkingMode(identity);
        return ResponseEntity.ok(view(identity));
    }

    private Map<String, Object> view(Identity identity) {
        UserSession s = sessionService.current(identity.getIdentityUid());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", s.getMode().name());
        m.put("activeRole", s.getActiveRole());
        m.put("activeOrganizationUid",
                s.getActiveOrganizationUid() != null ? s.getActiveOrganizationUid().toString() : null);
        Organization activeOrg = organizationService.find(s.getActiveOrganizationUid());
        m.put("activeOrganizationName", activeOrg != null ? activeOrg.getName() : null);
        m.put("startedAt", s.getStartedAt() != null ? s.getStartedAt().format(TS) : null);

        List<Map<String, Object>> orgs = new ArrayList<>();
        for (OrganizationMembership mem : organizationService.membershipsOf(identity.getIdentityUid())) {
            Organization org = organizationService.find(mem.getOrganizationUid());
            Map<String, Object> om = new LinkedHashMap<>();
            om.put("organizationUid", mem.getOrganizationUid().toString());
            om.put("name", org != null ? org.getName() : "—");
            om.put("role", mem.getWorkRole());
            orgs.add(om);
        }
        m.put("organizations", orgs);
        return m;
    }
}
