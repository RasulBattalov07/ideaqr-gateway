package com.ideaqr.gateway.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Set;

/** Profile of the authenticated user, used by the SPA to choose the interface. */
@Data
@Builder
public class CurrentUserResponse {

    private boolean authenticated;
    private String username;
    private String firstName;
    private String lastName;

    /** Profession key (English). */
    private String profession;

    /** Profession label for display (Russian). */
    private String professionLabel;

    private String employmentStatus;

    /** True → administrator governance panel; false → citizen terminal. */
    private boolean admin;

    private String identityUid;
    private String primaryQrUid;

    /**
     * The single trust metric (0–100) — the provisioned level the policy engine actually
     * gates on (medical ≥ 70, infrastructure ≥ 60). The old duplicate gamified "trust score"
     * was removed so there is one clear, decision-bearing number (audit-fix).
     */
    private int trustLevel;

    /** Identity-level risk score (NORMAL | MEDIUM | HIGH). */
    private String riskScore;

    /** True when this is a guest (unregistered) identity. */
    private boolean guest;

    /** True when the account must set a new password before continuing (audit 4.9). */
    private boolean mustChangePassword;

    /**
     * One-time merge token, returned ONLY when a guest session is first created.
     * The browser keeps it and presents it later to prove ownership of the guest
     * session during a history merge (audit 4.6). Never populated for normal logins.
     */
    private String mergeToken;

    /** Business roles held by the identity (e.g. DOCTOR, CITIZEN). */
    private Set<String> roles;
}
