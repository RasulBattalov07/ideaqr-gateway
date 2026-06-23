package com.ideaqr.gateway.web;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.RegistryObject;
import com.ideaqr.gateway.dto.ApiResponse;
import com.ideaqr.gateway.service.ObjectLifecycleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Administrator control of the <b>OBJECT LIFECYCLE</b> ({@code ACTIVE → MODIFIED
 * → ARCHIVED}). Restricted to {@code ROLE_ADMIN} by the security configuration
 * ({@code /api/admin/**}). Every transition is driven through the governance
 * pipeline by {@link ObjectLifecycleService}, so the object's change-history is
 * appended to the immutable journal and never lost.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class ObjectAdminController {

    private final ObjectLifecycleService objectLifecycleService;
    private final AuthSupport authSupport;

    @PostMapping("/objects/{objectUid}/activate")
    public ResponseEntity<ApiResponse> activate(@PathVariable String objectUid,
                                                @RequestBody(required = false) Map<String, Object> body,
                                                Authentication authentication) {
        Identity admin = authSupport.requireIdentity(authentication);
        return respond(objectLifecycleService.activate(admin, objectUid, note(body)),
                "Объект активирован.");
    }

    @PostMapping("/objects/{objectUid}/modify")
    public ResponseEntity<ApiResponse> modify(@PathVariable String objectUid,
                                              @RequestBody(required = false) Map<String, Object> body,
                                              Authentication authentication) {
        Identity admin = authSupport.requireIdentity(authentication);
        return respond(objectLifecycleService.modify(admin, objectUid, note(body)),
                "Изменение объекта зафиксировано.");
    }

    @PostMapping("/objects/{objectUid}/archive")
    public ResponseEntity<ApiResponse> archive(@PathVariable String objectUid,
                                               @RequestBody(required = false) Map<String, Object> body,
                                               Authentication authentication) {
        Identity admin = authSupport.requireIdentity(authentication);
        return respond(objectLifecycleService.archive(admin, objectUid, note(body)),
                "Объект архивирован.");
    }

    /** Reassign an object to a new owner (sale / handover) — history is preserved. */
    @PostMapping("/objects/{objectUid}/transfer")
    public ResponseEntity<ApiResponse> transfer(@PathVariable String objectUid,
                                                @RequestBody(required = false) Map<String, Object> body,
                                                Authentication authentication) {
        Identity admin = authSupport.requireIdentity(authentication);
        Object newOwner = body != null ? body.get("newOwnerIdentityUid") : null;
        if (newOwner == null || newOwner.toString().isBlank()) {
            throw new IllegalArgumentException("Не указан новый владелец объекта (newOwnerIdentityUid).");
        }
        RegistryObject object = objectLifecycleService.transfer(
                admin, objectUid, UUID.fromString(newOwner.toString().trim()), note(body));
        return ResponseEntity.ok(ApiResponse.ok("Объект передан новому владельцу.")
                .with("objectUid", object.getObjectUid())
                .with("status", object.getStatus().name())
                .with("ownerIdentityUid", object.getOwnerIdentityUid()));
    }

    private ResponseEntity<ApiResponse> respond(RegistryObject object, String message) {
        return ResponseEntity.ok(ApiResponse.ok(message)
                .with("objectUid", object.getObjectUid())
                .with("status", object.getStatus().name())
                .with("trustScore", object.getTrustScore()));
    }

    private String note(Map<String, Object> body) {
        if (body == null) {
            return null;
        }
        Object note = body.get("note");
        return note != null ? note.toString() : null;
    }
}
