package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Every column that names a row by its id is either held by a foreign key, or
 * classified here with what it carries.
 *
 * <p>Business ids are to become database-generated
 * ({@code docs/decisions/0049-identifiants-metier-generes-par-la-base.md}), and
 * the migration that does it has to rewrite every place an id is written down.
 * The declared foreign keys are not the risk: a migration that forgets one does
 * not apply. The risk is the columns that <b>cite an id without declaring
 * it</b> — the animateur id inside the deduplication key of the planned
 * notifications, the stand and créneau ids inside the JSONB of a published
 * plan, the perimeter of a solver job replayed at startup. Nothing signals
 * those: the migration passes, and three weeks later a reminder goes out a
 * second time to somebody who already had it, or the published-plan stability
 * rule finds nothing to match and lets the solver reshuffle a plan people have
 * already read.</p>
 *
 * <p>So this test reads the schema and refuses an id carrier nobody examined.
 * It decides nothing — a column may perfectly well be left as it is — but that
 * choice now has to be written down next to its reason. Held to the letter, it
 * already produced two lines the hand-written analysis had missed:
 * {@code validation_journee.id} and {@code prereglage_consigne.id}, two
 * server-side UUIDs that no inventory named.</p>
 *
 * <p><b>The suffix is not what makes a carrier</b>, and the scan says so:
 * {@code stand_typologie.typologie}, {@code animateur_competence.typologie} and
 * {@code animateur_souhait.typologie} hold a typologie id under a name that
 * ends in nothing. They are covered here by their foreign key, which is the
 * only reading that cannot drift. The name scan exists for what has none.</p>
 */
@QuarkusTest
class PorteursDIdentifiantStructurelleTest {

    /** Flyway's own bookkeeping: never a business table. */
    private static final String FLYWAY_HISTORY = "flyway_schema_history";

    /**
     * Columns that name a row by its id and that no foreign key holds, each
     * with what it carries and what a migration of that referential owes it.
     *
     * <p>Ordered as the referentials are: the ids the chantier is about first,
     * then the ones already generated, then what only looks like an id.</p>
     */
    private static final Map<String, String> SANS_CLE_ETRANGERE = new LinkedHashMap<>();

    static {
        // The business ids the chantier is about: client-supplied today,
        // IDENTITY tomorrow, one lot each.
        SANS_CLE_ETRANGERE.put(
                "edition.id",
                "business id, lot 6: partition key of nearly every table, plus the "
                        + "X-Edition-Id header, the browser's localStorage and the MCP « edition » argument");
        SANS_CLE_ETRANGERE.put(
                "animateur.id",
                "business id, lot 5: the most cited of all — espace, tokens, planned "
                        + "notifications, publication trace, journal, MCP");
        SANS_CLE_ETRANGERE.put(
                "stand.id",
                "business id, lot 4: also written in the JSONB of every snapshot and in "
                        + "demande_echange.stand_cible_id");
        SANS_CLE_ETRANGERE.put(
                "typologie.id",
                "business id, lot 3: cited by the competences grid, by the stands import and by "
                        + "the pending declarations");
        SANS_CLE_ETRANGERE.put(
                "emplacement.id",
                "business id, lot 1: one incoming foreign key only, which is why it "
                        + "opens the chantier and validates the pattern");
        SANS_CLE_ETRANGERE.put(
                "contrainte_ad_hoc.id", "business id, lot 2: cited by contrainte_animateur and by the journal");

        // Server-side UUIDs: kept as they are (decision 0049, D4).
        SANS_CLE_ETRANGERE.put(
                "verrouillage_planning.id",
                "server-side UUID, kept: a global (id) key, never "
                        + "composite, that a shared sequence would expose between editions");
        SANS_CLE_ETRANGERE.put(
                "demande_echange.id",
                "server-side UUID, kept: espace routes carry it, and a "
                        + "sequential number would reveal the volume and ease probing");
        SANS_CLE_ETRANGERE.put("declaration_disponibilite.id", "server-side UUID, kept: same espace surface");
        SANS_CLE_ETRANGERE.put("solver_job.id", "server-side UUID, kept: never typed, never shown");
        SANS_CLE_ETRANGERE.put("validation_journee.id", "server-side UUID, kept: never typed, never shown");
        SANS_CLE_ETRANGERE.put(
                "prereglage_consigne.id",
                "server-side UUID, kept: the preset is named by its nom, which carries its own unique index");

        // Already database-generated: nothing to migrate, everything to keep
        // in IDENTITY_TABLES so a restored dump does not collide.
        SANS_CLE_ETRANGERE.put("creneau.id", "IDENTITY since V11, the precedent this chantier follows");
        SANS_CLE_ETRANGERE.put("journee_type.id", "IDENTITY");
        SANS_CLE_ETRANGERE.put("journee_type_vacation.id", "IDENTITY");
        SANS_CLE_ETRANGERE.put("stand_horaire.id", "IDENTITY");
        SANS_CLE_ETRANGERE.put("stand_horaire_fenetre.id", "IDENTITY");
        SANS_CLE_ETRANGERE.put("stand_indisponibilite.id", "IDENTITY");
        SANS_CLE_ETRANGERE.put("stand_ouverture.id", "IDENTITY");
        SANS_CLE_ETRANGERE.put("plan_snapshot.id", "IDENTITY");
        SANS_CLE_ETRANGERE.put("publication_destinataire.id", "IDENTITY");
        SANS_CLE_ETRANGERE.put("kpi_historique.id", "IDENTITY");
        SANS_CLE_ETRANGERE.put("journal_action.id", "IDENTITY");

        // Ids written down without a foreign key: the ones a migration has to
        // rewrite by hand, and the reason this test exists.
        SANS_CLE_ETRANGERE.put(
                "poste_affectation.id",
                "« poste-N », renumbered at every build of the problem: unstable by design, migrated by nobody");
        SANS_CLE_ETRANGERE.put(
                "kpi_historique.edition_id",
                "names an edition without a foreign key, so the curves "
                        + "keep their history when an edition is dropped — to rewrite with the edition ids");
        SANS_CLE_ETRANGERE.put(
                "notification_planifiee.animateur_id",
                "names the recipient; an id left behind sends "
                        + "the day-before reminder to somebody who already had it");
        SANS_CLE_ETRANGERE.put(
                "publication_destinataire.animateur_id",
                "names the recipient of a past publication; "
                        + "an id left behind makes the diff find nobody, and everybody reads « votre planning a changé »");
        SANS_CLE_ETRANGERE.put(
                "demande_echange.stand_cible_id",
                "names the stand a directed swap aims at, without a "
                        + "foreign key so that the request survives the stand");
        SANS_CLE_ETRANGERE.put("demande_echange.creneau_cible_id", "same, for the créneau");
        SANS_CLE_ETRANGERE.put(
                "animateur.plan_notifie_id",
                "names the snapshot this person was last written about; "
                        + "snapshot ids do not move, but the column is an id all the same");
        SANS_CLE_ETRANGERE.put("planning_resolution.snapshot_avant_solve_id", "names the pre-solve snapshot, same");
        SANS_CLE_ETRANGERE.put(
                "journal_action.entite_id",
                "polymorphic: the kind is in « entite », so a rewrite is possible one value of "
                        + "that column at a time, never blind. Left unrewritten, the history stops "
                        + "resolving the names of past lines");
        SANS_CLE_ETRANGERE.put("journal_action.acteur_id", "same, for whoever acted");

        // Named id, but not one.
        SANS_CLE_ETRANGERE.put("backup_settings.id", "not an id: a boolean guard keeping the table to a single row");
        SANS_CLE_ETRANGERE.put("horloge_jour_j.id", "not an id: the same guard, as an integer");
    }

