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
    private int trustLevel;

    /** Trust Score (0–100) belonging to the identity. */
    private int trustScore;

    /** Identity-level risk score (NORMAL | MEDIUM | HIGH). */
    private String riskScore;

    /** True when this is a guest (unregistered) identity. */
    private boolean guest;

    /** Business roles held by the identity (e.g. DOCTOR, CITIZEN). */
    private Set<String> roles;
}
