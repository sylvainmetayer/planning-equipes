package dev.sylvain.planning.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

import javax.sql.DataSource;

import dev.sylvain.planning.domain.Animateur;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Passwordless authentication of the espace animateur (issue #165 follow-up):
 * the link (jeton) identifies the animateur, but since the espace serves the
 * planning for download, possessing the link alone is no longer enough. The
 * second factor is the animateur's mailbox — a 6-digit code sent to the
 * address on their fiche, exchanged for a durable session carried by an
 * HttpOnly cookie.
 *
 * <p>Nothing secret is stored in clear: the code and the session token are
 * both SHA-256 hashed (the code salted with the animateur's jeton). One code
 * at a time per animateur — asking again replaces it; 5 attempts, 10 minutes.
 * An animateur without an email address cannot open the espace: their address
 * IS the second factor, the interface tells them to contact the organisation.
 * Session and code lookups are edition-scoped through the caller's
 * {@code EditionContext} (resolved from the jeton, as everywhere in the
 * espace).</p>
 */
@ApplicationScoped
public class EspaceAccesService {

    public static final Duration VALIDITE_CODE = Duration.ofMinutes(10);
    public static final Duration VALIDITE_SESSION = Duration.ofDays(30);
    private static final int TENTATIVES_MAX = 5;

    private final SecureRandom random = new SecureRandom();

    @Inject
    DataSource dataSource;

    @Inject
    JdbcEditionScope scope;

    @Inject
    EditionContext editionContext;

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    MailService mailService;

    @Inject
    LimiteurDemandesCode limiteurDemandesCode;

    /** What the "send me a code" call tells the interface. */
    public record CodeEnvoye(String emailMasque) {
    }

    /** Too many codes asked for without using any: carries the delay before the next try. */
    public static class TropDeDemandes extends RuntimeException {

        private final long secondesAvantNouvelEssai;

        TropDeDemandes(long secondesAvantNouvelEssai) {
            super("Trop de codes demandés sans en utiliser aucun : réessayez dans "
                    + Math.max(1, (secondesAvantNouvelEssai + 59) / 60) + " minute(s).");
            this.secondesAvantNouvelEssai = secondesAvantNouvelEssai;
        }

        public long secondesAvantNouvelEssai() {
            return secondesAvantNouvelEssai;
        }
    }

    /**
     * Generates and mails a fresh code to the animateur. Replaces any pending
     * one. Throws {@link IllegalArgumentException} when the fiche carries no
     * email address — the business message is shown as-is, and
     * {@link TropDeDemandes} when codes pile up unused (see
     * {@link LimiteurDemandesCode}).
     */
    public CodeEnvoye demanderCode(String animateurId) {
        Animateur animateur = animateurRequis(animateurId);
        if (animateur.getEmail() == null || animateur.getEmail().isBlank()) {
            throw new ErreurMetier.Invalide(
                    "Aucune adresse e-mail n'est enregistrée pour vous : contactez l'organisation "
                            + "pour la faire ajouter à votre fiche.");
        }
        // After the address check, before the send: a record without an address
        // consumes nothing, and every mail actually sent is counted.
        LimiteurDemandesCode.Verdict verdict = limiteurDemandesCode.demander(cleDebit(animateurId));
        if (!verdict.autorise()) {
            throw new TropDeDemandes(verdict.secondesAvantNouvelEssai());
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection,
                        """
                        INSERT INTO espace_acces (edition_id, animateur_id, code_hash, expire_le, tentatives_restantes)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (edition_id, animateur_id)
                        DO UPDATE SET code_hash = EXCLUDED.code_hash, expire_le = EXCLUDED.expire_le,
                        tentatives_restantes = EXCLUDED.tentatives_restantes""")) {
            ps.setString(2, animateurId);
            ps.setString(3, hacher(code + animateur.getJetonAcces()));
            ps.setTimestamp(4, Timestamp.from(Instant.now().plus(VALIDITE_CODE)));
            ps.setInt(5, TENTATIVES_MAX);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to store the espace access code", e);
        }
        // After the store, deliberately: a mail we cannot send must not leave a
        // valid code behind that the animateur never received? It does — but a
        // replaced code is strictly safer than the previous one, and the next
        // request will replace it again. The send failure itself propagates.
        mailService.envoyerCodeAcces(animateur.getEmail(), animateur.getPrenom(), code);
        return new CodeEnvoye(masquer(animateur.getEmail()));
    }

    /**
     * Exchanges a valid code for a durable session. Returns the opaque session
     * token to put in the cookie. Throws {@link IllegalArgumentException} on a
     * wrong, expired or exhausted code.
     */
    public String ouvrirSession(String animateurId, String code) {
        Animateur animateur = animateurRequis(animateurId);
        verifierCode(animateurId, code, animateur.getJetonAcces());
        byte[] brut = new byte[32];
        random.nextBytes(brut);
        String session = Base64.getUrlEncoder().withoutPadding().encodeToString(brut);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection,
                        """
                        INSERT INTO espace_session (edition_id, session_hash, animateur_id, expire_le)
                        VALUES (?, ?, ?, ?)""")) {
            ps.setString(2, hacher(session));
            ps.setString(3, animateurId);
            ps.setTimestamp(4, Timestamp.from(Instant.now().plus(VALIDITE_SESSION)));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to open an espace session", e);
        }
        supprimerCode(animateurId);
        // The code was used: the run of requests with no follow-up stops there.
        limiteurDemandesCode.oublier(cleDebit(animateurId));
        return session;
    }

    /**
     * True when the cookie value carries a live session of {@code animateurId}
     * in the current edition. Like the jeton, the cookie alone must identify
     * the session — but it is only ever accepted for the animateur the jeton
     * of the URL resolves to, so a stolen cookie without the link is useless
     * (and vice versa).
     */
    public boolean sessionValide(String cookieValue, String animateurId) {
        if (cookieValue == null || cookieValue.isBlank()) {
            return false;
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection,
                        """
                        SELECT animateur_id, expire_le
                        FROM espace_session
                        WHERE edition_id = ? AND session_hash = ?""")) {
            ps.setString(2, hacher(cookieValue));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        && animateurId.equals(rs.getString("animateur_id"))
                        && rs.getTimestamp("expire_le").toInstant().isAfter(Instant.now());
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to check the espace session", e);
        }
    }

    private void verifierCode(String animateurId, String code, String jeton) {
        if (code == null || code.isBlank()) {
            throw new ErreurMetier.Invalide("Code manquant");
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection,
                        """
                        UPDATE espace_acces
                        SET tentatives_restantes = tentatives_restantes - 1
                        WHERE edition_id = ? AND animateur_id = ? AND tentatives_restantes > 0 AND expire_le > now()
                        RETURNING code_hash""")) {
            ps.setString(2, animateurId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new ErreurMetier.Invalide(
                            "Code expiré ou trop d'essais : demandez un nouveau code.");
                }
                if (!MessageDigest.isEqual(
                        rs.getString("code_hash").getBytes(StandardCharsets.UTF_8),
                        hacher(code.trim() + jeton).getBytes(StandardCharsets.UTF_8))) {
                    throw new ErreurMetier.Invalide("Code incorrect.");
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to check the espace access code", e);
        }
    }

    private void supprimerCode(String animateurId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection,
                        "DELETE FROM espace_acces WHERE edition_id = ? AND animateur_id = ?")) {
            ps.setString(2, animateurId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clear the espace access code", e);
        }
    }

    private Animateur animateurRequis(String animateurId) {
        return referenceDataService.listAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals(animateurId))
                .findFirst()
                .orElseThrow(() -> new ErreurMetier.Invalide("Animateur inconnu : " + animateurId));
    }

    /** {@code a•••@example.org} — enough to recognise one's address, nothing more. */
    static String masquer(String email) {
        int arobase = email.indexOf('@');
        if (arobase <= 0) {
            return "•••";
        }
        return email.charAt(0) + "•••" + email.substring(arobase);
    }

    private static String hacher(String valeur) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(valeur.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** The rate is counted per animateur AND per edition, like the rest of the espace. */
    private String cleDebit(String animateurId) {
        return editionContext.editionIdCourant() + "/" + animateurId;
    }

}
