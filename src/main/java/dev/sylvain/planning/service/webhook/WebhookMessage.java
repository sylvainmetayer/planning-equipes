package dev.sylvain.planning.service.webhook;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One event as it is queued, before any format shapes it: what happened, when,
 * on which edition, the counts that describe it and the screen to open.
 *
 * <p>Stored as the neutral JSON envelope of a delivery and rendered at send
 * time, so a format changed or a secret regenerated meanwhile applies to what
 * was still waiting.</p>
 *
 * @param event      the published code, {@code planning.publie}
 * @param occurredAt the real instant — never the simulated clock of a demo
 * @param edition    {@code null} for an event of the instance
 * @param data       counts and ids only, in the order they are written
 * @param link       the screen to open, {@code null} without a public URL
 */
public record WebhookMessage(
        String event, Instant occurredAt, EditionRef edition, Map<String, Object> data, String link) {

    public WebhookMessage {
        data = data == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }

    /** The edition an event is about: its id and the name the organiser gave it. */
    public record EditionRef(String id, String name) {}

    /** The published code read back as a known event, {@code null} for one this version no longer knows. */
    public WebhookEvent knownEvent() {
        return WebhookEvent.ofCode(event).orElse(null);
    }
}
