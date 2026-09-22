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
 *
 * <p><b>What this test does not see.</b> Two scans, and no more: the columns
 * <i>named</i> as an id, and the columns that can hold a value of any length —
 * {@code JSON}, {@code JSONB}, {@code TEXT}, unbounded {@code VARCHAR} — which
 * is the shape a payload takes. A {@code VARCHAR(n)} is read as a named field
 * and is not asked to classify itself; today two of them hold a list all the
 * same ({@code stand_horaire.dates} and {@code stand_horaire.jours_semaine},
 * dates and weekdays, neither an id), so a future payload that carries ids
 * under a bounded type would pass. Write one as {@code TEXT} or {@code JSONB}
 * and the net closes over it; write it as {@code VARCHAR(n)} and only a reader
 * will catch it. Nothing here reads a <i>value</i> either: a classification
 * says what a column is meant to hold, not what somebody put in it.</p>
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
                "generated identity, lot 1 done (V99): no longer typed by anyone. What names "
                        + "an emplacement outside the database is emplacement.code, a bounded "
                        + "VARCHAR(64) this scan does not have to classify");
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
    private static final Map<String, String> PORTEURS_ENFOUIS = new LinkedHashMap<>();

    static {
        PORTEURS_ENFOUIS.put(
                "plan_snapshot.contenu",
                "JSONB: standId, animateurId and creneauId of every seat. Left alone, the published "
                        + "plan matches nothing — the stability rule sees the whole grid as changed — and "
                        + "no older snapshot can be restored, since a restore requires every id it names "
                        + "to still exist");
        PORTEURS_ENFOUIS.put(
                "solver_job.perimetre",
                "JSONB: animateurIds and standIds of a scoped solve, replayed at startup — a job that "
                        + "outlives the migration would aim at an empty or wrong perimeter");
        PORTEURS_ENFOUIS.put(
                "notification_planifiee.cle",
                "text: « animateurId|date » and « animateurId|publieLe », the deduplication key. Left "
                        + "alone it matches nothing, and the day-before reminders and the relances go out "
                        + "a second time");
        PORTEURS_ENFOUIS.put(
                "declaration_disponibilite.souhaits",
                "text: one typologie id per line. A declaration still pending when the migration runs "
                        + "would cite typologies nobody knows");
    }

    /**
     * The other payloads, and why nothing has to be rewritten in them. Listed
     * so that a new one cannot join them by default.
     */
    private static final Map<String, String> CHARGES_SANS_ID = new LinkedHashMap<>();

    static {
        // JSONB.
        CHARGES_SANS_ID.put("plan_snapshot.consignes", "dates, hours and a motif — a consigne is not named by an id");
        CHARGES_SANS_ID.put("plan_snapshot.kpi", "aggregates, plus constraint names as keys");
        CHARGES_SANS_ID.put("kpi_historique.kpi", "same");
        CHARGES_SANS_ID.put("publication_destinataire.changements", "sentences written for the reader");
        CHARGES_SANS_ID.put("publication_destinataire.demandes", "same");

        // Lists stored as text: the shape a buried carrier takes, so each one
        // says what it holds.
        CHARGES_SANS_ID.put(
                "declaration_disponibilite.jours_indisponibles", "one ISO date per line — dates, never ids");
        CHARGES_SANS_ID.put(
                "demande_echange.contraintes_violees", "one constraint name per line, as ConstraintCatalog names them");
        CHARGES_SANS_ID.put("journal_action.champs", "the names of the fields an edit changed, never their values");

        // Codes and enum names: a fixed vocabulary, not a row of a referential.
        CHARGES_SANS_ID.put("journal_action.action", "the code of the action, as CatalogueActions names it");
        CHARGES_SANS_ID.put("journal_action.acteur", "the name of the Acteur enum constant");
        CHARGES_SANS_ID.put("journal_action.entite", "the kind of entity, which is what makes entite_id readable");
        CHARGES_SANS_ID.put("journal_action.resultat", "the outcome of the action: a fixed vocabulary");
        CHARGES_SANS_ID.put("confirmation_planning.statut", "the name of the status enum constant");
        CHARGES_SANS_ID.put("notification_planifiee.type", "same");
        CHARGES_SANS_ID.put("notification_planifiee.severite", "same");

        // Sentences: written for a human, read by no code.
        CHARGES_SANS_ID.put(
                "notification_planifiee.libelle", "a sentence written for an organiser, and never nominative");
        CHARGES_SANS_ID.put("solver_job.erreur", "the failure of a solve, as it is shown");
        CHARGES_SANS_ID.put("backup_settings.dernier_message", "the failure of the last dump, as it is shown");
        CHARGES_SANS_ID.put("backup_settings.dernier_fichier", "the name of the last dump written: a file, not a row");

        // Free text typed by a human: an id that lands in one is a word, not a
        // reference — nothing resolves it, so nothing has to rewrite it.
        CHARGES_SANS_ID.put("contrainte_ad_hoc.raison", "free text: why the exception was written");
        CHARGES_SANS_ID.put("verrouillage_planning.raison", "free text: why the lock was laid down");
        CHARGES_SANS_ID.put("consigne_edition.motif", "free text: the order the consigne relays");
        CHARGES_SANS_ID.put("consigne_edition.repas_justification", "free text: why that compensation was chosen");
        CHARGES_SANS_ID.put("prereglage_consigne.motif", "free text, same, on the preset");
        CHARGES_SANS_ID.put("prereglage_consigne.repas_justification", "same");
        CHARGES_SANS_ID.put("demande_echange.motif", "free text: why the swap is asked for");
        CHARGES_SANS_ID.put("demande_echange.commentaire_admin", "free text: the answer given to it");
        CHARGES_SANS_ID.put("declaration_disponibilite.commentaire", "free text written by the animateur");
        CHARGES_SANS_ID.put("declaration_disponibilite.commentaire_admin", "free text written when reading it");
        CHARGES_SANS_ID.put("validation_journee.commentaire", "free text written when a day is read and accepted");
        CHARGES_SANS_ID.put("typologie.description", "free text: what the game category covers");
    }

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
     * The same for what a scan cannot read: a payload column says whether it
     * carries ids, rather than being assumed not to. A column the id scan
     * already covers — held by a foreign key, or classified above — has been
     * examined and is not asked twice.
     */
    @Test
    void everyPayloadSaysWhetherItCarriesIds() throws SQLException {
        List<String> nonClassees = new ArrayList<>();
        for (Colonne colonne : colonnes()) {
            if (colonne.porteeParUneCleEtrangere() || SANS_CLE_ETRANGERE.containsKey(colonne.cle())) {
                continue;
            }
            if (colonne.isPayload()
                    && !PORTEURS_ENFOUIS.containsKey(colonne.cle())
                    && !CHARGES_SANS_ID.containsKey(colonne.cle())) {
                nonClassees.add(colonne.cle());
            }
        }

        assertThat(nonClassees).as("""
                        charges utiles non classées — JSONB, TEXT ou VARCHAR sans borne : une \
                        migration ne peut pas réécrire ce qu'elle ne sait pas lire. Ajoutez \
                        chacune à PORTEURS_ENFOUIS avec les ids qu'elle contient, ou à \
                        CHARGES_SANS_ID avec la raison qu'elle n'en contient pas.""").isEmpty();
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
    private record Colonne(String table, String nom, String type, Long longueurMax, boolean porteeParUneCleEtrangere) {

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

        /**
         * A column that can hold a value of any length, which is what a
         * payload needs: {@code JSON}, {@code JSONB}, {@code TEXT} and the
         * unbounded {@code VARCHAR}. A {@code VARCHAR(n)} is a named field,
         * and the scan stops there on purpose — see the class javadoc for the
         * two bounded columns that hold a list all the same.
         */
        boolean isPayload() {
            return "jsonb".equals(type)
                    || "json".equals(type)
                    || "text".equals(type)
                    || ("character varying".equals(type) && longueurMax == null);
        }
    }

    private List<Colonne> colonnes() throws SQLException {
        List<Colonne> colonnes = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("""
                        -- The join on table_constraints carries table_name as well as
                        -- constraint_name: PostgreSQL makes a constraint name unique per table,
                        -- not per schema, so a primary key sharing its name with another
                        -- table's foreign key would otherwise report its columns as held.
                        SELECT c.table_name, c.column_name, c.data_type,
                               c.character_maximum_length,
                               EXISTS (SELECT 1
                                       FROM information_schema.key_column_usage k
                                       JOIN information_schema.table_constraints tc
                                         ON tc.constraint_name = k.constraint_name
                                        AND tc.constraint_schema = k.constraint_schema
                                        AND tc.table_schema = k.table_schema
                                        AND tc.table_name = k.table_name
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
                    long lue = rs.getLong("character_maximum_length");
                    Long longueurMax = rs.wasNull() ? null : lue;
                    colonnes.add(new Colonne(
                            table,
                            rs.getString("column_name"),
                            rs.getString("data_type"),
                            longueurMax,
                            rs.getBoolean("portee")));
                }
            }
        }
        assertThat(colonnes)
                .as("aucune colonne lue : le scan ne reconnaît plus rien, et tous les tests ci-dessus passeraient")
                .isNotEmpty();
        return colonnes;
    }
}
