package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ConsigneEdition;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.CauseInfaisabilite;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.TypeCauseInfaisabilite;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService.ConstraintDiagnostic;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer;
import dev.sylvain.planning.service.analyse.ViolationFormatter.ViolationReference;
import dev.sylvain.planning.service.consigne.ConsigneResolver;
import dev.sylvain.planning.service.referentiel.CoherenceReferentielService.CoherenceFamily;
import dev.sylvain.planning.service.referentiel.CoherenceReferentielService.CoherenceIssue;
import dev.sylvain.planning.service.referentiel.CoherenceReferentielService.CoherenceReport;
import dev.sylvain.planning.service.referentiel.CoherenceReferentielService.CoherenceSeverity;
import dev.sylvain.planning.service.referentiel.CoherenceReferentielService.CoherenceSubject;
import dev.sylvain.planning.service.referentiel.CoherenceReferentielService.Sources;
import dev.sylvain.planning.service.solve.ProblemBuilder;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link CoherenceReferentielService#build}: the checklist gathers what the
 * analyzers already say, on the whole referential, with their own sentences.
 *
 * <p>2027-09-04 is a Saturday; the grid holds it and the Monday after, so the
 * Sunday between is a day of the event carrying no timeslot.</p>
 */
class CoherenceReferentielServiceTest {

    private static final LocalDate SAMEDI = LocalDate.of(2027, 9, 4);
    private static final LocalDate DIMANCHE = SAMEDI.plusDays(1);
    private static final LocalDate LUNDI = SAMEDI.plusDays(2);

    private static List<Creneau> grille() {
        return new ArrayList<>(List.of(
                new Creneau(1L, 1, SAMEDI, LocalTime.of(10, 0), LocalTime.of(13, 0)),
                new Creneau(2L, 3, LUNDI, LocalTime.of(10, 0), LocalTime.of(13, 0))));
    }

    private static Stand stand(String id, boolean reserveMajeurs) {
        Stand stand = new Stand(id, id, Set.of("JEUX"), 1, 2, reserveMajeurs);
        stand.setIndisponibilites(new ArrayList<>());
        stand.setOuvertures(new ArrayList<>());
        stand.setHoraires(new ArrayList<>());
        return stand;
    }

    private static Animateur adulte(String id, LocalDate... joursOff) {
        Animateur animateur = new Animateur(id, "Prénom", "Nom", LocalDate.of(1990, 1, 1), false);
        animateur.setJoursIndisponibles(Set.of(joursOff));
        return animateur;
    }

    private static Animateur mineur(String id) {
        return new Animateur(id, "Prénom", "Nom", SAMEDI.minusYears(15), false);
    }

    private static ContrainteAdHoc forced(String id, Animateur... animateurs) {
        ContrainteAdHoc contrainte = new ContrainteAdHoc(id, TypeContrainteAdHoc.AFFECTATION_FORCEE);
        contrainte.setAnimateursConcernes(List.of(animateurs));
        return contrainte;
    }

    /** What a line of the checklist reads as in an assertion. */
    private record Line(CoherenceFamily famille, CoherenceSeverity gravite, String code, String objetId) {}

    private static List<Line> lines(CoherenceReport rapport) {
        return rapport.anomalies().stream()
                .map(ligne -> new Line(ligne.famille(), ligne.gravite(), ligne.code(), ligne.objetId()))
                .toList();
    }

    /** The checklist of a referential, every source computed the way the service does. */
    private static CoherenceReport build(
            List<Animateur> animateurs,
            List<Stand> stands,
            List<Creneau> creneaux,
            List<ContrainteAdHoc> contraintes,
            List<VerrouillagePlanning> verrouillages,
            List<ConstraintDiagnostic> diagnostics,
            boolean withStaffing) {
        HoraireStandResolver.apply(stands, creneaux);
        return build(animateurs, stands, stands, creneaux, contraintes, verrouillages, diagnostics, withStaffing);
    }

