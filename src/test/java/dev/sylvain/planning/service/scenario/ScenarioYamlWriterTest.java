package dev.sylvain.planning.service.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.NiveauEffort;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.domain.TypeJoursHoraire;
import dev.sylvain.planning.service.EmptyReferenceData;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer;
import dev.sylvain.planning.service.referentiel.ReferenceData;
import dev.sylvain.planning.service.referentiel.TypologieItem;
import dev.sylvain.planning.service.solve.PlanningService;
import dev.sylvain.planning.service.solve.ProblemBuilder;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Exercises {@link ScenarioYamlWriter#buildScenarioYaml} directly
 * (package-private, no database needed), the reverse of what
 * {@code chargerScenarioYaml} parses. Checks the produced text is valid YAML
 * carrying the same shape as the hand-authored scenario files, in particular
 * that times like "09:00" stay strings instead of being reinterpreted as
 * YAML 1.1 sexagesimal numbers.
 */
class ScenarioYamlWriterTest {

    private final Stand stand = new Stand("STAND-A", "Stand A", Set.of("STRATEGIE"), 1, 2, false);
    private final Creneau creneau =
            new Creneau(1L, 1, LocalDate.of(2026, 8, 14), LocalTime.of(9, 0), LocalTime.of(13, 0));
    private final Animateur animateur = new Animateur("A1", "Alice", "Referente", LocalDate.of(2000, 1, 1), false);

    @Test
    @SuppressWarnings("unchecked")
    void exportedYamlRoundTripsThroughAParser() {
        animateur.setCompetences(Map.of("STRATEGIE", NiveauCompetence.REFERENT));
        animateur.setJoursIndisponibles(Set.of(LocalDate.of(2026, 8, 15)));
        stand.setNiveauEffort(NiveauEffort.EPUISANT);
        stand.setIndisponibilites(List.of(new IndisponibiliteStand(
                1L, LocalDate.of(2026, 8, 14), LocalTime.of(14, 0), LocalTime.of(16, 0), "Pause")));
        // A date clearly unrelated to the créneau's day (2026-08-14) and the day
        // after (2026-08-15, which Creneau#segmentsOuvertsMinutes also treats as
        // relevant for a créneau crossing into it) — an ouverture on either would
        // switch the stand to closed-by-default for this créneau's day and starve
        // buildPostes of a poste to build below.
        stand.setOuvertures(List.of(
                new OuvertureStand(2L, LocalDate.of(2026, 8, 20), LocalTime.of(20, 0), LocalTime.of(23, 0), null, 3)));
        List<PosteAffectation> postes = ProblemBuilder.buildPostes(List.of(stand), List.of(creneau));

        String yaml =
                ScenarioYamlWriter.buildScenarioYaml(List.of(animateur), List.of(stand), List.of(creneau), postes);
        Map<String, Object> parsed = new Yaml().load(yaml);

        assertThat(parsed.get("festival"))
                .isInstanceOfSatisfying(
                        Map.class, evenement -> assertThat(evenement).containsEntry("dateDebut", "2026-08-14"));

        List<Map<String, Object>> creneaux = (List<Map<String, Object>>) parsed.get("creneaux");
        assertThat(creneaux).hasSize(1);
        assertThat(creneaux.get(0))
                .containsEntry("id", "1")
                .containsEntry("heureDebut", "09:00")
                .containsEntry("heureFin", "13:00");

        List<Map<String, Object>> stands = (List<Map<String, Object>>) parsed.get("stands");
        assertThat(stands).hasSize(1);
        assertThat(stands.get(0))
                .containsEntry("id", "STAND-A")
                .containsEntry("effectifMin", 1)
                .containsEntry("effectifMax", 2)
                .containsEntry("niveauEffort", "EPUISANT");
        assertThat((List<String>) stands.get(0).get("typologiesProposees")).containsExactly("STRATEGIE");

        List<Map<String, Object>> indisponibilites =
                (List<Map<String, Object>>) stands.get(0).get("indisponibilites");
        assertThat(indisponibilites).hasSize(1);
        assertThat(indisponibilites.get(0))
                .containsEntry("date", "2026-08-14")
                .containsEntry("heureDebut", "14:00")
                .containsEntry("heureFin", "16:00")
                .containsEntry("motif", "Pause");

        List<Map<String, Object>> ouvertures =
                (List<Map<String, Object>>) stands.get(0).get("ouvertures");
        assertThat(ouvertures).hasSize(1);
        // The headcount an opening names travels too, else re-importing the file
        // silently falls back on the stand's minimum and loses the volume.
        assertThat(ouvertures.get(0)).containsEntry("effectif", 3);
        assertThat(ouvertures.get(0))
                .containsEntry("date", "2026-08-20")
                .containsEntry("heureDebut", "20:00")
                .containsEntry("heureFin", "23:00")
                // Absent, not null. The reader does map.get(), so it cannot tell
                // the two apart; a key that says nothing is noise in a file
                // meant to be read and diffed by hand.
                .doesNotContainKey("motif");

        List<Map<String, Object>> animateurs = (List<Map<String, Object>>) parsed.get("animateurs");
        assertThat(animateurs).hasSize(1);
        assertThat(animateurs.get(0)).containsEntry("id", "A1").containsEntry("dateNaissance", "2000-01-01");
        assertThat((Map<String, String>) animateurs.get(0).get("competences")).containsEntry("STRATEGIE", "REFERENT");
        assertThat((List<String>) animateurs.get(0).get("joursIndisponibles")).containsExactly("2026-08-15");

        List<Map<String, Object>> postesYaml = (List<Map<String, Object>>) parsed.get("postes");
        assertThat(postesYaml).hasSize(1);
        // A string, not a number: the published schema declares the id
        // `string`, and this assertion used to pin down the very gap that made
        // every exported file impossible to import back.
        assertThat(postesYaml.get(0))
                .containsEntry("standId", "STAND-A")
                .containsEntry("creneauId", "1")
                // Absent, not null. An export never pins who sits where — the
                // seat list says which seats exist, the solve says who fills
                // them — and writing the key out only made that look like a
                // decision somebody had taken.
                .doesNotContainKey("animateurId");
    }

    /**
     * The recurring horaires are exported as <b>rules</b>, with the day selector
     * flattened onto the rule and only the fields that selector uses — which is
     * the whole point: a stand open "10:00-12:00 then 14:00 to closing, every
     * day" takes four lines here instead of twenty-four dated entries. An
     * open-ended window drops its {@code heureFin} key entirely rather than
     * writing an explicit null.
     */
    @Test
    @SuppressWarnings("unchecked")
    void recurringHorairesAreWrittenAsRules() {
        HoraireStand quotidien = HoraireStand.everyDay(
                ModeHoraire.OUVERTURE,
                new FenetreHoraire(LocalTime.of(10, 0), LocalTime.of(12, 0)),
                new FenetreHoraire(LocalTime.of(14, 0), null));
        HoraireStand weekend = new HoraireStand(
                null,
                ModeHoraire.FERMETURE,
                TypeJoursHoraire.JOURS_SEMAINE,
                List.of(new FenetreHoraire(LocalTime.of(0, 0), null)));
        weekend.setJoursSemaine(Set.of(DayOfWeek.SUNDAY));
        stand.setHoraires(List.of(quotidien, weekend));

        String yaml =
                ScenarioYamlWriter.buildScenarioYaml(List.of(animateur), List.of(stand), List.of(creneau), List.of());
        Map<String, Object> parsed = new Yaml().load(yaml);

        List<Map<String, Object>> stands = (List<Map<String, Object>>) parsed.get("stands");
        List<Map<String, Object>> horaires =
                (List<Map<String, Object>>) stands.get(0).get("horaires");
        assertThat(horaires).hasSize(2);

        assertThat(horaires.get(0)).containsEntry("mode", "OUVERTURE").containsEntry("jours", "TOUS");
        // A TOUS rule carries no selector data: emitting empty date keys would be
        // noise in a file meant to be read and diffed by hand.
        assertThat(horaires.get(0)).doesNotContainKeys("joursSemaine", "dateDebut", "dateFin", "dates");
        List<Map<String, Object>> fenetres =
                (List<Map<String, Object>>) horaires.get(0).get("fenetres");
        assertThat(fenetres).hasSize(2);
        assertThat(fenetres.get(0)).containsEntry("heureDebut", "10:00").containsEntry("heureFin", "12:00");
        assertThat(fenetres.get(1)).containsEntry("heureDebut", "14:00").doesNotContainKey("heureFin");

        assertThat(horaires.get(1)).containsEntry("mode", "FERMETURE").containsEntry("jours", "JOURS_SEMAINE");
        assertThat((List<String>) horaires.get(1).get("joursSemaine")).containsExactly("SUNDAY");
    }

    /**
     * A dated exception with no end hour leaves the key out, exactly like a
     * rule's window does.
     *
     * <p>It used to write {@code heureFin: null} there and omit it in a rule's
     * window — two helper methods written at different times, not a
     * distinction anybody meant: the reader does {@code map.get()} either way.
     * Since the export serialises a {@code ScenarioDto}, one rule decides for
     * the whole file.</p>
     */
    @Test
    @SuppressWarnings("unchecked")
    void aDatedOpeningWithoutAnEndHourLeavesTheKeyOut() {
        stand.setOuvertures(
                List.of(new OuvertureStand(1L, LocalDate.of(2026, 8, 20), LocalTime.of(20, 0), null, null)));

        String yaml =
                ScenarioYamlWriter.buildScenarioYaml(List.of(animateur), List.of(stand), List.of(creneau), List.of());
        Map<String, Object> parsed = new Yaml().load(yaml);

        List<Map<String, Object>> stands = (List<Map<String, Object>>) parsed.get("stands");
        List<Map<String, Object>> ouvertures =
                (List<Map<String, Object>>) stands.get(0).get("ouvertures");
        assertThat(ouvertures.get(0)).containsEntry("heureDebut", "20:00").doesNotContainKey("heureFin");
    }

    /**
     * Reproducibility: an exported scenario must carry the settings that shape
     * a solve, not only its entities. Without them the file replayed elsewhere
     * silently borrowed that instance's own solve duration and legal caps —
     * the same "scenario", a different problem.
     */
    @Test
    @SuppressWarnings("unchecked")
    void exportedYamlCarriesTheCurrentSettings() {
        ParametresLegaux legaux = new ParametresLegaux();
        legaux.setDureeHebdomadaireMaxMinutes(40 * 60);
        legaux.setDureePauseMinutes(45);
        legaux.setDureeVacationMaxMinutes(5 * 60);

        String yaml = ScenarioYamlWriter.buildScenarioYaml(new ScenarioYamlWriter.ScenarioExport(
                List.of(animateur),
                List.of(stand),
                List.of(creneau),
                List.of(),
                List.of(new TypologieItem("STRATEGIE", "Stratégie", true)),
                List.of(new Emplacement("PLACE", "Place du Drapeau", 46.6487, 2.2503)),
                legaux,
                null,
                new ParametresSolveur(1800),
                Map.of("equilibrerCharge", false),
                Map.of("maxJoursConsecutifsTravailles", 5),
                List.of()));
        Map<String, Object> parsed = new Yaml().load(yaml);

        assertThat((Map<String, Object>) parsed.get("parametresSolveur"))
                .containsEntry("dureeResolutionSecondes", 1800);
        assertThat((Map<String, Object>) parsed.get("parametresLegaux"))
                .containsEntry("dureeHebdomadaireMaxMinutes", 40 * 60)
                .containsEntry("dureePauseMinutes", 45)
                // The meal break travels with the legal parameters; times stay
                // strings, like everywhere else in the file.
                .containsEntry("coupureRepasMinutes", 60)
                .containsEntry("coupureRepasMidiDebut", "12:00")
                // The vacation ceiling made the same move as the meal break.
                .containsEntry("dureeVacationMaxMinutes", 5 * 60);
        assertThat(parsed).doesNotContainKey("parametresDecoupage");
        assertThat((List<Map<String, Object>>) parsed.get("typologies"))
                .singleElement()
                .satisfies(typologie -> assertThat(typologie)
                        .containsEntry("id", "STRATEGIE")
                        .containsEntry("label", "Stratégie")
                        .containsEntry("ninja", true));
        assertThat((List<Map<String, Object>>) parsed.get("emplacements"))
                .singleElement()
                .satisfies(emplacement ->
                        assertThat(emplacement).containsEntry("id", "PLACE").containsEntry("latitude", 46.6487));
        Map<String, Object> contraintes = (Map<String, Object>) parsed.get("contraintes");
        assertThat((List<String>) contraintes.get("desactivees")).containsExactly("equilibrerCharge");
        assertThat((Map<String, Object>) contraintes.get("poids")).containsEntry("maxJoursConsecutifsTravailles", 5);
    }

    /**
     * Nothing switched off and nothing reweighted is what a file without the
     * section already means: writing an empty {@code contraintes:} block would
     * be noise in a file meant to be read and diffed by hand.
     */
    @Test
    void anUntunedEditionWritesNoContraintesSection() {
        String yaml =
                ScenarioYamlWriter.buildScenarioYaml(List.of(animateur), List.of(stand), List.of(creneau), List.of());

        assertThat(new Yaml().<Map<String, Object>>load(yaml)).doesNotContainKey("contraintes");
    }

    /**
     * The order of the keys is the order of the file, and the file is diffed
     * by people: since #445 it is the declaration order of the DTO
     * components, and nothing else pinned it — the differential tests
     * compare meaning after a re-read, the schema is alphabetical. Moving
     * one component would move one line per stand in every export.
     */
    @Test
    @SuppressWarnings("unchecked")
    void keysComeOutInTheOrderTheFileHasAlwaysHad() {
        animateur.setCompetences(Map.of("STRATEGIE", NiveauCompetence.REFERENT));
        stand.setIndisponibilites(List.of(new IndisponibiliteStand(
                1L, LocalDate.of(2026, 8, 14), LocalTime.of(14, 0), LocalTime.of(16, 0), "Pause")));
        List<PosteAffectation> postes = ProblemBuilder.buildPostes(List.of(stand), List.of(creneau));

        String yaml =
                ScenarioYamlWriter.buildScenarioYaml(List.of(animateur), List.of(stand), List.of(creneau), postes);
        Map<String, Object> parsed = new Yaml().load(yaml);

        assertThat(new ArrayList<>(parsed.keySet()))
                .containsExactly("festival", "creneaux", "stands", "animateurs", "postes");
        Map<String, Object> standYaml = ((List<Map<String, Object>>) parsed.get("stands")).get(0);
        assertThat(new ArrayList<>(standYaml.keySet()))
                .containsExactly(
                        "id",
                        "nom",
                        "typologiesProposees",
                        "effectifMin",
                        "effectifMax",
                        "reserveMajeurs",
                        "premium",
                        "niveauEffort",
                        "indisponibilites",
                        "ouvertures",
                        "horaires");
    }

    /** The hand-entered constraints travel with the scenario, animateurs and scope included. */
    @Test
    @SuppressWarnings("unchecked")
    void exportedYamlCarriesTheAdHocConstraints() {
        ContrainteAdHoc incompatibilite = new ContrainteAdHoc("INCOMPAT-1", TypeContrainteAdHoc.INCOMPATIBILITE);
        incompatibilite.getAnimateursConcernes().add(animateur);
        incompatibilite.setCreneau(creneau);
        incompatibilite.setStand(stand);
        incompatibilite.setRaison("Ne travaillent pas ensemble");

        String yaml = ScenarioYamlWriter.buildScenarioYaml(new ScenarioYamlWriter.ScenarioExport(
                List.of(animateur),
                List.of(stand),
                List.of(creneau),
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null,
                Map.of(),
                Map.of(),
                List.of(incompatibilite)));
        Map<String, Object> parsed = new Yaml().load(yaml);

        assertThat((List<Map<String, Object>>) parsed.get("contraintesAdHoc"))
                .singleElement()
                .satisfies(contrainte -> {
                    assertThat(contrainte)
                            .containsEntry("id", "INCOMPAT-1")
                            .containsEntry("type", "INCOMPATIBILITE")
                            .containsEntry("creneauId", "1")
                            .containsEntry("standId", "STAND-A")
                            .containsEntry("raison", "Ne travaillent pas ensemble");
                    assertThat((List<String>) contrainte.get("animateurs")).containsExactly("A1");
                });
    }

    /**
     * A constraint scoped to a créneau the export does not carry loses the
     * scope, and keeps everything else. The guard's inverse would re-export a
     * one-créneau INDISPONIBILITE_FORCEE as covering the whole event: a hard
     * constraint silently widened, which no shipped scenario could reveal —
     * none declares an ad hoc constraint.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aConstraintScopedToAnUnknownCreneauKeepsEverythingButTheScope() {
        ContrainteAdHoc indisponibilite = new ContrainteAdHoc("INDISPO-1", TypeContrainteAdHoc.INDISPONIBILITE_FORCEE);
        indisponibilite.getAnimateursConcernes().add(animateur);
        indisponibilite.setCreneau(creneau);
        indisponibilite.setStand(stand);

        String yaml = ScenarioYamlWriter.buildScenarioYaml(new ScenarioYamlWriter.ScenarioExport(
                List.of(animateur),
                List.of(stand),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null,
                Map.of(),
                Map.of(),
                List.of(indisponibilite)));
        Map<String, Object> parsed = new Yaml().load(yaml);

        assertThat((List<Map<String, Object>>) parsed.get("contraintesAdHoc"))
                .singleElement()
                .satisfies(contrainte -> {
                    assertThat(contrainte)
                            .containsEntry("id", "INDISPO-1")
                            .containsEntry("type", "INDISPONIBILITE_FORCEE")
                            .containsEntry("standId", "STAND-A")
                            .doesNotContainKey("creneauId");
                    assertThat((List<String>) contrainte.get("animateurs")).containsExactly("A1");
                });
    }

    /** A stand tied to an emplacement exports the link, or the emplacements section is decorative. */
    @Test
    @SuppressWarnings("unchecked")
    void aStandExportsItsEmplacementId() {
        stand.setEmplacement(new Emplacement("PLACE", "Place du Drapeau", 46.6487, 2.2503));

        String yaml =
                ScenarioYamlWriter.buildScenarioYaml(List.of(animateur), List.of(stand), List.of(creneau), List.of());
        Map<String, Object> parsed = new Yaml().load(yaml);

        assertThat(((List<Map<String, Object>>) parsed.get("stands")).get(0)).containsEntry("emplacementId", "PLACE");
    }

    /** A null seat list leaves the {@code postes:} section out entirely. */
    @Test
    void aNullSeatListPinsNoPostesSection() {
        String yaml = ScenarioYamlWriter.buildScenarioYaml(new ScenarioYamlWriter.ScenarioExport(
                List.of(animateur),
                List.of(stand),
                List.of(creneau),
                null,
                List.of(),
                List.of(),
                null,
                null,
                null,
                Map.of(),
                Map.of(),
                List.of()));
        Map<String, Object> parsed = new Yaml().load(yaml);

        assertThat(parsed).doesNotContainKey("postes");
    }

    @Test
    void exportingWithoutReferenceDataFails() {
        ReferenceData referenceDataService = new EmptyReferenceData();
        PlanningService planningService = new PlanningService(
                3L,
                2L,
                ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT,
                referenceDataService,
                new FeasibilityAnalyzer(),
                null,
                null,
                ConfigProvider.getConfig());

        assertThatThrownBy(planningService::exportScenarioYaml).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theWalkingSettingsTravelInTheScenarioFileAndComeBackIdentical() {
        ParametresQualite reglee = new ParametresQualite().withTrajet(4.5, 1.6, 8);
        String yaml = ScenarioYamlWriter.buildScenarioYaml(new ScenarioYamlWriter.ScenarioExport(
                List.of(animateur),
                List.of(stand),
                List.of(creneau),
                null,
                List.of(),
                List.of(),
                null,
                reglee,
                null,
                Map.of(),
                Map.of(),
                List.of()));

        ScenarioYamlReader.ScenarioImporte relu = ScenarioYamlReader.buildFromScenarioText(yaml, ParametresLegaux::new);

        assertThat(relu.sections().parametresQualite()).contains(reglee);
    }
}
