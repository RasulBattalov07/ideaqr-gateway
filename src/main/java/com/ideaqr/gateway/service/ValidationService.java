package com.ideaqr.gateway.service;

import com.ideaqr.gateway.entity.Identity;
import com.ideaqr.gateway.enums.DecisionResult;
import com.ideaqr.gateway.enums.RequestType;
import com.ideaqr.gateway.enums.Role;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;

/**
 * Advanced rules engine. Replaces the old prefix-only check with a multi-factor
 * evaluation: object/role prefix match, role-capability mapping, and time-window
 * constraints. Produces a {@link Verdict} that the orchestration layer persists as
 * a Decision entity.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ValidationService {

    /** Engineers may access INFRA_ objects only within this working window. */
    public static final LocalTime INFRA_WINDOW_START = LocalTime.of(8, 0);
    public static final LocalTime INFRA_WINDOW_END = LocalTime.of(18, 0);

    /**
     * Immutable verdict carrier.
     */
    @Getter
    public static class Verdict {
        private final DecisionResult result;
        private final String reason;

        public Verdict(DecisionResult result, String reason) {
            this.result = result;
            this.reason = reason;
        }

        public boolean isApproved() {
            return result == DecisionResult.APPROVED;
        }
    }

    /**
     * Evaluate an access request for a given identity against a target object.
     *
     * @param identity    the (already persisted) subject
     * @param requestType the kind of access requested
     * @param objectUid   the target object identifier (carries a sub-network prefix)
     * @param now         evaluation timestamp (injected for testability)
     * @return a verdict
     */
    public Verdict evaluate(Identity identity, RequestType requestType, String objectUid, LocalDateTime now) {
        Set<Role> roles = identity.getRoles();

        // QR creation is a self-service request that does not target a guarded object.
        if (requestType == RequestType.QR_CREATION) {
            return evaluateQrCreation(identity);
        }

        if (objectUid == null || objectUid.isBlank()) {
            return new Verdict(DecisionResult.REJECTED, "Missing objectUid for access request");
        }

        // Determine which role would be required to govern this object.
        Role requiredRole = resolveRoleByObjectPrefix(objectUid);
        if (requiredRole == null) {
            return new Verdict(DecisionResult.REVIEW,
                    "Unknown object prefix '" + prefixOf(objectUid) + "' has no governing role; routed to manual REVIEW");
        }

        // Multi-role check: the identity must actually hold the required role,
        // regardless of any default context.
        if (roles == null || !roles.contains(requiredRole)) {
            return new Verdict(DecisionResult.REJECTED,
                    "Identity lacks required role " + requiredRole + " for object " + objectUid);
        }

        // Cross-check that the request type is coherent with the object class.
        Verdict typeCoherence = checkRequestTypeCoherence(requestType, requiredRole, objectUid);
        if (typeCoherence != null) {
            return typeCoherence;
        }

        // Time-window rule: ENGINEER access to INFRA_ objects is constrained to 08:00-18:00.
        if (requiredRole == Role.ENGINEER) {
            LocalTime current = now.toLocalTime();
            boolean withinWindow =
                    !current.isBefore(INFRA_WINDOW_START) && !current.isAfter(INFRA_WINDOW_END);
            if (!withinWindow) {
                return new Verdict(DecisionResult.REJECTED,
                        "INFRA access for ENGINEER is restricted to " + INFRA_WINDOW_START + "-"
                                + INFRA_WINDOW_END + "; current time " + current + " is outside the window");
            }
        }

        return new Verdict(DecisionResult.APPROVED,
                "Access granted: role " + requiredRole + " satisfies object " + objectUid);
    }

    /**
     * QR creation is approved for any ACTIVE-capable identity that holds at least one role.
     * A guest with no roles is sent to REVIEW rather than auto-approved.
     */
    private Verdict evaluateQrCreation(Identity identity) {
        if (identity.getRoles() == null || identity.getRoles().isEmpty()) {
            return new Verdict(DecisionResult.REVIEW,
                    "QR creation requested by identity with no assigned roles; manual REVIEW required");
        }
        return new Verdict(DecisionResult.APPROVED, "QR creation approved for identity " + identity.getIdentityUid());
    }

    /**
     * Ensures the declared request type matches the class of object being targeted.
     * Returns a rejecting/review verdict on mismatch, or null when coherent.
     */
    private Verdict checkRequestTypeCoherence(RequestType requestType, Role requiredRole, String objectUid) {
        switch (requiredRole) {
            case DOCTOR:
                if (requestType != RequestType.MEDICAL_ACCESS && requestType != RequestType.OBJECT_ACCESS) {
                    return new Verdict(DecisionResult.REJECTED,
                            "Request type " + requestType + " is not valid for medical object " + objectUid);
                }
                break;
            case ENGINEER:
                if (requestType != RequestType.INFRASTRUCTURE_ACCESS && requestType != RequestType.OBJECT_ACCESS) {
                    return new Verdict(DecisionResult.REJECTED,
                            "Request type " + requestType + " is not valid for infrastructure object " + objectUid);
                }
                break;
            case FINANCIER:
                if (requestType != RequestType.FINANCE_ACCESS && requestType != RequestType.OBJECT_ACCESS) {
                    return new Verdict(DecisionResult.REJECTED,
                            "Request type " + requestType + " is not valid for finance object " + objectUid);
                }
                break;
            default:
                // CITIZEN / ADMIN governed objects accept generic OBJECT_ACCESS.
                break;
        }
        return null;
    }

    /**
     * Maps an object identifier's prefix to the role that governs it.
     * Returns null when no role matches the prefix.
     */
    private Role resolveRoleByObjectPrefix(String objectUid) {
        for (Role role : Role.values()) {
            if (objectUid.startsWith(role.getObjectPrefix())) {
                return role;
            }
        }
        return null;
    }

    private String prefixOf(String objectUid) {
        int underscore = objectUid.indexOf('_');
        return underscore >= 0 ? objectUid.substring(0, underscore + 1) : objectUid;
    }
}
