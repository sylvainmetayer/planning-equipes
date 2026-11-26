package dev.sylvain.planning.service.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.HorairesRecurrents;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.NiveauEffort;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlageStand;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.scenario.ScenarioValidator;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.EmptyReferenceData;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer;
import dev.sylvain.planning.service.referentiel.ReferenceData;
import dev.sylvain.planning.service.referentiel.TypologieItem;
import dev.sylvain.planning.service.solve.PlanningService;
import dev.sylvain.planning.service.solve.ProblemBuilder;
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
        stand.setIndisponibilites(List.of(PlageStand.fermeture(
                1L, LocalDate.of(2026, 8, 14), LocalTime.of(14, 0), LocalTime.of(16, 0), "Pause")));
        // A date clearly unrelated to the créneau's day (2026-08-14) and the day
        // after (2026-08-15, which Creneau#segmentsOuvertsMinutes also treats as
        // relevant for a créneau crossing into it) — an ouverture on either would
        // switch the stand to closed-by-default for this créneau's day and starve
        // buildPostes of a poste to build below.
        stand.setOuvertures(List.of(PlageStand.ouverture(
                2L, LocalDate.of(2026, 8, 20), LocalTime.of(20, 0), LocalTime.of(23, 0), null, 3)));
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

        List<Map<String, Object>> plages =
                (List<Map<String, Object>>) stands.get(0).get("plages");
        assertThat(plages).hasSize(2);
        assertThat(plages.get(0))
                .containsEntry("mode", "FERMETURE")
                .containsEntry("date", "2026-08-14")
                .containsEntry("heureDebut", "14:00")
                .containsEntry("heureFin", "16:00")
                .containsEntry("motif", "Pause");
        // The headcount an opening names travels too, else re-importing the file
        // silently falls back on the stand's minimum and loses the volume.
        assertThat(plages.get(1))
                .containsEntry("mode", "OUVERTURE")
                .containsEntry("effectif", 3)
                .containsEntry("date", "2026-08-20")
                .containsEntry("heureDebut", "20:00")
                .containsEntry("heureFin", "23:00")
                // Absent, not null. The reader does map.get(), so it cannot tell
                // the two apart; a key that says nothing is noise in a file
                // meant to be read and diffed by hand.
                .doesNotContainKey("motif");
        assertThat(stands.get(0)).doesNotContainKeys("indisponibilites", "ouvertures", "horaires");

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
     * A plage with no end hour leaves the key out: the reader does
     * {@code map.get()} either way, and the export serialises a
     * {@code ScenarioDto}, where one rule decides for the whole file.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aDatedOpeningWithoutAnEndHourLeavesTheKeyOut() {
        stand.setOuvertures(
                List.of(PlageStand.ouverture(1L, LocalDate.of(2026, 8, 20), LocalTime.of(20, 0), null, null)));

        String yaml =
                ScenarioYamlWriter.buildScenarioYaml(List.of(animateur), List.of(stand), List.of(creneau), List.of());
        Map<String, Object> parsed = new Yaml().load(yaml);

        List<Map<String, Object>> stands = (List<Map<String, Object>>) parsed.get("stands");
        List<Map<String, Object>> plages =
                (List<Map<String, Object>>) stands.get(0).get("plages");
        assertThat(plages.get(0)).containsEntry("heureDebut", "20:00").doesNotContainKey("heureFin");
    }

    /** A stand's model (ADR 0104) travels under {@code horaires} and comes back as the same rules. */
    @Test
    void aStandsModelTravelsUnderHorairesAndComesBackAsTheSameRules() {
        stand.setHoraires(List.of(new HorairesRecurrents.Regle(
                dev.sylvain.planning.domain.ModeHoraire.OUVERTURE,
                dev.sylvain.planning.domain.TypeJoursHoraire.JOURS_SEMAINE,
                java.util.Set.of(java.time.DayOfWeek.SATURDAY, java.time.DayOfWeek.SUNDAY),
                null,
                null,
                null,
                List.of(new dev.sylvain.planning.domain.FenetreHoraire(LocalTime.of(10, 0), LocalTime.of(18, 0), 2)),
                "Week-end")));

        String yaml =
                ScenarioYamlWriter.buildScenarioYaml(List.of(animateur), List.of(stand), List.of(creneau), List.of());
        dev.sylvain.planning.scenario.dto.StandDto read =
                ScenarioYamlReader.parseScenarioText(yaml).stands().get(0);

        assertThat(yaml).contains("horaires:").contains("joursSemaine:");
        assertThat(read.horaires()).singleElement().satisfies(dto -> {
            assertThat(dto.jours()).isEqualTo(dev.sylvain.planning.domain.TypeJoursHoraire.JOURS_SEMAINE);
            assertThat(dto.joursSemaine()).containsExactly(java.time.DayOfWeek.SATURDAY, java.time.DayOfWeek.SUNDAY);
            assertThat(dto.fenetres()).singleElement().satisfies(fenetre -> {
                assertThat(fenetre.heureFin()).isEqualTo(LocalTime.of(18, 0));
                assertThat(fenetre.effectif()).isEqualTo(2);
            });
            assertThat(dto.motif()).isEqualTo("Week-end");
        });
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
                Map.of("equilibrerCharge", false),
                Map.of("maxJoursConsecutifsTravailles", 5),
                List.of()));
        Map<String, Object> parsed = new Yaml().load(yaml);

        Map<String, Object> parametres = (Map<String, Object>) parsed.get("parametres");
        assertThat(parametres).doesNotContainKey("instance");
        assertThat(parsed).doesNotContainKeys("parametresLegaux", "parametresSolveur", "parametresQualite");
        assertThat((Map<String, Object>) parametres.get("legal"))
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
                        .containsEntry("polyvalent", true));
        assertThat((List<Map<String, Object>>) parsed.get("emplacements"))
                .singleElement()
                .satisfies(emplacement ->
                        assertThat(emplacement).containsEntry("id", "PLACE").containsEntry("latitude", 46.6487));
        Map<String, Object> contraintes = (Map<String, Object>) parsed.get("contraintes");
        assertThat((List<String>) contraintes.get("desactivees")).containsExactly("equilibrerCharge");
        assertThat((Map<String, Object>) contraintes.get("poids")).containsEntry("maxJoursConsecutifsTravailles", 5);
    }

    /**
     * A weight above the former ceiling of 100 — the ×5 scale of ADR 0057
     * makes « forte » 25 and lets an edition store up to 500 — goes out and
     * comes back as it was, and the schema's validator accepts it.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aWeightAboveTheFormerCeilingSurvivesTheRoundTrip() {
        String yaml = ScenarioYamlWriter.buildScenarioYaml(new ScenarioYamlWriter.ScenarioExport(
                List.of(animateur),
                List.of(stand),
                List.of(creneau),
                List.of(),
                List.of(new TypologieItem("STRATEGIE", "Stratégie", false)),
                List.of(),
                new ParametresLegaux(),
                null,
                Map.of(),
                Map.of("equilibrerCharge", 125),
                List.of()));

        assertThat((Map<String, Object>) ((Map<String, Object>)
                                new Yaml().<Map<String, Object>>load(yaml).get("contraintes"))
                        .get("poids"))
                .containsEntry("equilibrerCharge", 125);
        assertThat(ScenarioValidator.validate(yaml)).isEmpty();
        ScenarioYamlReader.ScenarioImporte relu = ScenarioYamlReader.buildFromScenarioText(yaml, ParametresLegaux::new);
        assertThat(relu.planning().getPonderationsScenario()).containsEntry("equilibrerCharge", 125);
    }

    /**
     * The level an edition chose for a rule (ADR 0106) goes out under
     * {@code contraintes.niveaux} and comes back as the same choice; a position
     * also gives the weight of that position, and a rule with no level to
     * choose is refused rather than read as the catalogue's.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theLevelsAnEditionChoseSurviveTheRoundTrip() {
        String yaml = ScenarioYamlWriter.buildScenarioYaml(new ScenarioYamlWriter.ScenarioExport(
                List.of(animateur),
                List.of(stand),
                List.of(creneau),
                List.of(),
                List.of(new TypologieItem("STRATEGIE", "Stratégie", false)),
                List.of(),
                new ParametresLegaux(),
                null,
                Map.of(),
                Map.of("equilibrerCharge", 25),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of("stabiliteDuPlanPublie", true, "coupureRepasObligatoire", false)));

        Map<String, Object> contraintes =
                (Map<String, Object>) new Yaml().<Map<String, Object>>load(yaml).get("contraintes");
        assertThat((Map<String, Object>) contraintes.get("niveaux"))
                .containsEntry("stabiliteDuPlanPublie", "OBLIGATOIRE")
                .containsEntry("coupureRepasObligatoire", "PREFERENCE");
        assertThat(ScenarioValidator.validate(yaml)).isEmpty();
        ScenarioYamlReader.ScenarioImporte relu = ScenarioYamlReader.buildFromScenarioText(yaml, ParametresLegaux::new);
        assertThat(relu.planning().getNiveauxScenario())
                .containsEntry("stabiliteDuPlanPublie", true)
                .containsEntry("coupureRepasObligatoire", false);

        String position = yaml.replace("stabiliteDuPlanPublie: OBLIGATOIRE", "stabiliteDuPlanPublie: PREFERENCE_FORTE");
        ScenarioYamlReader.ScenarioImporte parPosition =
                ScenarioYamlReader.buildFromScenarioText(position, ParametresLegaux::new);
        assertThat(parPosition.planning().getNiveauxScenario()).containsEntry("stabiliteDuPlanPublie", false);
        assertThat(parPosition.planning().getPonderationsScenario()).containsEntry("stabiliteDuPlanPublie", 25);

        String sansChoix = yaml.replace("stabiliteDuPlanPublie: OBLIGATOIRE", "dureeQuotidienneMax: PREFERENCE");
        assertThatThrownBy(() -> ScenarioYamlReader.buildFromScenarioText(sansChoix, ParametresLegaux::new))
                .hasMessageContaining("dureeQuotidienneMax")
                .hasMessageContaining("pas de niveau");
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
        stand.setIndisponibilites(List.of(PlageStand.fermeture(
                1L, LocalDate.of(2026, 8, 14), LocalTime.of(14, 0), LocalTime.of(16, 0), "Pause")));
        List<PosteAffectation> postes = ProblemBuilder.buildPostes(List.of(stand), List.of(creneau));

        String yaml =
                ScenarioYamlWriter.buildScenarioYaml(List.of(animateur), List.of(stand), List.of(creneau), postes);
        Map<String, Object> parsed = new Yaml().load(yaml);

        assertThat(new ArrayList<>(parsed.keySet()))
                .containsExactly("schemaVersion", "festival", "creneaux", "stands", "animateurs", "postes");
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
                        "plages");
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
     * one-créneau INCOMPATIBILITE as covering the whole event: a hard
     * constraint silently widened, which no shipped scenario could reveal —
     * none declares an ad hoc constraint.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aConstraintScopedToAnUnknownCreneauKeepsEverythingButTheScope() {
        ContrainteAdHoc indisponibilite = new ContrainteAdHoc("INDISPO-1", TypeContrainteAdHoc.INCOMPATIBILITE);
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
                Map.of(),
                Map.of(),
                List.of(indisponibilite)));
        Map<String, Object> parsed = new Yaml().load(yaml);

        assertThat((List<Map<String, Object>>) parsed.get("contraintesAdHoc"))
                .singleElement()
                .satisfies(contrainte -> {
                    assertThat(contrainte)
                            .containsEntry("id", "INDISPO-1")
                            .containsEntry("type", "INCOMPATIBILITE")
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
                Map.of(),
                Map.of(),
                List.of()));

        ScenarioYamlReader.ScenarioImporte relu = ScenarioYamlReader.buildFromScenarioText(yaml, ParametresLegaux::new);

        assertThat(relu.sections().parametresQualite()).contains(reglee);
    }

    /**
     * The phone number travels in the scenario file like the e-mail address
     * and comes back on the fiche; a file carrying one longer than its column
     * is refused in the reader's words, not by the database.
     */
    @Test
    @SuppressWarnings("unchecked")
    void thePhoneNumberTravelsInTheScenarioFileAndBack() {
        animateur.setTelephone("06 12 34 56 78");
        String yaml =
                ScenarioYamlWriter.buildScenarioYaml(List.of(animateur), List.of(stand), List.of(creneau), List.of());

        Map<String, Object> parsed = new Yaml().load(yaml);
        assertThat(((List<Map<String, Object>>) parsed.get("animateurs")).getFirst())
                .containsEntry("telephone", "06 12 34 56 78");
        ScenarioYamlReader.ScenarioImporte relu = ScenarioYamlReader.buildFromScenarioText(yaml, ParametresLegaux::new);
        assertThat(relu.planning().getAnimateurs())
                .singleElement()
                .satisfies(lu -> assertThat(lu.getTelephone()).isEqualTo("06 12 34 56 78"));

        String tropLong = yaml.replace("06 12 34 56 78", "+33 6 12 34 56 78 poste 1234567890");
        assertThatThrownBy(() -> ScenarioYamlReader.buildFromScenarioText(tropLong, ParametresLegaux::new))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("animateurs.telephone");
    }
}
