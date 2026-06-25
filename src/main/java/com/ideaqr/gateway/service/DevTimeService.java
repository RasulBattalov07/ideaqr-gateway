package com.ideaqr.gateway.service;

import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Demo "time machine". Lets a live demo mock the hour-of-day used by the
 * working-hours policy <b>for the current session only</b>, so a presenter can show an
 * object being allowed during business hours and blocked outside them without waiting
 * for the wall clock.
 *
 * <p>The mock is stored as a session attribute (Spring-Session-backed), so it is scoped
 * to the one browser session and never leaks into another user's decisions. It is a
 * presentation lever, not a security control: the real policy still defaults to the
 * server clock when no mock is set (audit 4.3).</p>
 */
@Service
public class DevTimeService {

    private static final String ATTR = "ideaqr.mockHour";

    /** The session's mock hour [0–23], or {@code null} when the real server clock applies. */
    public Integer currentMockHour() {
        ServletRequestAttributes attrs = current();
        if (attrs == null) {
            return null;
        }
        Object value = attrs.getAttribute(ATTR, RequestAttributes.SCOPE_SESSION);
        return value instanceof Integer i ? i : null;
    }

    /** Pin the session's effective hour to {@code hour} (clamped to [0–23]). */
    public int setMockHour(int hour) {
        ServletRequestAttributes attrs = current();
        if (attrs == null) {
            throw new IllegalStateException("Нет активной сессии для установки времени.");
        }
        int clamped = Math.max(0, Math.min(23, hour));
        attrs.setAttribute(ATTR, clamped, RequestAttributes.SCOPE_SESSION);
        return clamped;
    }

    /** Drop the mock — decisions fall back to the real server clock. */
    public void clear() {
        ServletRequestAttributes attrs = current();
        if (attrs != null) {
            attrs.removeAttribute(ATTR, RequestAttributes.SCOPE_SESSION);
        }
    }

    private ServletRequestAttributes current() {
        RequestAttributes ra = RequestContextHolder.getRequestAttributes();
        return ra instanceof ServletRequestAttributes sra ? sra : null;
    }
}
