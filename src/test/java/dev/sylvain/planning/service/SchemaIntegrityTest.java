package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.edition.EditionService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What the schema refuses by itself (V126): a seat on another edition's
 * timeslot, the delete behaviour of the references that were left without a
 * key, and the timestamps that carry a zone. Raw SQL on purpose — the Java
 * guards sit above the database and would hide a missing key.
 */
@QuarkusTest
class SchemaIntegrityTest {

    @Inject
    DataSource dataSource;

    @Inject
    EditionService editionService;

    @Inject
    EditionContext editionContext;

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    PlanningPersistenceService persistenceService;

    private String a;
    private String b;
    private long creneauA;

    @BeforeEach
    void setUp() throws SQLException {
        a = editionService.create(new Edition(null, "Schema A", false, null)).getId();
        b = editionService.create(new Edition(null, "Schema B", false, null)).getId();
        creneauA = editionContext.executeIn(
                a,
                () -> referenceDataService
                        .createCreneau(new Creneau(
                                null, 1, LocalDate.of(2026, 7, 14), LocalTime.of(10, 0), LocalTime.of(12, 0)))
                        .getId());
        update("INSERT INTO stand (edition_id, id, nom, effectif_min, effectif_max) VALUES (?, 'S1', 'S', 1, 1)", a);
        update("INSERT INTO stand (edition_id, id, nom, effectif_min, effectif_max) VALUES (?, 'S1', 'S', 1, 1)", b);
    }

    @AfterEach
    void tearDown() {
        editionService.delete(a);
        editionService.delete(b);
    }

    @Test
    void aSeatCannotPointAtAnotherEditionsTimeslot() throws SQLException {
        String insert = "INSERT INTO poste_affectation (edition_id, id, stand_id, creneau_id) VALUES (?, 'x', 'S1', ?)";
        assertThatThrownBy(() -> update(insert, b, creneauA))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("poste_affectation_creneau_fkey");
        update(insert, a, creneauA);
    }

    @Test
    void theLandingUpsertCannotTouchAnotherEditionsTimeslot() {
        editionContext.executeIn(b, () -> {
            Stand stand = new Stand("S1", "S", Set.of(), 1, 1, false);
            Creneau stolen = new Creneau(creneauA, 1, LocalDate.of(2030, 1, 1), LocalTime.of(1, 0), LocalTime.of(2, 0));
            Animateur animateur = new Animateur("A1", "P", "N", LocalDate.of(1990, 1, 1), false);
            PlanningEvenement plan = new PlanningEvenement(
                    stolen.getDate(), List.of(animateur), List.of(new PosteAffectation("p1", stand, stolen)));
            assertThatThrownBy(() -> persistenceService.persist(plan))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("another edition");
        });
        assertThat(scalar("SELECT date_creneau::text FROM creneau WHERE id = ?", creneauA))
                .isEqualTo("2026-07-14");
    }

    @Test
    void deletingATimeslotCascadesOrNullsOnlyTheReferencingColumn() throws SQLException {
        update(
                "INSERT INTO animateur (edition_id, id, prenom, nom, date_naissance) VALUES (?, 'A1', 'P', 'N', DATE '1990-01-01')",
                a);
        update(
                "INSERT INTO demande_echange (edition_id, id, demandeur_id, cible_id, creneau_id, stand_id,"
                        + " creneau_cible_id, stand_cible_id) VALUES (?, 'D1', 'A1', 'A1', ?, 'S1', ?, 'S1')",
                a,
                creneauA,
                creneauA);
        update(
                "INSERT INTO verrouillage_planning (edition_id, id, type, creneau_id, creneau_date,"
                        + " creneau_heure_debut, creneau_heure_fin)"
                        + " VALUES (?, 'V1', 'CRENEAU', ?, DATE '2026-07-14', TIME '10:00', TIME '12:00')",
                a,
                creneauA);
        update(
                "INSERT INTO contrainte_ad_hoc (edition_id, id, type, creneau_id) VALUES (?, 'C1', 'INDISPONIBILITE_FORCEE', ?)",
                a,
                creneauA);

        update("DELETE FROM creneau WHERE id = ?", creneauA);

        assertThat(count("demande_echange", a)).isZero();
        assertThat(scalar("SELECT edition_id FROM verrouillage_planning WHERE id = 'V1' AND edition_id = '" + a + "'"))
                .isEqualTo(a);
        assertThat(scalar("SELECT creneau_id FROM verrouillage_planning WHERE id = 'V1' AND edition_id = '" + a + "'"))
                .isNull();
        assertThat(scalar("SELECT edition_id FROM contrainte_ad_hoc WHERE id = 'C1' AND edition_id = '" + a + "'"))
                .isEqualTo(a);
        assertThat(scalar("SELECT creneau_id FROM contrainte_ad_hoc WHERE id = 'C1' AND edition_id = '" + a + "'"))
                .isNull();
    }

