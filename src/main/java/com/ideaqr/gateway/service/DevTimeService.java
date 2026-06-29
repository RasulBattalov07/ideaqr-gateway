package com.ideaqr.gateway.service;

import org.springframework.stereotype.Service;

/**
 * Demo "time machine". Lets the platform administrator mock the hour-of-day used by the
 * working-hours policy so a presenter can show an object being allowed during business hours
 * and blocked outside them without waiting for the wall clock.
 *
 * <p><b>Scope &amp; control:</b> the mock is a single, process-wide value set <i>only</i> by an
 * {@code ADMIN} (the endpoint is locked to {@code ROLE_ADMIN}; a regular user can neither read
 * nor set it). It therefore acts as a global "demo clock" the presenter freezes for the whole
 * live demo, so a specialist's session immediately reflects the chosen hour. It is a
 * presentation lever, not a client-trusted input: when no mock is set the real server clock
 * (Asia/Almaty, audit 4.3) applies, and the value resets on restart.</p>
 */
@Service
public class DevTimeService {

    /** Process-wide mock hour [0–23], or {@code null} when the real server clock applies. */
    private static volatile Integer globalMockHour = null;

    /** The effective mock hour, or {@code null} to fall back to the real server clock. */
    public Integer currentMockHour() {
        return globalMockHour;
    }

    /** Pin the platform's effective hour to {@code hour} (clamped to [0–23]). Admin only. */
    public int setMockHour(int hour) {
        int clamped = Math.max(0, Math.min(23, hour));
        globalMockHour = clamped;
        return clamped;
    }

    /** Drop the mock — decisions fall back to the real server clock. Admin only. */
    public void clear() {
        globalMockHour = null;
    }
}
