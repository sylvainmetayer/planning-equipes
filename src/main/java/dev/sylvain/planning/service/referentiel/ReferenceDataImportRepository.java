package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.JdbcEditionScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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
public class ReferenceDataImportRepository {

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

    public void importFromPlanning(PlanningEvenement planning) {
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
        List<ContrainteAdHoc> contraintes =
                planning.getContraintesAdHoc() != null ? planning.getContraintesAdHoc() : List.of();

        scope.write("Failed to import reference data from planning", connection -> {
            // verrouillage_planning goes with the assignments it freezes: the
            // reference dataset is being replaced, so the validated planning
            // those locks protected no longer exists — and planning_resolution
            // goes with it, so nothing keeps claiming "résolu le …" over an
            // empty plan. Stands and animateurs, on the other hand, are
            // DIFFED, not wiped: the file's rows are upserted (which keeps
            // an existing animateur's access token, sessions and demandes
            // alive) and only the rows absent from the file are deleted.
            for (String table : List.of(
                    "contrainte_animateur",
                    "contrainte_ad_hoc",
                    "verrouillage_planning",
                    "validation_journee",
                    "poste_affectation",
                    "planning_resolution")) {
                // Table names come from the literal list above, never from user input.
                try (PreparedStatement ps =
                        scope.prepareScoped(connection, "DELETE FROM " + table + " WHERE edition_id = ?")) {
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = scope.prepareScoped(connection, "DELETE FROM creneau WHERE edition_id = ?")) {
                ps.executeUpdate();
            }
            Map<Long, Long> idsRemap = new LinkedHashMap<>();
            for (Creneau creneau : creneauxById.values()) {
                Long ancienId = creneau.getId();
                Long nouvelId = creneauRepository.insertCreneauTx(connection, creneau);
                idsRemap.put(ancienId, nouvelId);
            }
            // The emplacements the file names, reconciled by their code and not
            // by the id the file carries: that id is a local reference (decision
            // 0049, D3), and the row it must land on is the one already carrying
            // that code in this edition. A code naming nothing is a new row, and
            // the generated id is written back onto the object the stands point
            // at — so the upsert below stores the id the database just minted,
            // never the file's.
            Map<String, Emplacement> emplacementsByCode = new LinkedHashMap<>();
            for (Stand stand : standsById.values()) {
                Emplacement emplacement = stand.getEmplacement();
                if (emplacement != null && emplacement.getCode() != null) {
                    emplacementsByCode.putIfAbsent(emplacement.getCode(), emplacement);
                }
            }
            for (Emplacement emplacement : emplacementsByCode.values()) {
                Long existant = emplacementRepository.idOfCode(connection, emplacement.getCode());
                if (existant == null) {
                    emplacement.setId(null);
                    emplacementRepository.insertEmplacementTx(connection, emplacement);
                } else {
                    emplacement.setId(existant);
                    // No precondition: an import deliberately overwrites what it
                    // finds, like every other row it replaces here.
                    emplacement.setModifieLe(null);
                    emplacementRepository.updateEmplacementTx(connection, emplacement);
                }
            }
            // Every stand shares the object its code resolved to, so a stand
            // whose emplacement was named by a second stand points at the same
            // row rather than at the file's reference.
            for (Stand stand : standsById.values()) {
                Emplacement emplacement = stand.getEmplacement();
                if (emplacement != null && emplacement.getCode() != null) {
                    stand.setEmplacement(emplacementsByCode.get(emplacement.getCode()));
                } else if (emplacement != null) {
                    stand.setEmplacement(null);
                }
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
            deleteMissingTx(connection, "stand", standsById.keySet());
            deleteMissingTx(
                    connection,
                    "animateur",
                    animateurs.stream()
                            .filter(animateur -> animateur != null && animateur.getId() != null)
                            .map(Animateur::getId)
                            .collect(Collectors.toSet()));
            for (ContrainteAdHoc contrainte : contraintes) {
                if (contrainte != null && contrainte.getId() != null) {
                    if (contrainte.getCreneau() != null
                            && contrainte.getCreneau().getId() != null) {
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

    public ImportImpact countImportImpact() {
        try (Connection connection = dataSource.getConnection()) {
            int animateurs = count(connection, "animateur");
            int stands = count(connection, "stand");
            int postes = count(connection, "poste_affectation");
            int verrous = count(connection, "verrouillage_planning");
            int demandes = 0;
            int enAttente = 0;
            try (PreparedStatement ps = scope.prepareScoped(connection, """
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
            try (PreparedStatement ps =
                            scope.prepareScoped(connection, "SELECT 1 FROM planning_resolution WHERE edition_id = ?");
                    ResultSet rs = ps.executeQuery()) {
                resolu = rs.next();
            }
            return new ImportImpact(animateurs, stands, postes, resolu, demandes, enAttente, verrous);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to measure the import impact", e);
        }
    }

    /** COUNT(*) of one edition-scoped table from the literal list of {@link #countImportImpact}. */
    private int count(Connection connection, String table) throws SQLException {
        try (PreparedStatement ps =
                        scope.prepareScoped(connection, "SELECT COUNT(*) FROM " + table + " WHERE edition_id = ?");
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
    private void deleteMissingTx(Connection connection, String table, Set<String> idsConserves) throws SQLException {
        List<String> missing = new ArrayList<>();
        try (PreparedStatement ps =
                        scope.prepareScoped(connection, "SELECT id FROM " + table + " WHERE edition_id = ?");
                // nosemgrep: java.lang.security.audit.formatted-sql-string.formatted-sql-string
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String id = rs.getString("id");
                if (!idsConserves.contains(id)) {
                    missing.add(id);
                }
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        try (PreparedStatement ps =
                scope.prepareScoped(connection, "DELETE FROM " + table + " WHERE edition_id = ? AND id = ?")) {
            for (String id : missing) {
                ps.setString(2, id);
                // nosemgrep: java.lang.security.audit.formatted-sql-string.formatted-sql-string
                ps.addBatch();
            }
            // nosemgrep: java.lang.security.audit.formatted-sql-string.formatted-sql-string
            ps.executeBatch();
        }
    }
}
