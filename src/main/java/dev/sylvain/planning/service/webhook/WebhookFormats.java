package dev.sylvain.planning.service.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The body of a delivery, one pure method per format, and the signature of the
 * generic one.
 *
 * <p>Pure and static on purpose: what a receiver reads is a contract, pinned
 * by JSON snapshot tests that need neither a container nor a network. The
 * {@link ObjectMapper} is the caller's — the one Quarkus configured.</p>
 *
 * <p>Every human sentence is written here once and shaped per format, so Slack,
 * Discord, Matrix and Telegram never say four different things about the same
 * publication. None of them names a person: the data carries counts and ids
 * only, and these sentences have nothing else to read.</p>
 */
public final class WebhookFormats {

    private WebhookFormats() {}

    /** A rendered body and the media type it travels under. */
    public record Rendered(byte[] body, String contentType) {

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        /** By content: a record compares an array component by reference. */
        @Override
        public boolean equals(Object other) {
            return other instanceof Rendered that
                    && Arrays.equals(body, that.body)
                    && Objects.equals(contentType, that.contentType);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(body) + Objects.hashCode(contentType);
        }

        @Override
        public String toString() {
            return "Rendered[" + contentType + ", " + body.length + " bytes]";
        }
    }

    /** What a chat message says: a title and one sentence. */
    record Summary(String title, String sentence) {}

    private static final String JSON = "application/json";

    /** Renders {@code message} for {@code format}; {@code chatId} is read by Telegram alone. */
    public static Rendered render(
            ObjectMapper mapper, WebhookFormat format, String deliveryId, WebhookMessage message, String chatId) {
        ObjectNode body =
                switch (format) {
                    case GENERIC -> generic(mapper, deliveryId, message);
                    case SLACK -> slack(mapper, message);
                    case DISCORD -> discord(mapper, message);
                    case MATRIX -> matrix(mapper, message);
                    case TELEGRAM -> telegram(mapper, message, chatId);
                };
        try {
            return new Rendered(mapper.writeValueAsBytes(body), JSON);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A webhook body could not be serialised", e);
        }
    }

    /**
     * The neutral envelope, plus the delivery id a receiver deduplicates on —
     * the same id travels in {@code X-Planning-Livraison}, and a retry or a
     * « Renvoyer » keeps it.
     */
    static ObjectNode generic(ObjectMapper mapper, String deliveryId, WebhookMessage message) {
        ObjectNode body = mapper.createObjectNode();
        body.put("id", deliveryId);
        body.setAll(envelope(mapper, message));
        return body;
    }

    /** What a delivery row stores, and the generic body without its id. */
    public static ObjectNode envelope(ObjectMapper mapper, WebhookMessage message) {
        ObjectNode body = mapper.createObjectNode();
        body.put("evenement", message.event());
        body.put("survenuLe", message.occurredAt().toString());
        if (message.edition() == null) {
            body.putNull("edition");
        } else {
            ObjectNode edition = body.putObject("edition");
            edition.put("id", message.edition().id());
            edition.put("nom", message.edition().name());
        }
        body.set("donnees", mapper.valueToTree(message.data()));
        body.put("lien", message.link());
        return body;
    }

    /** The envelope as the text a delivery row stores. */
    public static String envelopeJson(ObjectMapper mapper, WebhookMessage message) {
        try {
            return mapper.writeValueAsString(envelope(mapper, message));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A webhook payload could not be serialised", e);
        }
    }

    /** The edition after a title, {@code " · « Année 2026 »"}; {@code name} comes escaped for its format. */
    private static String editionSuffix(String name) {
        return " · « " + name + " »";
    }

