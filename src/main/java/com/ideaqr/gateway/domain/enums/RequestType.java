package com.ideaqr.gateway.domain.enums;

/** The kind of action a request represents as it enters the pipeline. */
public enum RequestType {
    /** A scan / read access against a registry object. */
    ACCESS,
    /** Creation and governance of a new QR-identified object (admin panel). */
    QR_CREATION,
    /** A citizen reporting an issue against an infrastructure object. */
    REPORT_ISSUE,
    /** An emergency / SOS request (escalated priority). */
    SOS,
    /** Activating or ending working mode (a governed session change). */
    WORKING_MODE
}
