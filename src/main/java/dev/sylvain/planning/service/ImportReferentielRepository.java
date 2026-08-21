package dev.sylvain.planning.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The one write that spans every referential at once: replacing the whole
 * reference dataset with the one a scenario (or a sample planning) carries.
 *
 * <p>It borrows a single connection and hands it to each family repository in
 * turn, because the import is atomic by nature — a half-imported edition, with
 * new créneaux but the old stands still pointing at them, is not a state any
 * screen could make sense of. That is also why the family repositories expose
 * their {@code …Tx} upserts to this package: they take the caller's connection
 * instead of opening their own.</p>
 */
@ApplicationScoped
public class ImportReferentielRepository {

    @Inject
    DataSource dataSource;

    @Inject
    JdbcEditionScope scope;

    @Inject
    StandRepository standRepository;

    @Inject
    EmplacementRepository emplacementRepository;

    @Inject
    CreneauRepository creneauRepository;

    @Inject
    AnimateurRepository animateurRepository;

    @Inject
    TypologieRepository typologieRepository;

    @Inject
    ContrainteAdHocRepository contrainteRepository;

    public void importFromPlanning(PlanningFestival planning) {
        if (planning == null) {
            return;
        }
        Map<String, Stand> standsById = new LinkedHashMap<>();
        Map<Long, Creneau> creneauxById = new LinkedHashMap<>();
        if (planning.getPostes() != null) {
            for (PosteAffectation poste : planning.getPostes()) {
                if (poste.getStand() != null) {
                    standsById.putIfAbsent(poste.getStand().getId(), poste.getStand());
                }
                if (poste.getCreneau() != null) {
                    creneauxById.putIfAbsent(poste.getCreneau().getId(), poste.getCreneau());
                }
            }
        }
        List<Animateur> animateurs = planning.getAnimateurs() != null ? planning.getAnimateurs() : List.of();
        List<ContrainteAdHoc> contraintes = planning.getContraintesAdHoc() != null
                ? planning.getContraintesAdHoc()
                : List.of();

        scope.ecrire("Failed to import reference data from planning", connection -> {
            // verrouillage_planning goes with the assignments it freezes: the
            // reference dataset is being replaced, so the validated planning
            // those locks protected no longer exists — and planning_resolution
            // goes with it, so nothing keeps claiming "résolu le …" over an
            // empty plan. Stands and animateurs, on the other hand, are
            // DIFFED, not wiped: the file's rows are upserted (which keeps
            // an existing animateur's access token, sessions and demandes
            // alive) and only the rows absent from the file are deleted.
            for (String table : List.of("contrainte_animateur", "contrainte_ad_hoc", "verrouillage_planning",
                    "poste_affectation", "planning_resolution")) {
                // Table names come from the literal list above, never from user input.
                try (PreparedStatement ps = scope.prepareScoped(connection,
                        "DELETE FROM " + table + " WHERE edition_id = ?")) {
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = scope.prepareScoped(connection,
                    "DELETE FROM creneau WHERE edition_id = ?")) {
                ps.executeUpdate();
            }
            Map<Long, Long> idsRemap = new LinkedHashMap<>();
            for (Creneau creneau : creneauxById.values()) {
                Long ancienId = creneau.getId();
                Long nouvelId = creneauRepository.insertCreneauTx(connection, creneau);
                idsRemap.put(ancienId, nouvelId);
            }
            Map<String, Emplacement> emplacementsById = new LinkedHashMap<>();
            for (Stand stand : standsById.values()) {
                if (stand.getEmplacement() != null) {
                    emplacementsById.putIfAbsent(stand.getEmplacement().getId(), stand.getEmplacement());
                }
            }
            for (Emplacement emplacement : emplacementsById.values()) {
                emplacementRepository.upsertEmplacementTx(connection, emplacement);
            }
            for (TypologieItem typologie : typologieRepository.derivedTypologies(standsById.values(), animateurs)) {
                typologieRepository.upsertTypologieDerivee(connection, typologie);
            }
            for (Stand stand : standsById.values()) {
                standRepository.upsertStand(connection, stand);
            }
            for (Animateur animateur : animateurs) {
                if (animateur != null && animateur.getId() != null) {
                    animateurRepository.upsertAnimateur(connection, animateur, true);
                }
            }
            // Rows the file does not carry are the only ones deleted — for
            // an animateur that also drops, by cascade, their demandes
            // d'échange, sessions and access code.
            supprimerAbsentsTx(connection, "stand", standsById.keySet());
            supprimerAbsentsTx(connection, "animateur", animateurs.stream()
                    .filter(animateur -> animateur != null && animateur.getId() != null)
                    .map(Animateur::getId)
                    .collect(java.util.stream.Collectors.toSet()));
            for (ContrainteAdHoc contrainte : contraintes) {
                if (contrainte != null && contrainte.getId() != null) {
                    if (contrainte.getCreneau() != null && contrainte.getCreneau().getId() != null) {
                        Long nouvelId = idsRemap.get(contrainte.getCreneau().getId());
                        if (nouvelId != null) {
                            contrainte.getCreneau().setId(nouvelId);
                        }
                    }
                    contrainteRepository.upsertContrainte(connection, contrainte);
                }
            }
        });
    }

    public ImpactImport compterImpactImport() {
        try (Connection connection = dataSource.getConnection()) {
            int animateurs = compter(connection, "animateur");
            int stands = compter(connection, "stand");
            int postes = compter(connection, "poste_affectation");
            int verrous = compter(connection, "verrouillage_planning");
            int demandes = 0;
            int enAttente = 0;
            try (PreparedStatement ps = scope.prepareScoped(connection,
                    """
                    SELECT COUNT(*) AS total, COUNT(*) FILTER (WHERE statut = 'PROPOSEE') AS en_attente
                    FROM demande_echange
                    WHERE edition_id = ?""");
                    ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    demandes = rs.getInt("total");
                    enAttente = rs.getInt("en_attente");
                }
            }
            boolean resolu = false;
            try (PreparedStatement ps = scope.prepareScoped(connection,
                    "SELECT 1 FROM planning_resolution WHERE edition_id = ?");
                    ResultSet rs = ps.executeQuery()) {
                resolu = rs.next();
            }
            return new ImpactImport(animateurs, stands, postes, resolu, demandes, enAttente, verrous);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to measure the import impact", e);
        }
    }

