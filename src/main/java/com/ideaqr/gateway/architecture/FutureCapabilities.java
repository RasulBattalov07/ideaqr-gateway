package com.ideaqr.gateway.architecture;

/**
 * Architectural placeholders for capabilities the brief says to <b>design for but
 * not implement yet</b>. They are documented here (and reflected by extension
 * points elsewhere — e.g. the {@code Workflow} entity, the {@code SessionMode}
 * enum, the append-only {@code Event} log) so that adding them later requires no
 * change to the platform core (Identity → Role → Request → Decision →
 * Interaction → Event → History → Trust Score).
 *
 * <ul>
 *   <li><b>CRISIS_MODE</b> — citizen-abroad SOS coordination (consulate →
 *       transport → arrival confirmation). Hook: {@code RequestType.SOS} +
 *       {@code Workflow} multi-step escalation.</li>
 *   <li><b>ADVISORY_ROOM</b> — ephemeral file hand-off; the platform stores only
 *       interaction facts (sender, receiver, time, status), never file content.</li>
 *   <li><b>OFFLINE_MODE / EDGE_MODE</b> — local-first operation with later sync.
 *       Hook: {@code SessionMode} + the event log as the sync unit.</li>
 *   <li><b>QR_WALLET</b> — container of a user's QR, objects, services, requests
 *       and history. Hook: {@code Assignment} already links identity↔QR↔object.</li>
 *   <li><b>TEMPORARY_ROLES</b> — time-boxed roles (e.g. 08:00–18:00 →
 *       SERVICE_OPERATOR, then back to CITIZEN). Hook: working-mode session.</li>
 *   <li><b>DIGITAL_CONTINUITY</b> — object lifecycle Created → Active → Modified
 *       → Archived, reconstructable from the event log.</li>
 *   <li><b>QR_SCENE</b> — one QR resolving to different scenarios by context
 *       (home / hospital / shop). Hook: context analysis in the policy engine.</li>
 *   <li><b>AI_READY_LAYER</b> — every event already records who / what / when /
 *       where / why, in a shape ready for future analytics and AI modules.</li>
 * </ul>
 *
 * <p>Explicitly out of scope for now (per the brief): eGov, digital signatures,
 * biometrics, real state registries, AI, financial operations and geolocation
 * control — to be connected later without touching the core chain.</p>
 */
public final class FutureCapabilities {

    private FutureCapabilities() {
        // Documentation holder — not instantiable.
    }

    /** Roadmap markers; referenced by docs/tests, not by runtime logic yet. */
    public enum Capability {
        CRISIS_MODE,
        ADVISORY_ROOM,
        OFFLINE_MODE,
        EDGE_MODE,
        QR_WALLET,
        TEMPORARY_ROLES,
        DIGITAL_CONTINUITY,
        QR_SCENE,
        AI_READY_LAYER
    }
}
