package dev.sylvain.planning.service.webhook;

import java.time.Instant;
import java.util.UUID;

/**
 * One event bound for one webhook, and how its attempts went.
 *
 * @param payload  the neutral JSON envelope, rendered per format at send time
 * @param attempts how many attempts were made so far
 * @param error    a fixed sentence about the last failure — never the
 *                 receiver's body, never the address
 */
public record WebhookDelivery(
        UUID id,
        String webhookId,
        String event,
        String payload,
        int attempts,
        DeliveryStatus status,
        Integer httpStatus,
        Long durationMs,
        String error,
        Instant nextAttemptAt,
        Instant lastAttemptAt,
        Instant createdAt) {}