    /** COUNT(*) of one edition-scoped table from the literal list of {@link #compterImpactImport}. */
    private int compter(Connection connection, String table) throws SQLException {
        try (PreparedStatement ps = scope.prepareScoped(connection,
                "SELECT COUNT(*) FROM " + table + " WHERE edition_id = ?");
                // nosemgrep: java.lang.security.audit.formatted-sql-string.formatted-sql-string
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * Deletes the rows of {@code table} (whitelisted by its two callers in
     * {@link #importFromPlanning}: {@code stand} or {@code animateur}) whose id
     * is not in {@code idsConserves} — the diff half of the import: what the
     * file does not name disappears, what it names was upserted in place.
     */
    private void supprimerAbsentsTx(Connection connection, String table, java.util.Set<String> idsConserves)
            throws SQLException {
        List<String> absents = new ArrayList<>();
        try (PreparedStatement ps = scope.prepareScoped(connection,
                "SELECT id FROM " + table + " WHERE edition_id = ?");
                // nosemgrep: java.lang.security.audit.formatted-sql-string.formatted-sql-string
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String id = rs.getString("id");
                if (!idsConserves.contains(id)) {
                    absents.add(id);
                }
            }
        }
        if (absents.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = scope.prepareScoped(connection,
                "DELETE FROM " + table + " WHERE edition_id = ? AND id = ?")) {
            for (String id : absents) {
                ps.setString(2, id);
                // nosemgrep: java.lang.security.audit.formatted-sql-string.formatted-sql-string
                ps.addBatch();
            }
            // nosemgrep: java.lang.security.audit.formatted-sql-string.formatted-sql-string
            ps.executeBatch();
        }
    }
}