    /**
     * Payload columns that carry ids <b>inside</b> their content, where no
     * scan can find them: a migration rewrites these by parsing, or not at
     * all.
     */
    private static final Map<String, String> PORTEURS_ENFOUIS = Map.of(
            "plan_snapshot.contenu",
            "JSONB: standId, animateurId and creneauId of every seat. Left alone, the published "
                    + "plan matches nothing — the stability rule sees the whole grid as changed — and "
                    + "no older snapshot can be restored, since a restore requires every id it names "
                    + "to still exist",
            "solver_job.perimetre",
            "JSONB: animateurIds and standIds of a scoped solve, replayed at startup — a job that "
                    + "outlives the migration would aim at an empty or wrong perimeter",
            "notification_planifiee.cle",
            "text: « animateurId|date » and « animateurId|publieLe », the deduplication key. Left "
                    + "alone it matches nothing, and the day-before reminders and the relances go out "
                    + "a second time",
            "declaration_disponibilite.souhaits",
            "text: one typologie id per line. A declaration still pending when the migration runs "
                    + "would cite typologies nobody knows");

    /**
     * The other payloads, and why nothing has to be rewritten in them. Listed
     * so that a new JSONB column cannot join them by default.
     */
    private static final Map<String, String> CHARGES_SANS_ID = Map.of(
            "plan_snapshot.consignes", "dates, hours and a motif — a consigne is not named by an id",
            "plan_snapshot.kpi", "aggregates, plus constraint names as keys",
            "kpi_historique.kpi", "same",
            "publication_destinataire.changements", "sentences written for the reader",
            "publication_destinataire.demandes", "same");

    @Inject
    DataSource dataSource;

    /**
     * The guard: an id column with no foreign key is either classified, or it
     * is one nobody has examined.
     */
    @Test
    void everyIdColumnWithoutAForeignKeyIsClassified() throws SQLException {
        List<String> nonClassees = new ArrayList<>();
        for (Colonne colonne : colonnes()) {
            if (colonne.looksLikeAnId()
                    && !colonne.porteeParUneCleEtrangere()
                    && !SANS_CLE_ETRANGERE.containsKey(colonne.cle())) {
                nonClassees.add(colonne.cle());
            }
        }

        assertThat(nonClassees).as("""
                        colonnes qui nomment une ligne par son id sans qu'aucune clé étrangère \
                        ne les tienne, et que personne n'a examinées. Ajoutez chacune à \
                        SANS_CLE_ETRANGERE avec ce qu'elle porte et ce qu'une migration de ce \
                        référentiel lui doit — quitte à dire qu'elle ne lui doit rien. Voir \
                        docs/decisions/0049-identifiants-metier-generes-par-la-base.md.""").isEmpty();
    }