    /**
     * The checklist with the solver's view of the stands ({@code stands}, the
     * consigne layer included) apart from their own hours ({@code ownHoursStands}),
     * both already resolved.
     */
    private static CoherenceReport build(
            List<Animateur> animateurs,
            List<Stand> stands,
            List<Stand> ownHoursStands,
            List<Creneau> creneaux,
            List<ContrainteAdHoc> contraintes,
            List<VerrouillagePlanning> verrouillages,
            List<ConstraintDiagnostic> diagnostics,
            boolean withStaffing) {
        return CoherenceReferentielService.build(new Sources(
                animateurs,
                creneaux,
                stands,
                ownHoursStands,
                contraintes,
                verrouillages,
                Set::of,
                null,
                diagnostics,
                CreneauGridService.gridAnomalies(creneaux, new ParametresLegaux(), verrouillages, List.of()),
                OuvertureStandsAnalyzer.analyze(stands, creneaux),
                withStaffing
                        ? new StaffingAnalyzer()
                                .analyze(ProblemBuilder.buildPostes(stands, creneaux), animateurs, List.of(), 2100, 660)
                        : null));
    }

    private static CoherenceReport build(List<Animateur> animateurs, List<Stand> stands, List<Creneau> creneaux) {
        return build(animateurs, stands, creneaux, List.of(), List.of(), List.of(), false);
    }

    /* ------------------------------ Coverage ------------------------------ */

    /**
     * A consigne shuts every stand over its band: the timeslot inside it is the
     * consigne, not a timeslot outside every opening. The write-time warning is
     * replayed against the stands' own hours, as {@link CoherenceService} raises
     * it — never against the solver's view, which carries the band.
     */
    @Test
    void aTimeslotInsideAConsigneBandIsNotListedOutsideEveryOpening() {
        List<Creneau> creneaux = grille();
        ConsigneEdition consigne = new ConsigneEdition(
                SAMEDI,
                LocalTime.of(9, 0),
                LocalTime.of(14, 0),
                "arrêté préfectoral",
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null);
        List<Stand> solverView = List.of(stand("PLATEAU", false));
        HoraireStandResolver.apply(solverView, creneaux);
        ConsigneResolver.apply(solverView, List.of(consigne), creneaux);
        List<Stand> ownHours = List.of(stand("PLATEAU", false));
        HoraireStandResolver.apply(ownHours, creneaux);
        // The consigne really shuts the stand over the Saturday timeslot.
        assertThat(CoherenceAnalyzer.onCreneau(creneaux.get(0), solverView))
                .extracting(Avertissement::type)
                .contains(TypeAvertissement.CRENEAU_HORS_OUVERTURE_STANDS);

        CoherenceReport rapport =
                build(List.of(), solverView, ownHours, creneaux, List.of(), List.of(), List.of(), false);

        assertThat(rapport.anomalies())
                .filteredOn(ligne -> ligne.famille() == CoherenceFamily.CRENEAUX)
                .extracting(CoherenceIssue::code)
                .doesNotContain(
                        TypeAvertissement.CRENEAU_HORS_OUVERTURE_STANDS.name(),
                        TypeAvertissement.CRENEAU_DEBORDE_OUVERTURE_STANDS.name());
    }

    /**
     * The whole point of the checklist: a timeslot left outside every opening
     * by a later edit of a <em>stand</em>. The stand's own write warns about
     * the stand, never about the timeslot — and the checklist does.
     */
    @Test
    void aTimeslotLeftOutsideEveryOpeningByALaterStandEditIsListed() {
        List<Creneau> creneaux = grille();
        Stand avant = stand("PLATEAU", false);
        Stand apres = stand("PLATEAU", false);
        apres.getIndisponibilites().add(new IndisponibiliteStand(null, SAMEDI, LocalTime.MIDNIGHT, null, null));
        HoraireStandResolver.apply(List.of(apres), creneaux);

        assertThat(CoherenceAnalyzer.onStand(avant, apres, creneaux))
                .extracting(Avertissement::type)
                .doesNotContain(TypeAvertissement.CRENEAU_HORS_OUVERTURE_STANDS);
        assertThat(lines(build(List.of(adulte("A1")), List.of(apres), creneaux)))
                .contains(new Line(
                        CoherenceFamily.CRENEAUX,
                        CoherenceSeverity.A_VERIFIER,
                        TypeAvertissement.CRENEAU_HORS_OUVERTURE_STANDS.name(),
                        "1"));
    }