    @Test
    void deletingTheTargetStandCascadesAndTheOtherReferencesNullTheirColumn() throws SQLException {
        update(
                "INSERT INTO animateur (edition_id, id, prenom, nom, date_naissance) VALUES (?, 'A1', 'P', 'N', DATE '1990-01-01')",
                a);
        update("INSERT INTO stand (edition_id, id, nom, effectif_min, effectif_max) VALUES (?, 'S2', 'S', 1, 1)", a);
        update(
                "INSERT INTO demande_echange (edition_id, id, demandeur_id, cible_id, creneau_id, stand_id,"
                        + " stand_cible_id) VALUES (?, 'D1', 'A1', 'A1', ?, 'S1', 'S2')",
                a,
                creneauA);
        update("DELETE FROM stand WHERE edition_id = ? AND id = 'S2'", a);
        assertThat(count("demande_echange", a)).isZero();

        // seat continuation, ad hoc adjustment behind a teammate declaration, snapshots
        update(
                "INSERT INTO poste_affectation (edition_id, id, stand_id, creneau_id) VALUES (?, 'o', 'S1', ?)",
                a,
                creneauA);
        update(
                "INSERT INTO poste_affectation (edition_id, id, stand_id, creneau_id, suite_de) VALUES (?, 'c', 'S1', ?, 'o')",
                a,
                creneauA);
        update("DELETE FROM poste_affectation WHERE edition_id = ? AND id = 'o'", a);
        assertThat(scalar("SELECT suite_de FROM poste_affectation WHERE id = 'c' AND edition_id = '" + a + "'"))
                .isNull();
        assertThat(count("poste_affectation", a)).isEqualTo(1);

        update("INSERT INTO contrainte_ad_hoc (edition_id, id, type) VALUES (?, 'C1', 'INDISPONIBILITE_FORCEE')", a);
        update(
                "INSERT INTO declaration_coequipier (edition_id, id, animateur_id, nature, coequipiers, contrainte_id)"
                        + " VALUES (?, 'DC1', 'A1', 'COVOITURAGE', 'x', 'C1')",
                a);
        update("DELETE FROM contrainte_ad_hoc WHERE edition_id = ? AND id = 'C1'", a);
        assertThat(scalar("SELECT contrainte_id FROM declaration_coequipier WHERE id = 'DC1' AND edition_id = '" + a
                        + "'"))
                .isNull();
        assertThat(count("declaration_coequipier", a)).isEqualTo(1);

        long snapshot = Long.parseLong(scalar("INSERT INTO plan_snapshot (edition_id, libelle, contenu) VALUES ('" + a
                + "', 'l', '[]'::jsonb) RETURNING id::text"));
        update("UPDATE animateur SET plan_notifie_id = ? WHERE edition_id = ? AND id = 'A1'", snapshot, a);
        update(
                "INSERT INTO planning_resolution (edition_id, resolu_le, snapshot_avant_solve_id) VALUES (?, now(), ?)",
                a,
                snapshot);
        update("DELETE FROM plan_snapshot WHERE id = ?", snapshot);
        assertThat(scalar("SELECT plan_notifie_id FROM animateur WHERE id = 'A1' AND edition_id = '" + a + "'"))
                .isNull();
        assertThat(scalar("SELECT snapshot_avant_solve_id FROM planning_resolution WHERE edition_id = '" + a + "'"))
                .isNull();
    }

    @Test
    void aSnapshotReferenceMustExist() {
        assertThatThrownBy(() -> update(
                        "INSERT INTO planning_resolution (edition_id, resolu_le, snapshot_avant_solve_id)"
                                + " VALUES (?, now(), 999999)",
                        a))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void theFormerlyNaiveTimestampsCarryAZone() {
        assertThat(scalar("""
                        SELECT count(*) FROM information_schema.columns
                        WHERE table_schema = 'public' AND data_type = 'timestamp with time zone'
                          AND (table_name, column_name) IN (('edition', 'cree_le'), ('contrainte_ad_hoc', 'cree_le'),
                               ('verrouillage_planning', 'cree_le'), ('planning_resolution', 'resolu_le'),
                               ('demande_echange', 'cible_decide_le'))""")).isEqualTo("5");
    }

    private void update(String sql, Object... params) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }

    private String scalar(String sql, Object... params) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private int count(String table, String edition) {
        return Integer.parseInt(scalar("SELECT count(*) FROM " + table + " WHERE edition_id = ?", edition));
    }
}