    /**
     * The same for what a scan cannot read: a new JSONB column says whether it
     * carries ids, rather than being assumed not to.
     */
    @Test
    void everyJsonPayloadSaysWhetherItCarriesIds() throws SQLException {
        List<String> nonClassees = new ArrayList<>();
        for (Colonne colonne : colonnes()) {
            if (colonne.isJson()
                    && !PORTEURS_ENFOUIS.containsKey(colonne.cle())
                    && !CHARGES_SANS_ID.containsKey(colonne.cle())) {
                nonClassees.add(colonne.cle());
            }
        }

        assertThat(nonClassees).as("""
                        charges utiles JSONB non classées : une migration ne peut pas réécrire ce \
                        qu'elle ne sait pas lire. Ajoutez chacune à PORTEURS_ENFOUIS avec les ids \
                        qu'elle contient, ou à CHARGES_SANS_ID avec la raison qu'elle n'en \
                        contient pas.""").isEmpty();
    }

    /** A line that survives what it described hides the next real gap. */
    @Test
    void everyClassifiedColumnStillExists() throws SQLException {
        Set<String> vivantes = new LinkedHashSet<>();
        for (Colonne colonne : colonnes()) {
            vivantes.add(colonne.cle());
        }

        List<String> disparues = new ArrayList<>();
        for (String cle : SANS_CLE_ETRANGERE.keySet()) {
            if (!vivantes.contains(cle)) {
                disparues.add(cle);
            }
        }
        for (String cle : PORTEURS_ENFOUIS.keySet()) {
            if (!vivantes.contains(cle)) {
                disparues.add(cle);
            }
        }
        for (String cle : CHARGES_SANS_ID.keySet()) {
            if (!vivantes.contains(cle)) {
                disparues.add(cle);
            }
        }

        assertThat(disparues)
                .as("colonnes classées qui n'existent plus : la ligne survit à ce qu'elle décrivait")
                .isEmpty();
    }

    /**
     * The mirror of the guard: a column that has since gained a foreign key is
     * held by the schema itself, and its line here only adds a reason to read.
     */
    @Test
    void noClassifiedColumnHasSinceGainedAForeignKey() throws SQLException {
        List<String> tenues = new ArrayList<>();
        for (Colonne colonne : colonnes()) {
            if (colonne.porteeParUneCleEtrangere() && SANS_CLE_ETRANGERE.containsKey(colonne.cle())) {
                tenues.add(colonne.cle());
            }
        }

        assertThat(tenues)
                .as("colonnes classées « sans clé étrangère » qui en ont gagné une depuis : "
                        + "le schéma les tient, retirez leur ligne de SANS_CLE_ETRANGERE")
                .isEmpty();
    }

    /** One column of one business table, as the schema describes it. */
    private record Colonne(String table, String nom, String type, boolean porteeParUneCleEtrangere) {

        String cle() {
            return table + "." + nom;
        }

        /**
         * Named as an id: {@code id} or {@code …_id}. Deliberately by name and
         * not by type — {@code confirmation_planning.animateur_id} is
         * {@code TEXT} where its twin is {@code VARCHAR}, and a scan keyed on
         * the type would have let that one through.
         */
        boolean looksLikeAnId() {
            return "id".equals(nom) || nom.endsWith("_id");
        }

        boolean isJson() {
            return "jsonb".equals(type) || "json".equals(type);
        }
    }

    private List<Colonne> colonnes() throws SQLException {
        List<Colonne> colonnes = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("""
                        SELECT c.table_name, c.column_name, c.data_type,
                               EXISTS (SELECT 1
                                       FROM information_schema.key_column_usage k
                                       JOIN information_schema.table_constraints tc
                                         ON tc.constraint_name = k.constraint_name
                                        AND tc.constraint_schema = k.constraint_schema
                                        AND tc.constraint_type = 'FOREIGN KEY'
                                       WHERE k.table_schema = c.table_schema
                                         AND k.table_name = c.table_name
                                         AND k.column_name = c.column_name) AS portee
                        FROM information_schema.columns c
                        JOIN information_schema.tables t
                          ON t.table_schema = c.table_schema
                         AND t.table_name = c.table_name
                         AND t.table_type = 'BASE TABLE'
                        WHERE c.table_schema = 'public'
                        ORDER BY c.table_name, c.ordinal_position""")) {
            while (rs.next()) {
                String table = rs.getString("table_name");
                if (!FLYWAY_HISTORY.equals(table)) {
                    colonnes.add(new Colonne(
                            table, rs.getString("column_name"), rs.getString("data_type"), rs.getBoolean("portee")));
                }
            }
        }
        assertThat(colonnes)
                .as("aucune colonne lue : le scan ne reconnaît plus rien, et tous les tests ci-dessus passeraient")
                .isNotEmpty();
        return colonnes;
    }
}