    @Test
    void theOffDaysOfEveryAnimateurAreReplayedAndAMinorIsInformation() {
        List<Line> lignes = lines(build(
                List.of(adulte("A1", LocalDate.of(2027, 12, 25)), adulte("A2", DIMANCHE), mineur("A3")),
                List.of(stand("PLATEAU", false)),
                grille()));

        assertThat(lignes)
                .contains(
                        new Line(
                                CoherenceFamily.ANIMATEURS,
                                CoherenceSeverity.A_VERIFIER,
                                TypeAvertissement.INDISPONIBILITE_HORS_EVENEMENT.name(),
                                "A1"),
                        new Line(
                                CoherenceFamily.ANIMATEURS,
                                CoherenceSeverity.A_VERIFIER,
                                TypeAvertissement.INDISPONIBILITE_JOUR_SANS_CRENEAU.name(),
                                "A2"),
                        new Line(
                                CoherenceFamily.ANIMATEURS,
                                CoherenceSeverity.INFORMATION,
                                TypeAvertissement.MINEUR_PENDANT_EVENEMENT.name(),
                                "A3"));
    }

    @Test
    void aTimeslotStartingBeforeEveryStandOpensIsListed() {
        Stand stand = stand("PLATEAU", false);
        stand.setHoraires(
                List.of(HoraireStand.everyDay(ModeHoraire.OUVERTURE, new FenetreHoraire(LocalTime.of(10, 0), null))));
        List<Creneau> creneaux =
                new ArrayList<>(List.of(new Creneau(7L, 1, SAMEDI, LocalTime.of(8, 0), LocalTime.of(13, 0))));

        assertThat(lines(build(List.of(adulte("A1")), List.of(stand), creneaux)))
                .contains(new Line(
                        CoherenceFamily.CRENEAUX,
                        CoherenceSeverity.A_VERIFIER,
                        TypeAvertissement.CRENEAU_DEBORDE_OUVERTURE_STANDS.name(),
                        "7"));
    }

    /**
     * The three stand warnings: the dated exception outside the event is
     * replayed, the window without effect and the never-open stand are the
     * opening report's own anomalies — the same fact, listed once.
     */
    @Test
    void theStandWarningsAreListedOnceEach() {
        Stand horsEvenement = stand("HORS", false);
        horsEvenement
                .getIndisponibilites()
                .add(new IndisponibiliteStand(null, LocalDate.of(2027, 12, 25), LocalTime.of(10, 0), null, null));
        Stand sansEffet = stand("SANS-EFFET", false);
        sansEffet.getOuvertures().add(new OuvertureStand(null, SAMEDI, LocalTime.of(20, 0), LocalTime.of(22, 0), null));
        Stand ferme = stand("FERME", false);
        ferme.setHoraires(
                List.of(HoraireStand.everyDay(ModeHoraire.FERMETURE, new FenetreHoraire(LocalTime.MIDNIGHT, null))));

        CoherenceReport rapport = build(List.of(adulte("A1")), List.of(horsEvenement, sansEffet, ferme), grille());

        assertThat(lines(rapport))
                .contains(
                        new Line(
                                CoherenceFamily.STANDS,
                                CoherenceSeverity.A_VERIFIER,
                                TypeAvertissement.STAND_EXCEPTION_HORS_EVENEMENT.name(),
                                "HORS"),
                        new Line(
                                CoherenceFamily.STANDS,
                                CoherenceSeverity.A_VERIFIER,
                                OuvertureStandsAnalyzer.AnomalyType.FENETRE_SANS_EFFET.name(),
                                "SANS-EFFET"),
                        new Line(
                                CoherenceFamily.STANDS,
                                CoherenceSeverity.A_VERIFIER,
                                OuvertureStandsAnalyzer.AnomalyType.STAND_JAMAIS_OUVERT.name(),
                                "FERME"));
        assertThat(rapport.anomalies())
                .extracting(CoherenceIssue::code)
                .doesNotContain(TypeAvertissement.STAND_FENETRE_SANS_EFFET.name())
                // The warning and the anomaly share the name: one line, not two.
                .filteredOn(code -> code.equals(TypeAvertissement.STAND_JAMAIS_OUVERT.name()))
                .hasSize(1);
    }

