package dev.sylvain.planning.service.webhook;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The two webhook tables: the configuration and the journal of deliveries.
 *
 * <p>Instance-wide, and therefore <b>not</b> routed through
 * {@code JdbcEditionScope}, like {@code backup_settings}: a webhook belongs to
 * the instance and the payload names the edition it speaks of (ADR 0074).</p>
 */
@ApplicationScoped
public class WebhookRepository {

    private final DataSource dataSource;

    @Inject
    public WebhookRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /* ------------------------------ Configuration ----------------------------- */

    public List<Webhook> list() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("""
                        SELECT id, nom, format, url, secret_chiffre, chat_id, evenements, actif, cree_le, modifie_le
                        FROM webhook ORDER BY cree_le, id""");
                ResultSet rs = ps.executeQuery()) {
            List<Webhook> webhooks = new ArrayList<>();
            while (rs.next()) {
                webhooks.add(webhook(rs));
            }
            return webhooks;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list the webhooks", e);
        }
    }

    public Optional<Webhook> find(String id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("""
                        SELECT id, nom, format, url, secret_chiffre, chat_id, evenements, actif, cree_le, modifie_le
                        FROM webhook WHERE id = ?""")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(webhook(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read a webhook", e);
        }
    }

    /** One stored secret, for the boot to check the key opens it; empty without a webhook. */
    public Optional<String> anySecret() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement("SELECT secret_chiffre FROM webhook ORDER BY cree_le, id LIMIT 1");
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read a webhook secret", e);
        }
    }

    /** Whether any webhook exists — what decides that a missing key must stop the boot. */
    public boolean any() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("SELECT EXISTS (SELECT 1 FROM webhook)");
                ResultSet rs = ps.executeQuery()) {
            return rs.next() && rs.getBoolean(1);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count the webhooks", e);
        }
    }

    public void insert(Webhook webhook) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO webhook (id, nom, format, url, secret_chiffre, chat_id, evenements, actif)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, webhook.id());
            bindFields(connection, ps, webhook, 2);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create a webhook", e);
        }
    }

    public void update(Webhook webhook) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("""
                        UPDATE webhook SET nom = ?, format = ?, url = ?, secret_chiffre = ?, chat_id = ?,
                        evenements = ?, actif = ?, modifie_le = now()
                        WHERE id = ?""")) {
            bindFields(connection, ps, webhook, 1);
            ps.setString(8, webhook.id());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update a webhook", e);
        }
    }

    /** Its deliveries go with it ({@code ON DELETE CASCADE}): a retry under way finds nothing to send. */
    public boolean delete(String id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("DELETE FROM webhook WHERE id = ?")) {
            ps.setString(1, id);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete a webhook", e);
        }
    }

    /** The active webhooks subscribed to {@code event}. */
    public List<Webhook> subscribers(WebhookEvent event) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("""
                        SELECT id, nom, format, url, secret_chiffre, chat_id, evenements, actif, cree_le, modifie_le
                        FROM webhook WHERE actif AND ? = ANY (evenements) ORDER BY cree_le, id""")) {
            ps.setString(1, event.code());
            try (ResultSet rs = ps.executeQuery()) {
                List<Webhook> webhooks = new ArrayList<>();
                while (rs.next()) {
                    webhooks.add(webhook(rs));
                }
                return webhooks;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list the subscribers of a webhook event", e);
        }
    }

    private static void bindFields(Connection connection, PreparedStatement ps, Webhook webhook, int from)
            throws SQLException {
        ps.setString(from, webhook.name());
        ps.setString(from + 1, webhook.format().name());
        ps.setString(from + 2, webhook.url());
        ps.setString(from + 3, webhook.encryptedSecret());
        ps.setString(from + 4, webhook.chatId());
        Array events = connection.createArrayOf(
                "text", webhook.events().stream().map(WebhookEvent::code).toArray());
        ps.setArray(from + 5, events);
        ps.setBoolean(from + 6, webhook.active());
    }

    private static Webhook webhook(ResultSet rs) throws SQLException {
        Array array = rs.getArray("evenements");
        List<WebhookEvent> events = new ArrayList<>();
        if (array != null) {
            for (Object code : (Object[]) array.getArray()) {
                // A code this version no longer knows is ignored rather than fatal.
                WebhookEvent.ofCode(String.valueOf(code)).ifPresent(events::add);
            }
        }
        return new Webhook(
                rs.getString("id"),
                rs.getString("nom"),
                WebhookFormat.valueOf(rs.getString("format")),
                rs.getString("url"),
                rs.getString("secret_chiffre"),
                rs.getString("chat_id"),
                events,
                rs.getBoolean("actif"),
                instant(rs.getTimestamp("cree_le")),
                instant(rs.getTimestamp("modifie_le")));
    }

    /* -------------------------------- Deliveries ------------------------------ */

    /** Queues one delivery, due at once. */
    public void enqueue(UUID id, String webhookId, String event, String payload) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO webhook_livraison (id, webhook_id, evenement, payload, statut, prochain_essai)
                        VALUES (?, ?, ?, CAST(? AS json), 'PENDING', now())""")) {
            ps.setObject(1, id);
            ps.setString(2, webhookId);
            ps.setString(3, event);
            ps.setString(4, payload);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to queue a webhook delivery", e);
        }
    }

    /**
     * Queues the delivery of « Envoyer un test », <b>already leased</b> by the
     * caller that is about to send it: inserted then claimed in two
     * statements, the minute sweep could take it in between.
     *
     * @return the lease, to record the attempt under
     */
    public Instant enqueueLeased(UUID id, String webhookId, String event, String payload) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO webhook_livraison
                        (id, webhook_id, evenement, payload, statut, prochain_essai, en_cours_depuis)
                        VALUES (?, ?, ?, CAST(? AS json), 'PENDING', now(), now())
                        RETURNING en_cours_depuis""")) {
            ps.setObject(1, id);
            ps.setString(2, webhookId);
            ps.setString(3, event);
            ps.setString(4, payload);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getTimestamp(1).toInstant();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to queue a webhook delivery", e);
        }
    }

    /**
     * Takes the lease of one delivery whose attempt is due, so the immediate
     * attempt, the minute sweep and « Renvoyer » never send it twice. A lease
     * older than {@code lease} is one a restart abandoned, and is taken over.
     *
     * @return the lease this call holds — the instant it was written, which
     *         {@link #recordAttempt} requires — or empty when the delivery is
     *         not pending, not due yet, or leased by somebody else
     */
    public Optional<Instant> claim(UUID id, Duration lease) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("""
                        UPDATE webhook_livraison SET en_cours_depuis = now()
                        WHERE id = ? AND statut = 'PENDING' AND prochain_essai <= now()
                        AND (en_cours_depuis IS NULL OR en_cours_depuis < now() - CAST(? AS interval))
                        RETURNING en_cours_depuis""")) {
            ps.setObject(1, id);
            ps.setString(2, interval(lease));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getTimestamp(1).toInstant()) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to claim a webhook delivery", e);
        }
    }

    /**
     * Up to {@code limit} deliveries whose next attempt is due, oldest first —
     * <b>candidates</b>, not leased: the sweep leases each one through
     * {@link #claim} right before its own attempt, so no lease lapses while
     * the rows before it are sent.
     */
    public List<UUID> dueIds(int limit, Duration lease) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("""
                        SELECT id FROM webhook_livraison
                        WHERE statut = 'PENDING' AND prochain_essai <= now()
                        AND (en_cours_depuis IS NULL OR en_cours_depuis < now() - CAST(? AS interval))
                        ORDER BY prochain_essai
                        LIMIT ?""")) {
            ps.setString(1, interval(lease));
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<UUID> ids = new ArrayList<>();
                while (rs.next()) {
                    ids.add(rs.getObject("id", UUID.class));
                }
                return ids;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list the due webhook deliveries", e);
        }
    }

    public Optional<WebhookDelivery> findDelivery(UUID id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("""
                        SELECT id, webhook_id, evenement, payload, tentative, statut, code_http, duree_ms, erreur,
                        prochain_essai, derniere_tentative_le, cree_le
                        FROM webhook_livraison WHERE id = ?""")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(delivery(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read a webhook delivery", e);
        }
    }

    /**
     * Records how an attempt ended and releases the lease — only under the
     * lease the attempt was given: one that lapsed and was taken over belongs
     * to another attempt now, whose outcome this one must not overwrite.
     *
     * @return whether the attempt was recorded
     */
    public boolean recordAttempt(
            UUID id,
            Instant lease,
            int attempts,
            DeliveryStatus status,
            Integer httpStatus,
            long durationMs,
            String error,
            Instant nextAttemptAt) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("""
                        UPDATE webhook_livraison
                        SET tentative = ?, statut = ?, code_http = ?, duree_ms = ?, erreur = ?, prochain_essai = ?,
                        en_cours_depuis = NULL, derniere_tentative_le = now()
                        WHERE id = ? AND en_cours_depuis = ?""")) {
            ps.setInt(1, attempts);
            ps.setString(2, status.name());
            if (httpStatus == null) {
                ps.setNull(3, Types.INTEGER);
            } else {
                ps.setInt(3, httpStatus);
            }
            ps.setLong(4, durationMs);
            ps.setString(5, error);
            ps.setTimestamp(6, nextAttemptAt == null ? null : Timestamp.from(nextAttemptAt));
            ps.setObject(7, id);
            ps.setTimestamp(8, Timestamp.from(lease));
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record a webhook attempt", e);
        }
    }

    /**
     * Puts a delivery back at the start of its schedule — « Renvoyer ». Refused
     * while an attempt holds its lease, so a resend never doubles a send in
     * flight.
     *
     * @return whether the delivery was reset
     */
    public boolean resetForResend(UUID id, Duration lease) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("""
                        UPDATE webhook_livraison
                        SET statut = 'PENDING', tentative = 0, prochain_essai = now(), code_http = NULL,
                        duree_ms = NULL, erreur = NULL, en_cours_depuis = NULL
                        WHERE id = ?
                        AND (en_cours_depuis IS NULL OR en_cours_depuis < now() - CAST(? AS interval))""")) {
            ps.setObject(1, id);
            ps.setString(2, interval(lease));
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to reset a webhook delivery", e);
        }
    }

    /** The journal of one webhook, newest first. */
    public List<WebhookDelivery> deliveries(String webhookId, int limit) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("""
                        SELECT id, webhook_id, evenement, payload, tentative, statut, code_http, duree_ms, erreur,
                        prochain_essai, derniere_tentative_le, cree_le
                        FROM webhook_livraison WHERE webhook_id = ?
                        ORDER BY cree_le DESC, id
                        LIMIT ?""")) {
            ps.setString(1, webhookId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<WebhookDelivery> deliveries = new ArrayList<>();
                while (rs.next()) {
                    deliveries.add(delivery(rs));
                }
                return deliveries;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list the deliveries of a webhook", e);
        }
    }

    /** The latest delivery of each webhook, for the list. */
    public Map<String, WebhookDelivery> latestDeliveries() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("""
                        SELECT DISTINCT ON (webhook_id) id, webhook_id, evenement, payload, tentative, statut,
                        code_http, duree_ms, erreur, prochain_essai, derniere_tentative_le, cree_le
                        FROM webhook_livraison
                        ORDER BY webhook_id, cree_le DESC""");
                ResultSet rs = ps.executeQuery()) {
            Map<String, WebhookDelivery> latest = new HashMap<>();
            while (rs.next()) {
                WebhookDelivery delivery = delivery(rs);
                latest.put(delivery.webhookId(), delivery);
            }
            return latest;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the latest webhook deliveries", e);
        }
    }

    /** Drops the deliveries older than {@code before}, whatever their state. */
    public int purgeBefore(Instant before) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("DELETE FROM webhook_livraison WHERE cree_le < ?")) {
            ps.setTimestamp(1, Timestamp.from(Objects.requireNonNull(before)));
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to purge the webhook deliveries", e);
        }
    }

    private static WebhookDelivery delivery(ResultSet rs) throws SQLException {
        int code = rs.getInt("code_http");
        Integer httpStatus = rs.wasNull() ? null : code;
        long duration = rs.getLong("duree_ms");
        Long durationMs = rs.wasNull() ? null : duration;
        return new WebhookDelivery(
                rs.getObject("id", UUID.class),
                rs.getString("webhook_id"),
                rs.getString("evenement"),
                rs.getString("payload"),
                rs.getInt("tentative"),
                DeliveryStatus.valueOf(rs.getString("statut")),
                httpStatus,
                durationMs,
                rs.getString("erreur"),
                instant(rs.getTimestamp("prochain_essai")),
                instant(rs.getTimestamp("derniere_tentative_le")),
                instant(rs.getTimestamp("cree_le")));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** A lease as the text PostgreSQL casts to an {@code interval}. */
    private static String interval(Duration lease) {
        return lease.toSeconds() + " seconds";
    }
}