    static ObjectNode slack(ObjectMapper mapper, WebhookMessage message) {
        Summary summary = summary(message);
        StringBuilder text =
                new StringBuilder("*").append(escapeSlack(summary.title())).append('*');
        if (message.edition() != null) {
            text.append(editionSuffix(escapeSlack(message.edition().name())));
        }
        if (!summary.sentence().isEmpty()) {
            text.append('\n').append(escapeSlack(summary.sentence()));
        }
        if (message.link() != null) {
            text.append('\n').append('<').append(message.link()).append("|Ouvrir dans l'application>");
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("text", text.toString());
        return body;
    }

    static ObjectNode discord(ObjectMapper mapper, WebhookMessage message) {
        Summary summary = summary(message);
        ObjectNode body = mapper.createObjectNode();
        body.put(
                "content",
                message.edition() == null
                        ? summary.title()
                        : summary.title() + editionSuffix(message.edition().name()));
        // An edition name is typed by an organiser: it must never ping a whole server.
        body.putObject("allowed_mentions").putArray("parse");
        ArrayNode embeds = body.putArray("embeds");
        ObjectNode embed = embeds.addObject();
        embed.put("title", summary.title());
        if (!summary.sentence().isEmpty()) {
            embed.put("description", summary.sentence());
        }
        if (message.link() != null) {
            embed.put("url", message.link());
        }
        embed.put("timestamp", message.occurredAt().toString());
        return body;
    }

    static ObjectNode matrix(ObjectMapper mapper, WebhookMessage message) {
        Summary summary = summary(message);
        StringBuilder text = new StringBuilder(summary.title());
        StringBuilder html = new StringBuilder("<strong>")
                .append(escapeHtml(summary.title()))
                .append("</strong>");
        if (message.edition() != null) {
            text.append(editionSuffix(message.edition().name()));
            html.append(editionSuffix(escapeHtml(message.edition().name())));
        }
        if (!summary.sentence().isEmpty()) {
            text.append('\n').append(summary.sentence());
            html.append("<br>").append(escapeHtml(summary.sentence()));
        }
        if (message.link() != null) {
            text.append('\n').append(message.link());
            html.append("<br><a href=\"").append(escapeHtml(message.link())).append("\">Ouvrir dans l'application</a>");
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("text", text.toString());
        body.put("html", html.toString());
        return body;
    }

    static ObjectNode telegram(ObjectMapper mapper, WebhookMessage message, String chatId) {
        Summary summary = summary(message);
        StringBuilder html =
                new StringBuilder("<b>").append(escapeHtml(summary.title())).append("</b>");
        if (message.edition() != null) {
            html.append(editionSuffix(escapeHtml(message.edition().name())));
        }
        if (!summary.sentence().isEmpty()) {
            html.append('\n').append(escapeHtml(summary.sentence()));
        }
        if (message.link() != null) {
            html.append('\n')
                    .append("<a href=\"")
                    .append(escapeHtml(message.link()))
                    .append("\">Ouvrir dans l'application</a>");
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("chat_id", chatId);
        body.put("text", html.toString());
        body.put("parse_mode", "HTML");
        body.put("disable_web_page_preview", true);
        return body;
    }

    /**
     * The one sentence every chat format shows, read from the counts alone.
     * An event this version does not know is announced by its code rather than
     * dropped: a retry outliving an upgrade still says something.
     */
    static Summary summary(WebhookMessage message) {
        Map<String, Object> data = message.data();
        WebhookEvent event = message.knownEvent();
        if (event == null) {
            return new Summary(message.event(), "");
        }
        return switch (event) {
            case PLANNING_PUBLISHED ->
                new Summary(
                        "Planning publié",
                        count(data, "envoyes", "personne prévenue", "personnes prévenues")
                                + " sur "
                                + number(data, "destinataires")
                                + " ; "
                                + count(data, "planningsChanges", "planning modifié", "plannings modifiés")
                                + ", "
                                + count(data, "differes", "envoi différé", "envois différés")
                                + ".");
            case SWAP_SUBMITTED ->
                new Summary(
                        "Demande d'échange à trancher",
                        count(data, "nombre", "demande d'échange attend", "demandes d'échange attendent")
                                + " une décision.");
            case SWAPS_PENDING ->
                new Summary(
                        "Échanges en attente",
                        count(data, "nombre", "demande attend", "demandes attendent")
                                + " une décision, la plus ancienne depuis "
                                + count(data, "ancienneteMaxJours", "jour", "jours")
                                + ".");
            case AVAILABILITY_DECLARED ->
                new Summary(
                        "Disponibilités déclarées",
                        "Une déclaration attend : "
                                + count(data, "joursIndisponibles", "jour indisponible", "jours indisponibles")
                                + ", "
                                + count(data, "souhaits", "souhait", "souhaits")
                                + ".");
            case SOLVE_FINISHED ->
                new Summary(
                        "Résolution terminée",
                        "Score " + data.get("score") + " : "
                                + (Boolean.TRUE.equals(data.get("faisable"))
                                        ? "planning faisable."
                                        : "planning NON faisable."));
            case BACKUP_FAILED ->
                new Summary(
                        "Échec de la sauvegarde nocturne",
                        String.valueOf(data.get("raison"))
                                + " ("
                                + count(data, "echecsConsecutifs", "échec consécutif", "échecs consécutifs")
                                + ").");
            case TEST -> new Summary("Test", "Ce webhook est bien relié à l'application.");
        };
    }

    private static String number(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value == null ? "0" : String.valueOf(value);
    }

    private static String count(Map<String, Object> data, String key, String singular, String plural) {
        Object value = data.get(key);
        long n = value instanceof Number number ? number.longValue() : 0;
        return n + " " + (n > 1 ? plural : singular);
    }

    /** Slack's three control characters; anything else in mrkdwn is shown as typed. */
    static String escapeSlack(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * {@code sha256=<hex HMAC-SHA256(secret, timestamp + "." + body)>}: the
     * timestamp is signed with the body, so a receiver that refuses one older
     * than five minutes refuses a replay too (see {@code docs/api.md}).
     */
    public static String signature(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            mac.update(body);
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal());
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}