    /** The opening anomalies are the Ouvertures screen's, the very same report. */
    @Test
    void theOpeningAnomaliesAreTheOnesOfTheOpeningsScreen() {
        Stand chevauchant = stand("DOUBLE", false);
        chevauchant.setHoraires(List.of(
                HoraireStand.everyDay(
                        ModeHoraire.OUVERTURE, new FenetreHoraire(LocalTime.of(10, 0), LocalTime.of(13, 0), 2)),
                HoraireStand.everyDay(
                        ModeHoraire.OUVERTURE, new FenetreHoraire(LocalTime.of(10, 0), LocalTime.of(13, 0), 1))));
        List<Stand> stands = List.of(chevauchant, stand("PLATEAU", false));
        List<Creneau> creneaux = grille();

        CoherenceReport rapport = build(List.of(adulte("A1")), stands, creneaux);

        List<String> ecran = OuvertureStandsAnalyzer.analyze(stands, creneaux).anomalies().stream()
                .map(anomalie -> anomalie.type() + " " + anomalie.standId() + " " + anomalie.message())
                .toList();
        List<String> checklist = rapport.anomalies().stream()
                .filter(ligne -> ligne.famille() == CoherenceFamily.STANDS)
                .map(ligne -> ligne.code() + " " + ligne.objetId() + " " + ligne.message())
                .toList();
        assertThat(checklist).containsExactlyInAnyOrderElementsOf(ecran).isNotEmpty();
        assertThat(rapport.anomalies())
                .filteredOn(ligne -> ligne.code().equals("REGLES_CHEVAUCHANTES"))
                .singleElement()
                .extracting(CoherenceIssue::gravite)
                .isEqualTo(CoherenceSeverity.INFORMATION);
    }

    /**
     * The four readings of the exceptions, with the sentences Diagnostic →
     * Problèmes shows — blocking, since a critical cause guarantees a failed
     * solve.
     */
    @Test
    void theExceptionsAreTheOnesOfTheProblemsScreenWithTheSameSentences() {
        List<Creneau> creneaux = grille();
        Animateur absent = adulte("A1", SAMEDI, LUNDI);
        Animateur jeune = mineur("A2");
        Animateur fige = adulte("A3");
        Stand plateau = stand("PLATEAU", false);
        Stand bar = stand("BAR", true);
        ContrainteAdHoc surAbsent = forced("C01", absent);
        ContrainteAdHoc surJeune = forced("C02", jeune);
        surJeune.setStand(bar);
        ContrainteAdHoc surFige = forced("C03", fige);
        ContrainteAdHoc incompatibles = new ContrainteAdHoc("C04", TypeContrainteAdHoc.INCOMPATIBILITE);
        incompatibles.setAnimateursConcernes(List.of(adulte("A4"), adulte("A5")));
        ContrainteAdHoc affines = new ContrainteAdHoc("C05", TypeContrainteAdHoc.AFFINITE);
        affines.setAnimateursConcernes(List.of(adulte("A4"), adulte("A5")));
        VerrouillagePlanning verrou = new VerrouillagePlanning("V1", TypeVerrouillage.ANIMATEUR);
        verrou.setAnimateurId("A3");
        List<Animateur> animateurs = List.of(absent, jeune, fige, adulte("A4"), adulte("A5"));
        List<Stand> stands = List.of(plateau, bar);
        List<ContrainteAdHoc> contraintes = List.of(surAbsent, surJeune, surFige, incompatibles, affines);

        CoherenceReport rapport = build(animateurs, stands, creneaux, contraintes, List.of(verrou), List.of(), false);

        List<CoherenceIssue> ajustements = rapport.anomalies().stream()
                .filter(ligne -> ligne.famille() == CoherenceFamily.AJUSTEMENTS)
                .toList();
        assertThat(ajustements)
                .extracting(CoherenceIssue::code, CoherenceIssue::objetId)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("CONTRAINTES_AD_HOC_CONTRADICTOIRES", "C04"),
                        org.assertj.core.groups.Tuple.tuple(
                                TypeAvertissement.AFFECTATION_FORCEE_JOUR_INDISPONIBLE.name(), "C01"),
                        org.assertj.core.groups.Tuple.tuple(
                                TypeAvertissement.AFFECTATION_FORCEE_MOTIF_LEGAL.name(), "C02"),
                        org.assertj.core.groups.Tuple.tuple(
                                TypeAvertissement.AFFECTATION_FORCEE_SIEGE_VERROUILLE.name(), "C03"));
        assertThat(ajustements).allSatisfy(ligne -> {
            assertThat(ligne.gravite()).isEqualTo(CoherenceSeverity.BLOQUANT);
            assertThat(ligne.objet()).isEqualTo(CoherenceSubject.CONTRAINTE_AD_HOC);
        });

