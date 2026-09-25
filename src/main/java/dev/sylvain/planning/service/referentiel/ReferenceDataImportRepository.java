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
import java.util.Collection;
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

    private static final String WHERE_EDITION = " WHERE edition_id = ?";

    private final DataSource dataSource;

    private final JdbcEditionScope scope;

    private final StandRepository standRepository;

    private final EmplacementRepository emplacementRepository;

    private final CreneauRepository creneauRepository;

    private final AnimateurRepository animateurRepository;

    private final TypologieRepository typologieRepository;

    private final ContrainteAdHocRepository contrainteRepository;

    @Inject
    public ReferenceDataImportRepository(
            DataSource dataSource,
            JdbcEditionScope scope,
            StandRepository standRepository,
            EmplacementRepository emplacementRepository,
            CreneauRepository creneauRepository,
            AnimateurRepository animateurRepository,
            TypologieRepository typologieRepository,
            ContrainteAdHocRepository contrainteRepository) {
        this.dataSource = dataSource;
        this.scope = scope;
        this.standRepository = standRepository;
        this.emplacementRepository = emplacementRepository;
        this.creneauRepository = creneauRepository;
        this.animateurRepository = animateurRepository;
        this.typologieRepository = typologieRepository;
        this.contrainteRepository = contrainteRepository;
    }

    public void importFromPlanning(PlanningEvenement planning) {
        if (planning == null) {
            return;
        }
        Map<String, Stand> standsById = new LinkedHashMap<>();
        Map<Long, Creneau> creneauxById = new LinkedHashMap<>();
        if (planning.getPostes() != null) {
            collectStandsAndTimeslots(planning.getPostes(), standsById, creneauxById);
        }
        List<Animateur> animateurs = planning.getAnimateurs() != null ? planning.getAnimateurs() : List.of();
        List<ContrainteAdHoc> contraintes =
                planning.getContraintesAdHoc() != null ? planning.getContraintesAdHoc() : List.of();

        scope.write("Failed to import reference data from planning", connection -> {
            wipeTx(connection);
            Map<Long, Long> idsRemap = insertTimeslotsTx(connection, creneauxById.values());
            upsertEmplacementsTx(connection, standsById.values());
            for (TypologieItem typologie : typologieRepository.derivedTypologies(standsById.values(), animateurs)) {
                typologieRepository.insertTypologieDerivee(connection, typologie);
            }
            for (Stand stand : standsById.values()) {
                standRepository.upsertStand(connection, stand);
            }
            Set<String> animateurIds = upsertAnimateursTx(connection, animateurs);
            // Rows the file does not carry are the only ones deleted — for
            // an animateur that also drops, by cascade, their demandes
            // d'échange, sessions and access code.
            deleteMissingTx(connection, "stand", standsById.keySet());
            deleteMissingTx(connection, "animateur", animateurIds);
            upsertContraintesTx(connection, contraintes, idsRemap);
        });
    }

    private static void collectStandsAndTimeslots(
            List<PosteAffectation> postes, Map<String, Stand> standsById, Map<Long, Creneau> creneauxById) {
        for (PosteAffectation poste : postes) {
            if (poste.getStand() != null) {
                standsById.putIfAbsent(poste.getStand().getId(), poste.getStand());
            }
            if (poste.getCreneau() != null) {
                creneauxById.putIfAbsent(poste.getCreneau().getId(), poste.getCreneau());
            }
        }
    }

    /**
     * verrouillage_planning goes with the assignments it freezes: the
     * reference dataset is being replaced, so the validated planning
     * those locks protected no longer exists — and planning_resolution
     * goes with it, so nothing keeps claiming "résolu le …" over an
     * empty plan. Stands and animateurs, on the other hand, are
     * DIFFED, not wiped: the file's rows are upserted (which keeps
     * an existing animateur's access token, sessions and demandes
     * alive) and only the rows absent from the file are deleted.
     */
    private void wipeTx(Connection connection) throws SQLException {
        for (String table : List.of(
                "contrainte_animateur",
                "contrainte_ad_hoc",
                "verrouillage_planning",
                "validation_journee",
                "poste_affectation",
                "planning_resolution")) {
            // Table names come from the literal list above, never from user input.
            try (PreparedStatement ps = scope.prepareScoped(connection, "DELETE FROM " + table + WHERE_EDITION)) {
                ps.executeUpdate();
            }
        }
        try (PreparedStatement ps = scope.prepareScoped(connection, "DELETE FROM creneau WHERE edition_id = ?")) {
            ps.executeUpdate();
        }
    }

    /** Inserts the créneaux afresh; the map is each one's old id to its new one. */
    private Map<Long, Long> insertTimeslotsTx(Connection connection, Collection<Creneau> creneaux) throws SQLException {
        Map<Long, Long> idsRemap = new LinkedHashMap<>();
        for (Creneau creneau : creneaux) {
            Long ancienId = creneau.getId();
            Long nouvelId = creneauRepository.insertCreneauTx(connection, creneau);
            idsRemap.put(ancienId, nouvelId);
        }
        return idsRemap;
    }

    private void upsertEmplacementsTx(Connection connection, Collection<Stand> stands) throws SQLException {
        Map<String, Emplacement> emplacementsById = new LinkedHashMap<>();
        for (Stand stand : stands) {
            if (stand.getEmplacement() != null) {
                emplacementsById.putIfAbsent(stand.getEmplacement().getId(), stand.getEmplacement());
            }
        }
        for (Emplacement emplacement : emplacementsById.values()) {
            emplacementRepository.upsertEmplacementTx(connection, emplacement);
        }
    }

    /** Upserts the identified animateurs, and returns their ids. */
    private Set<String> upsertAnimateursTx(Connection connection, List<Animateur> animateurs) throws SQLException {
        for (Animateur animateur : animateurs) {
            if (animateur != null && animateur.getId() != null) {
                animateurRepository.upsertAnimateur(connection, animateur, true);
            }
        }
        return animateurs.stream()
                .filter(animateur -> animateur != null && animateur.getId() != null)
                .map(Animateur::getId)
                .collect(Collectors.toSet());
    }

    /** Upserts the identified exceptions, their créneau renumbered onto the one just inserted. */
    private void upsertContraintesTx(Connection connection, List<ContrainteAdHoc> contraintes, Map<Long, Long> idsRemap)
            throws SQLException {
        for (ContrainteAdHoc contrainte : contraintes) {
            if (contrainte != null && contrainte.getId() != null) {
                remapCreneau(contrainte, idsRemap);
                contrainteRepository.upsertContrainte(connection, contrainte);
            }
        }
    }

    private static void remapCreneau(ContrainteAdHoc contrainte, Map<Long, Long> idsRemap) {
        if (contrainte.getCreneau() == null || contrainte.getCreneau().getId() == null) {
            return;
        }
        Long nouvelId = idsRemap.get(contrainte.getCreneau().getId());
        if (nouvelId != null) {
            contrainte.getCreneau().setId(nouvelId);
        }
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
        try (PreparedStatement ps = scope.prepareScoped(connection, "SELECT COUNT(*) FROM " + table + WHERE_EDITION);
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
        try (PreparedStatement ps = scope.prepareScoped(connection, "SELECT id FROM " + table + WHERE_EDITION);
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
