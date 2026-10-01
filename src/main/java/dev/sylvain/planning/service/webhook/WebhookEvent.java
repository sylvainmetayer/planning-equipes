package dev.sylvain.planning.service.webhook;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * What a webhook can subscribe to — the published vocabulary of the payload's
 * {@code evenement} field, documented in {@code docs/api.md}. The codes are a
 * contract with whoever filters on them in an n8n flow: never rename one.
 *
 * <p>Each event carries counts and ids, never a person (see
 * {@code docs/rgpd.md}): a chat platform, often outside the EU, is no place
 * for an animateur's name, a minor's above all.</p>
 */
public enum WebhookEvent {
    PLANNING_PUBLISHED("planning.publie", false),
    SWAP_SUBMITTED("echange.soumis", false),
    SWAPS_PENDING("echanges.en_attente", false),
    AVAILABILITY_DECLARED("disponibilites.declaree", false),
    SOLVE_FINISHED("resolution.terminee", false),
    /**
     * The nightly backup covers the whole database, every edition at once: an
     * event of the instance, delivered whatever edition may emit.
     */
    BACKUP_FAILED("sauvegarde.echec", true),
    /** The morning weather query raised alerts: dates, phenomena, levels — a suggestion, never applied. */
    WEATHER_ALERT("meteo.alerte", false),
    /** « Envoyer un test »: sent to one webhook on demand, never subscribed to. */
    TEST("test", true);

    private final String code;

    private final boolean instanceLevel;

    WebhookEvent(String code, boolean instanceLevel) {
        this.code = code;
        this.instanceLevel = instanceLevel;
    }

    /** The published code, {@code planning.publie}. */
    public String code() {
        return code;
    }

    /**
     * Whether the event belongs to the instance rather than to one edition: no
     * edition in its payload, and no edition policy to answer to.
     */
    public boolean instanceLevel() {
        return instanceLevel;
    }

    public static Optional<WebhookEvent> ofCode(String code) {
        return Arrays.stream(values()).filter(event -> event.code.equals(code)).findFirst();
    }

    /** The events a webhook may subscribe to, in the order the form lists them. */
    public static List<WebhookEvent> subscribable() {
        return Arrays.stream(values()).filter(event -> event != TEST).toList();
    }
}