        List<String> problemes = new FeasibilityAnalyzer()
                        .analyze(
                                animateurs,
                                stands,
                                creneaux,
                                contraintes,
                                true,
                                new FeasibilityAnalyzer.PlanContext(List.of(verrou), Set::of, null))
                        .causes()
                        .stream()
                        .filter(cause -> cause.type() != TypeCauseInfaisabilite.CRENEAU_SOUS_EFFECTIF)
                        .map(CauseInfaisabilite::message)
                        .toList();
        assertThat(ajustements).extracting(CoherenceIssue::message).containsExactlyInAnyOrderElementsOf(problemes);
    }

    @Test
    void aCarWhoseMembersDeclaredDifferentDaysOffIsListed() {
        ContrainteAdHoc voiture = new ContrainteAdHoc("G01", TypeContrainteAdHoc.ARRIVEE_GROUPEE);
        voiture.setAnimateursConcernes(List.of(adulte("A1", SAMEDI), adulte("A2")));

        CoherenceReport rapport = build(
                List.of(adulte("A1", SAMEDI), adulte("A2")),
                List.of(),
                grille(),
                List.of(voiture),
                List.of(),
                List.of(),
                false);

        assertThat(rapport.anomalies())
                .filteredOn(ligne -> ligne.famille() == CoherenceFamily.AJUSTEMENTS)
                .singleElement()
                .satisfies(ligne -> {
                    assertThat(ligne.code()).isEqualTo(TypeAvertissement.ARRIVEE_GROUPEE_JOURS_DIVERGENTS.name());
                    assertThat(ligne.objetId()).isEqualTo("G01");
                    // Named by id, never by identity.
                    assertThat(ligne.message()).contains("A1").doesNotContain("Prénom");
                });
    }

    @Test
    void aLockOverAHardViolationOfTheLatestAnalysisIsListed() {
        VerrouillagePlanning verrou = new VerrouillagePlanning("V1", TypeVerrouillage.ANIMATEUR);
        verrou.setAnimateurId("A1");
        ConstraintDiagnostic violation = new ConstraintDiagnostic(
                "reposQuotidienMinimal",
                "-1hard",
                1,
                List.of(),
                null,
                null,
                List.of(new ViolationReference("…", "A1", "PLATEAU", 1L)));

        assertThat(lines(build(
                        List.of(adulte("A1")),
                        List.of(stand("PLATEAU", false)),
                        grille(),
                        List.of(),
                        List.of(verrou),
                        List.of(violation),
                        false)))
                .contains(new Line(
                        CoherenceFamily.AJUSTEMENTS,
                        CoherenceSeverity.A_VERIFIER,
                        TypeAvertissement.VERROUILLAGE_SUR_VIOLATION_DURE.name(),
                        "V1"));
    }

    /**
     * Every write-time warning has a line — under its own code, or under the
     * opening anomaly that states the same fact. A new {@link TypeAvertissement}
     * fails here until the checklist says where it goes.
     */
    @Test
    void everyWriteTimeWarningIsCoveredByTheTestsAbove() {
        Set<TypeAvertissement> couverts = EnumSet.of(
                TypeAvertissement.INDISPONIBILITE_HORS_EVENEMENT,
                TypeAvertissement.INDISPONIBILITE_JOUR_SANS_CRENEAU,
                TypeAvertissement.MINEUR_PENDANT_EVENEMENT,
                TypeAvertissement.CRENEAU_HORS_OUVERTURE_STANDS,
                TypeAvertissement.CRENEAU_DEBORDE_OUVERTURE_STANDS,
                TypeAvertissement.STAND_EXCEPTION_HORS_EVENEMENT,
                // As the opening report's FENETRE_SANS_EFFET and STAND_JAMAIS_OUVERT.
                TypeAvertissement.STAND_FENETRE_SANS_EFFET,
                TypeAvertissement.STAND_JAMAIS_OUVERT,
                TypeAvertissement.AFFECTATION_FORCEE_JOUR_INDISPONIBLE,
                TypeAvertissement.AFFECTATION_FORCEE_MOTIF_LEGAL,
                TypeAvertissement.AFFECTATION_FORCEE_SIEGE_VERROUILLE,
                // aCarWhoseMembersDeclaredDifferentDaysOffIsListed below.
                TypeAvertissement.ARRIVEE_GROUPEE_JOURS_DIVERGENTS,
                TypeAvertissement.VERROUILLAGE_SUR_VIOLATION_DURE);

        assertThat(couverts).containsExactlyInAnyOrder(TypeAvertissement.values());
    }

    /* ------------------------------- Shape -------------------------------- */

    @Test
    void anEmptyEditionHasAnEmptyChecklistWithEveryFamilyCountedAtZero() {
        CoherenceReport rapport = build(List.of(), List.of(), List.of());

        assertThat(rapport.anomalies()).isEmpty();
        assertThat(rapport.familles()).hasSize(CoherenceFamily.values().length).allSatisfy(famille -> {
            assertThat(famille.bloquants()).isZero();
            assertThat(famille.aVerifier()).isZero();
            assertThat(famille.informations()).isZero();
        });
    }

    @Test
    void aRosterBelowTheStaffingBoundIsACapacityLine() {
        CoherenceReport rapport = build(
                List.of(adulte("A1")),
                List.of(stand("S1", false), stand("S2", false), stand("S3", false)),
                grille(),
                List.of(),
                List.of(),
                List.of(),
                true);

        assertThat(rapport.anomalies())
                .filteredOn(ligne -> ligne.famille() == CoherenceFamily.CAPACITE)
                .extracting(CoherenceIssue::code)
                .contains("BESOIN_NON_COUVERT");
    }

    @Test
    void theCountsAddUpByFamilyAndBySeverity() {
        CoherenceReport rapport = build(
                List.of(adulte("A1", LocalDate.of(2027, 12, 25)), mineur("A2")),
                List.of(stand("PLATEAU", false)),
                grille());

        assertThat(rapport.aVerifier()).isEqualTo(1);
        assertThat(rapport.informations()).isEqualTo(1);
        assertThat(rapport.familles())
                .filteredOn(famille -> famille.famille() == CoherenceFamily.ANIMATEURS)
                .singleElement()
                .satisfies(famille -> {
                    assertThat(famille.aVerifier()).isEqualTo(1);
                    assertThat(famille.informations()).isEqualTo(1);
                });
        // Sorted by family, then severity: the fixable line comes before the information.
        assertThat(rapport.anomalies())
                .extracting(CoherenceIssue::gravite)
                .containsExactly(CoherenceSeverity.A_VERIFIER, CoherenceSeverity.INFORMATION);
    }
}
