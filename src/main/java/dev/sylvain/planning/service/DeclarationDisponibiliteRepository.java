package dev.sylvain.planning.service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.sylvain.planning.domain.DeclarationDisponibilite;
import dev.sylvain.planning.domain.StatutDeclaration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * SQL of the self-service declarations (issue #291) and of the collection
 * window that gates them — one repository per family, every statement scoped
 * through {@link JdbcEditionScope}.
 *
 * <p>Lists are stored as one value per line in a {@code TEXT} column, the same
 * shape {@code demande_echange.contraintes_violees} uses: a declaration is the
 * frozen snapshot of what somebody typed, so it must survive the removal of a
 * game category it names instead of being cascaded away with it.</p>
 */
@ApplicationScoped
public class DeclarationDisponibiliteRepository {

    @Inject
    JdbcEditionScope scope;

    private static final String COLONNES = "id, animateur_id, jours_indisponibles, souhaits, commentaire, "
            + "statut, commentaire_admin, cree_le, decide_le";

    /* --------------------------- Collection window --------------------------- */

    /**
     * The admin's collection window for the current edition.
     *
     * @param ouverte the master switch; {@code false} while no row exists —
     *                unlike the foire au planning, a route that <b>writes</b>
     *                from the public Internet does not open by omission
     * @param debut   first day the espace accepts a declaration, {@code null}
     *                for "as soon as it is open"
     * @param fin     last day it does, {@code null} for "until it is closed"
     */
    public record FenetreCollecte(boolean ouverte, LocalDate debut, LocalDate fin) {

        /** What an edition nobody configured looks like. */
        public static FenetreCollecte closed() {
            return new FenetreCollecte(false, null, null);
        }

        /** True on {@code jour}: the switch is on and the day is inside the bounds. */
        public boolean openOn(LocalDate jour) {
            return ouverte
                    && (debut == null || !jour.isBefore(debut))
                    && (fin == null || !jour.isAfter(fin));
        }
    }

    public FenetreCollecte fenetre() {
        return scope.read("Failed to read the collection window", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection,
                    "SELECT collecte_ouverte, date_debut, date_fin FROM parametres_collecte "
                            + "WHERE edition_id = ?");
                    ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return FenetreCollecte.closed();
                }
                return new FenetreCollecte(rs.getBoolean("collecte_ouverte"),
                        date(rs, "date_debut"), date(rs, "date_fin"));
            }
        });
    }

    public void saveFenetre(FenetreCollecte fenetre) {
        scope.write("Failed to store the collection window", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection,
                    """
                    INSERT INTO parametres_collecte (edition_id, collecte_ouverte, date_debut, date_fin)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (edition_id) DO UPDATE SET
                        collecte_ouverte = EXCLUDED.collecte_ouverte,
                        date_debut = EXCLUDED.date_debut,
                        date_fin = EXCLUDED.date_fin""")) {
                ps.setBoolean(2, fenetre.ouverte());
                ps.setObject(3, fenetre.debut());
                ps.setObject(4, fenetre.fin());
                ps.executeUpdate();
            }
        });
    }

    /* ----------------------------- Declarations ----------------------------- */

    /** Every declaration of the edition, most recent first, whatever their statut. */
    public List<DeclarationDisponibilite> list() {
        return select("", null);
    }

    /** One animateur's declarations, most recent first — what their espace shows back. */
    public List<DeclarationDisponibilite> listForAnimateur(String animateurId) {
        return select(" AND animateur_id = ?", animateurId);
    }

    public Optional<DeclarationDisponibilite> byId(String id) {
        return select(" AND id = ?", id).stream().findFirst();
    }

    /** The single pending declaration of an animateur, if they have one. */
    public Optional<DeclarationDisponibilite> pendingOf(String animateurId) {
        return listForAnimateur(animateurId).stream()
                .filter(declaration -> declaration.getStatut() == StatutDeclaration.EN_ATTENTE)
                .findFirst();
    }

    /**
     * Replaces the animateur's pending declaration with {@code declaration}, in
     * one transaction: the deletion and the insertion must not leave a window
     * where the partial unique index sees two pending rows.
     */
    public void replacePending(DeclarationDisponibilite declaration) {
        scope.write("Failed to store the declaration of animateur " + declaration.getAnimateurId(),
                connection -> {
                    try (PreparedStatement suppression = scope.prepareScoped(connection,
                            "DELETE FROM declaration_disponibilite WHERE edition_id = ? "
                                    + "AND animateur_id = ? AND statut = 'EN_ATTENTE'")) {
                        suppression.setString(2, declaration.getAnimateurId());
                        suppression.executeUpdate();
                    }
                    try (PreparedStatement insertion = scope.prepareScoped(connection,
                            "INSERT INTO declaration_disponibilite (edition_id, " + COLONNES + ") "
                                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                        insertion.setString(2, declaration.getId());
                        insertion.setString(3, declaration.getAnimateurId());
                        insertion.setString(4, lines(declaration.getJoursIndisponibles().stream()
                                .map(LocalDate::toString)
                                .toList()));
                        insertion.setString(5, lines(declaration.getSouhaits()));
                        insertion.setString(6, declaration.getCommentaire());
                        insertion.setString(7, declaration.getStatut().name());
                        insertion.setString(8, declaration.getCommentaireAdmin());
                        insertion.setTimestamp(9, Timestamp.from(declaration.getCreeLe()));
                        insertion.setTimestamp(10, declaration.getDecideLe() == null
                                ? null
                                : Timestamp.from(declaration.getDecideLe()));
                        insertion.executeUpdate();
                    }
                });
    }

    /**
     * Records the admin's verdict on one pending declaration.
     *
     * @return how many rows moved: {@code 0} means the declaration was already
     *         decided, which the service turns into a refusal rather than a
     *         silent no-op
     */
    public int decide(String id, StatutDeclaration statut, String commentaireAdmin, Timestamp decideLe) {
        return scope.writeAndReturn("Failed to record the decision on declaration " + id, connection -> {
            // Not prepareScoped: the SET clause claims the first placeholders,
            // so the edition_id predicate is bound explicitly.
            try (PreparedStatement ps = connection.prepareStatement(
                    """
                    UPDATE declaration_disponibilite
                    SET statut = ?, commentaire_admin = ?, decide_le = ?
                    WHERE edition_id = ? AND id = ? AND statut = 'EN_ATTENTE'""")) {
                ps.setString(1, statut.name());
                ps.setString(2, commentaireAdmin);
                ps.setTimestamp(3, decideLe);
                ps.setString(4, scope.editionId());
                ps.setString(5, id);
                return ps.executeUpdate();
            }
        });
    }

    private List<DeclarationDisponibilite> select(String predicatSupplementaire, String parametre) {
        // Only the column list and the extra predicate are concatenated, and both
        // are literals from this class's own call sites; the value that varies
        // travels as a bound parameter below.
        String sql = "SELECT " + COLONNES + " FROM declaration_disponibilite WHERE edition_id = ?"
                + predicatSupplementaire + " ORDER BY cree_le DESC, id";
        return scope.read("Failed to list the availability declarations", connection -> {
            List<DeclarationDisponibilite> declarations = new ArrayList<>();
            try (PreparedStatement ps = scope.prepareScoped(connection, sql)) {
                if (parametre != null) {
                    ps.setString(2, parametre);
                }
                // nosemgrep: java.lang.security.audit.formatted-sql-string.formatted-sql-string
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        declarations.add(read(rs));
                    }
                }
            }
            return declarations;
        });
    }

    private static DeclarationDisponibilite read(ResultSet rs) throws SQLException {
        DeclarationDisponibilite declaration = new DeclarationDisponibilite();
        declaration.setId(rs.getString("id"));
        declaration.setAnimateurId(rs.getString("animateur_id"));
        declaration.setJoursIndisponibles(new ArrayList<>(values(rs.getString("jours_indisponibles")).stream()
                .map(LocalDate::parse)
                .toList()));
        declaration.setSouhaits(new ArrayList<>(values(rs.getString("souhaits"))));
        declaration.setCommentaire(rs.getString("commentaire"));
        declaration.setStatut(StatutDeclaration.valueOf(rs.getString("statut")));
        declaration.setCommentaireAdmin(rs.getString("commentaire_admin"));
        Timestamp creeLe = rs.getTimestamp("cree_le");
        declaration.setCreeLe(creeLe == null ? null : creeLe.toInstant());
        Timestamp decideLe = rs.getTimestamp("decide_le");
        declaration.setDecideLe(decideLe == null ? null : decideLe.toInstant());
        return declaration;
    }

    /** {@code null} rather than an empty string, so "nothing" reads the same on the way back. */
    private static String lines(List<String> values) {
        return values == null || values.isEmpty() ? null : String.join("\n", values);
    }

    private static List<String> values(String stored) {
        return stored == null || stored.isBlank() ? List.of() : List.of(stored.split("\n"));
    }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        java.sql.Date date = rs.getDate(column);
        return date == null ? null : date.toLocalDate();
    }
}
