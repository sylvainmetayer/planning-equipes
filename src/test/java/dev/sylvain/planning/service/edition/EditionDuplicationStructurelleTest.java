package dev.sylvain.planning.service.edition;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.service.edition.EditionRepository.TableToCopy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Every column of a duplicated table is copied, or excused here with a reason.
 *
 * <p>{@code EditionRepository.TABLES_A_COPIER} names its columns by hand, and a
 * migration adding one does not touch it. Nothing held the two together, so
 * three columns drifted out of a duplication without a word: {@code
 * heure_debut_soiree} (V74) and {@code parametres_solveur.mail_fin_resolution}
 * came back at their default, and {@code typologie.ninja} with them — the
 * duplicated edition no longer knew which typologie was the polyvalent one.
 * None of it fails anything at duplication time: the {@code INSERT … SELECT}
 * is valid, the column simply takes its {@code DEFAULT}.</p>
 *
 * <p>The existing duplication tests could not catch it either: they assert a
 * handful of hand-picked fields, so a column nobody thought to name is a column
 * nobody checks. This one reads the schema instead — what a migration actually
 * created — which is the only source that cannot drift from itself.</p>
 */
@QuarkusTest
class EditionDuplicationStructurelleTest {

    /**
     * Columns deliberately left out of a copy, and why. A copy is a <b>new</b>
     * row of a <b>new</b> edition: some columns must be reborn rather than
     * carried over.
     */
    private static final Map<String, String> NON_COPIEES_AVEC_MOTIF = Map.of(
            "typologie.modifie_le", "concurrency stamp: a copied row is a fresh write, so it defaults to now()",
            "emplacement.modifie_le", "same",
            "stand.modifie_le", "same",
            "animateur.modifie_le", "same",
            "journee_type.modifie_le", "same",
            "animateur.access_token",
                    "credential: the default mints a fresh one per edition, so one person's espace "
                            + "link is never shared between two editions",
            "animateur.abonnement_token", "same, for the ICS subscription",
            "stand_indisponibilite.id",
                    "surrogate key on its own sequence — unlike stand_horaire, whose composite "
                            + "(edition_id, id) primary key is why its id IS copied",
            "stand_ouverture.id", "same");

    /** Columns every copy writes itself, outside the declared list. */
    private static final Set<String> ECRITES_PAR_LA_COPIE = Set.of("edition_id");

    @Inject
    DataSource dataSource;

    @Test
    void everyColumnOfACopiedTableIsEitherCopiedOrExcusedWithAReason() throws SQLException {
        List<String> oubliees = new ArrayList<>();
        for (TableToCopy table : EditionRepository.TABLES_A_COPIER) {
            Set<String> declarees = new LinkedHashSet<>();
            for (String colonne : table.colonnes().split(",")) {
                declarees.add(colonne.strip());
            }
            for (String colonne : columnsOf(table.nom())) {
                String cle = table.nom() + "." + colonne;
                if (!declarees.contains(colonne)
                        && !ECRITES_PAR_LA_COPIE.contains(colonne)
                        && !NON_COPIEES_AVEC_MOTIF.containsKey(cle)) {
                    oubliees.add(cle);
                }
            }
        }

        assertThat(oubliees)
                .as("colonnes qu'une duplication d'édition laisse retomber sur leur DEFAUT sans le dire — "
                        + "ajoutez-les à TABLES_A_COPIER, ou à NON_COPIEES_AVEC_MOTIF avec la raison")
                .isEmpty();
    }

    /** A column excused by name must still exist: a stale reason hides the next real gap. */
    @Test
    void everyExcusedColumnStillExists() throws SQLException {
        List<String> disparues = new ArrayList<>();
        for (String cle : NON_COPIEES_AVEC_MOTIF.keySet()) {
            String table = cle.substring(0, cle.indexOf('.'));
            String colonne = cle.substring(cle.indexOf('.') + 1);
            if (!columnsOf(table).contains(colonne)) {
                disparues.add(cle);
            }
        }

        assertThat(disparues)
                .as("colonnes excusées qui n'existent plus : la ligne survit à ce qu'elle décrivait")
                .isEmpty();
    }

    private Set<String> columnsOf(String table) throws SQLException {
        Set<String> colonnes = new LinkedHashSet<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("""
                        SELECT column_name FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = ?
                        ORDER BY ordinal_position""")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    colonnes.add(rs.getString("column_name"));
                }
            }
        }
        return colonnes;
    }
}
