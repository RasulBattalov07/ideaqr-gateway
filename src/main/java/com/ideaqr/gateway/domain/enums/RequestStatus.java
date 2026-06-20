package com.ideaqr.gateway.domain.enums;

/**
 * Processing status of a {@code RequestRecord}.
 *
 * <p>The first three values drive the existing synchronous pipeline. The
 * remaining values (NEW → REVIEW → APPROVED/REJECTED → COMPLETED) are the
 * lifecycle defined in the brief and are used by the person-to-person access
 * flow, where a request waits for the data owner's confirmation.</p>
 */
public enum RequestStatus {
    PENDING,
    PROCESSED,
    FAILED,

    // Brief lifecycle (person-to-person access, multi-step workflows).
    NEW,
    REVIEW,
    APPROVED,
    REJECTED,
    COMPLETED
}
