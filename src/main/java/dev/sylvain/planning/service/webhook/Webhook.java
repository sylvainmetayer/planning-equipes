package dev.sylvain.planning.service.webhook;

import java.time.Instant;
import java.util.List;

/**
 * One configured webhook, as stored. The secret stays encrypted in this
 * record: it is opened only at the moment a delivery needs it.
 *
 * @param url             the address in clear, {@link WebhookFormat#GENERIC} only
 * @param encryptedSecret the HMAC key, the address of a chat preset, or the
 *                        Telegram bot token — see {@link SecretCipher}
 * @param chatId          the Telegram chat, {@code null} for every other format
 */
public record Webhook(
        String id,
        String name,
        WebhookFormat format,
        String url,
        String encryptedSecret,
        String chatId,
        List<WebhookEvent> events,
        boolean active,
        Instant createdAt,
        Instant modifiedAt) {

    public Webhook {
        events = events == null ? List.of() : List.copyOf(events);
    }

    public boolean subscribesTo(WebhookEvent event) {
        return events.contains(event);
    }
}
