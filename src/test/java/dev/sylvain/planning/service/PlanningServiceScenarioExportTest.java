package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.ParametresDecoupage;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.NiveauEffort;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeJoursHoraire;

/**
 * Exercises {@link PlanningService#buildScenarioYaml} directly
 * (package-private, no database needed), the reverse of what
 * {@code chargerScenarioYaml} parses. Checks the produced text is valid YAML
 * carrying the same shape as the hand-authored scenario files, in particular
 * that times like "09:00" stay strings instead of being reinterpreted as
 * YAML 1.1 sexagesimal numbers.
 */
class PlanningServiceScenarioExportTest {

    private final Stand stand = new Stand("STAND-A", "Stand A", Set.of("STRATEGIE"), 1, 2, false);
    private final Creneau creneau = new Creneau(1L, 1, LocalDate.of(2026, 8, 14), LocalTime.of(9, 0), LocalTime.of(13, 0));
    private final Animateur animateur = new Animateur("A1", "Alice", "Referente", LocalDate.of(2000, 1, 1), false);

    @Test
    @SuppressWarnings("unchecked")
    void exportedYamlRoundTripsThroughAParser() {
        animateur.setCompetences(Map.of("STRATEGIE", NiveauCompetence.REFERENT));
        animateur.setJoursIndisponibles(Set.of(LocalDate.of(2026, 8, 15)));
        stand.setNiveauEffort(NiveauEffort.EPUISANT);
        stand.setIndisponibilites(List.of(
                new IndisponibiliteStand(1L, LocalDate.of(2026, 8, 14), LocalTime.of(14, 0), LocalTime.of(16, 0), "Pause")));
        // A date clearly unrelated to the créneau's day (2026-08-14) and the day
        // after (2026-08-15, which Creneau#segmentsOuvertsMinutes also treats as
        // relevant for a créneau crossing into it) — an ouverture on either would
        // switch the stand to closed-by-default for this créneau's day and starve
        // buildPostes of a poste to build below.
        stand.setOuvertures(List.of(
                new OuvertureStand(2L, LocalDate.of(2026, 8, 20), LocalTime.of(20, 0), LocalTime.of(23, 0), null)));
        List<PosteAffectation> postes = PlanningService.buildPostes(List.of(stand), List.of(creneau));

        String yaml = PlanningService.buildScenarioYaml(List.of(animateur), List.of(stand), List.of(creneau), postes);
        Map<String, Object> parsed = new Yaml().load(yaml);

        assertThat(parsed.get("festival")).isInstanceOfSatisfying(Map.class,
                festival -> assertThat(festival.get("dateDebut")).isEqualTo("2026-08-14"));

        List<Map<String, Object>> creneaux = (List<Map<String, Object>>) parsed.get("creneaux");
        assertThat(creneaux).hasSize(1);
        assertThat(creneaux.get(0)).containsEntry("id", 1)
                .containsEntry("heureDebut", "09:00")
                .containsEntry("heureFin", "13:00");

        List<Map<String, Object>> stands = (List<Map<String, Object>>) parsed.get("stands");
        assertThat(stands).hasSize(1);
        assertThat(stands.get(0)).containsEntry("id", "STAND-A")
                .containsEntry("effectifMin", 1)
                .containsEntry("effectifMax", 2)
                .containsEntry("niveauEffort", "EPUISANT");
        assertThat((List<String>) stands.get(0).get("typologiesProposees")).containsExactly("STRATEGIE");

        List<Map<String, Object>> indisponibilites = (List<Map<String, Object>>) stands.get(0).get("indisponibilites");
        assertThat(indisponibilites).hasSize(1);
        assertThat(indisponibilites.get(0)).containsEntry("date", "2026-08-14")
                .containsEntry("heureDebut", "14:00")
                .containsEntry("heureFin", "16:00")
                .containsEntry("motif", "Pause");

        List<Map<String, Object>> ouvertures = (List<Map<String, Object>>) stands.get(0).get("ouvertures");
        assertThat(ouvertures).hasSize(1);
        assertThat(ouvertures.get(0)).containsEntry("date", "2026-08-20")
                .containsEntry("heureDebut", "20:00")
                .containsEntry("heureFin", "23:00")
                .containsEntry("motif", null);

        List<Map<String, Object>> animateurs = (List<Map<String, Object>>) parsed.get("animateurs");
        assertThat(animateurs).hasSize(1);
        assertThat(animateurs.get(0)).containsEntry("id", "A1")
                .containsEntry("dateNaissance", "2000-01-01");
        assertThat((Map<String, String>) animateurs.get(0).get("competences")).containsEntry("STRATEGIE", "REFERENT");
        assertThat((List<String>) animateurs.get(0).get("joursIndisponibles")).containsExactly("2026-08-15");

        List<Map<String, Object>> postesYaml = (List<Map<String, Object>>) parsed.get("postes");
        assertThat(postesYaml).hasSize(1);
        assertThat(postesYaml.get(0)).containsEntry("standId", "STAND-A")
                .containsEntry("creneauId", 1)
                .containsEntry("animateurId", null);
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
    void horairesRecurrentsSontExportesCommeRegles() {
        HoraireStand quotidien = HoraireStand.everyDay(ModeHoraire.OUVERTURE,
                new FenetreHoraire(LocalTime.of(10, 0), LocalTime.of(12, 0)),
                new FenetreHoraire(LocalTime.of(14, 0), null));
        HoraireStand weekend = new HoraireStand(null, ModeHoraire.FERMETURE, TypeJoursHoraire.JOURS_SEMAINE,
                List.of(new FenetreHoraire(LocalTime.of(0, 0), null)));
        weekend.setJoursSemaine(Set.of(DayOfWeek.SUNDAY));
        stand.setHoraires(List.of(quotidien, weekend));

        String yaml = PlanningService.buildScenarioYaml(List.of(animateur), List.of(stand), List.of(creneau),
                List.of());
        Map<String, Object> parsed = new Yaml().load(yaml);

        List<Map<String, Object>> stands = (List<Map<String, Object>>) parsed.get("stands");
        List<Map<String, Object>> horaires = (List<Map<String, Object>>) stands.get(0).get("horaires");
        assertThat(horaires).hasSize(2);

        assertThat(horaires.get(0)).containsEntry("mode", "OUVERTURE").containsEntry("jours", "TOUS");
        // A TOUS rule carries no selector data: emitting empty date keys would be
        // noise in a file meant to be read and diffed by hand.
        assertThat(horaires.get(0)).doesNotContainKeys("joursSemaine", "dateDebut", "dateFin", "dates");
        List<Map<String, Object>> fenetres = (List<Map<String, Object>>) horaires.get(0).get("fenetres");
        assertThat(fenetres).hasSize(2);
        assertThat(fenetres.get(0)).containsEntry("heureDebut", "10:00").containsEntry("heureFin", "12:00");
        assertThat(fenetres.get(1)).containsEntry("heureDebut", "14:00").doesNotContainKey("heureFin");

        assertThat(horaires.get(1)).containsEntry("mode", "FERMETURE").containsEntry("jours", "JOURS_SEMAINE");
        assertThat((List<String>) horaires.get(1).get("joursSemaine")).containsExactly("SUNDAY");
    }

    /** A dated exception with no end hour keeps its explicit null, unlike a rule's window. */
    @Test
    @SuppressWarnings("unchecked")
    void uneOuvertureDateeSansHeureFinExporteUnHeureFinNul() {
        stand.setOuvertures(List.of(
                new OuvertureStand(1L, LocalDate.of(2026, 8, 20), LocalTime.of(20, 0), null, null)));

        String yaml = PlanningService.buildScenarioYaml(List.of(animateur), List.of(stand), List.of(creneau),
                List.of());
        Map<String, Object> parsed = new Yaml().load(yaml);

        List<Map<String, Object>> stands = (List<Map<String, Object>>) parsed.get("stands");
        List<Map<String, Object>> ouvertures = (List<Map<String, Object>>) stands.get(0).get("ouvertures");
        assertThat(ouvertures.get(0)).containsEntry("heureDebut", "20:00").containsEntry("heureFin", null);
    }

    /**
     * Reproducibility: an exported scenario must carry the settings that shape
     * a solve, not only its entities. Without them the file replayed elsewhere
     * silently borrowed that instance's own solve duration, vacation lengths
     * and legal caps — the same "scenario", a different problem.
     */
    @Test
    @SuppressWarnings("unchecked")
    void exportedYamlCarriesTheCurrentSettings() {
        ParametresLegaux legaux = new ParametresLegaux();
        legaux.setDureeHebdomadaireMaxMinutes(40 * 60);
        legaux.setPauseMinimaleEntreVacationsMinutes(45);
        ParametresDecoupage decoupage = new ParametresDecoupage();
        decoupage.setNombreFamillesDecalage(5);
        decoupage.setDureeDecalageMaxMinutes(120);
        decoupage.setStrategieCouverturePendantPause(
                ParametresDecoupage.PauseCoverageStrategy.EFFECTIF_REDUIT);

        String yaml = PlanningService.buildScenarioYaml(new PlanningService.ScenarioExport(
                List.of(animateur), List.of(stand), List.of(creneau), List.of(),
                List.of(new TypologieItem("STRATEGIE", "Stratégie", true)),
                List.of(new Emplacement("PLACE", "Place du Drapeau", 46.6487, 2.2503)),
                legaux, decoupage, new ParametresSolveur(1800)));
        Map<String, Object> parsed = new Yaml().load(yaml);

        assertThat((Map<String, Object>) parsed.get("parametresSolveur"))
                .containsEntry("dureeResolutionSecondes", 1800);
        assertThat((Map<String, Object>) parsed.get("parametresLegaux"))
                .containsEntry("dureeHebdomadaireMaxMinutes", 40 * 60)
                .containsEntry("pauseMinimaleEntreVacationsMinutes", 45);
        assertThat((Map<String, Object>) parsed.get("parametresDecoupage"))
                .containsEntry("nombreFamillesDecalage", 5)
                .containsEntry("dureeDecalageMaxMinutes", 120)
                .containsEntry("strategieCouverturePendantPause", "EFFECTIF_REDUIT")
                // Times stay strings, like everywhere else in the file.
                .containsEntry("fenetreRepasMidiDebut", "12:00");
        assertThat((List<Map<String, Object>>) parsed.get("typologies"))
                .singleElement()
                .satisfies(typologie -> assertThat(typologie).containsEntry("id", "STRATEGIE")
                        .containsEntry("label", "Stratégie")
                        .containsEntry("ninja", true));
        assertThat((List<Map<String, Object>>) parsed.get("emplacements"))
                .singleElement()
                .satisfies(emplacement -> assertThat(emplacement).containsEntry("id", "PLACE")
                        .containsEntry("latitude", 46.6487));
    }

    /** A stand tied to an emplacement exports the link, or the emplacements section is decorative. */
    @Test
    @SuppressWarnings("unchecked")
    void aStandExportsItsEmplacementId() {
        stand.setEmplacement(new Emplacement("PLACE", "Place du Drapeau", 46.6487, 2.2503));

        String yaml = PlanningService.buildScenarioYaml(List.of(animateur), List.of(stand), List.of(creneau),
                List.of());
        Map<String, Object> parsed = new Yaml().load(yaml);

        assertThat(((List<Map<String, Object>>) parsed.get("stands")).get(0)).containsEntry("emplacementId", "PLACE");
    }

    /** A null seat list leaves the {@code postes:} section out entirely. */
    @Test
    void aNullSeatListPinsNoPostesSection() {
        String yaml = PlanningService.buildScenarioYaml(new PlanningService.ScenarioExport(
                List.of(animateur), List.of(stand), List.of(creneau), null, List.of(), List.of(), null, null, null));
        Map<String, Object> parsed = new Yaml().load(yaml);

        assertThat(parsed).doesNotContainKey("postes");
        assertThat(parsed).doesNotContainKey("decoupageAuto");
    }

    @Test
    void exportingWithoutReferenceDataFails() {
        ReferenceData referenceDataService = new EmptyReferenceData();
        PlanningService planningService = new PlanningService(3L, 2L, ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT, referenceDataService, new FeasibilityAnalyzer(),
                ConfigProvider.getConfig());

        assertThatThrownBy(planningService::exportScenarioYaml).isInstanceOf(IllegalStateException.class);
    }
}
