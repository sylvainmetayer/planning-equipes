package dev.sylvain.planning.service.analyse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer.Anomaly;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer.AnomalyType;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer.CelluleCreneau;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer.CelluleJour;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer.EtatOuverture;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer.LigneStand;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer.RapportOuvertures;
import dev.sylvain.planning.service.referentiel.HoraireStandResolver;
import dev.sylvain.planning.service.referentiel.HoraireStandResolver.SourceHoraire;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link OuvertureStandsAnalyzer}: the stand × jour grid the "Ouvertures des
 * stands" screen shows, and the three anomalies it flags.
 *
 * <p>2026-07-08 is a Wednesday, as in the reference event.</p>
 */
class OuvertureStandsAnalyzerTest {

    private static final LocalDate JOUR_1 = LocalDate.of(2026, 7, 8);

    /** Two days, 10:00→20:00 each — the shape the amplitude fixtures use. */
    private static List<Creneau> deuxJours() {
        return new ArrayList<>(List.of(
                new Creneau(1L, 1, JOUR_1, LocalTime.of(10, 0), LocalTime.of(20, 0)),
                new Creneau(2L, 2, JOUR_1.plusDays(1), LocalTime.of(10, 0), LocalTime.of(20, 0))));
    }

    private static Stand stand(String id) {
        Stand stand = new Stand(id, id, Set.of(), 1, 1, false);
        stand.setIndisponibilites(new ArrayList<>());
        stand.setOuvertures(new ArrayList<>());
        stand.setHoraires(new ArrayList<>());
        return stand;
    }

    /** The cell without its segments, for the assertions that read the grid as integers. */
    private static CelluleCreneau withoutSegments(CelluleCreneau cellule) {
        return new CelluleCreneau(
                cellule.creneauId(),
                cellule.tranche(),
                cellule.effectif(),
                cellule.partiel(),
                cellule.horsFamille(),
                List.of());
    }

    private static RapportOuvertures analyze(List<Stand> stands, List<Creneau> creneaux) {
        HoraireStandResolver.apply(stands, creneaux);
        return OuvertureStandsAnalyzer.analyze(stands, creneaux);
    }

    @Test
    void unStandSansHoraireEstOuvertSurToutesLesAmplitudes() {
        Stand stand = stand("LIBRE");

        RapportOuvertures rapport = analyze(List.of(stand), deuxJours());

        assertThat(rapport.jours()).hasSize(2);
        assertThat(rapport.jours().get(0).minutes()).isEqualTo(600);
        LigneStand ligne = rapport.stands().get(0);
        assertThat(ligne.jours()).allSatisfy(cellule -> {
            assertThat(cellule.etat()).isEqualTo(EtatOuverture.OUVERT_TOTAL);
            assertThat(cellule.source()).isEqualTo(SourceHoraire.DEFAUT);
            assertThat(cellule.postes()).isEqualTo(1);
        });
        assertThat(ligne.minutesOuvertes()).isEqualTo(1200);
        assertThat(rapport.postesTotal()).isEqualTo(2);
        assertThat(rapport.anomalies()).isEmpty();
    }

    /**
     * The interesting cell: a rule narrows the day, so the grid must show the
     * clamped window, count the partial state, and say a <i>rule</i> decided it —
     * that last part is what lets an admin find the culprit.
     */
    @Test
    void uneRegleDonneUneCellulePartielleAttribueeALaRegle() {
        Stand stand = stand("APREM");
        stand.setHoraires(
                List.of(HoraireStand.everyDay(ModeHoraire.OUVERTURE, new FenetreHoraire(LocalTime.of(14, 0), null))));

        RapportOuvertures rapport = analyze(List.of(stand), deuxJours());

        CelluleJour cellule = rapport.stands().get(0).jours().get(0);
        assertThat(cellule.etat()).isEqualTo(EtatOuverture.OUVERT_PARTIEL);
        assertThat(cellule.source()).isEqualTo(SourceHoraire.REGLE);
        assertThat(cellule.minutesOuvertes()).isEqualTo(360);
        assertThat(cellule.minutesAmplitude()).isEqualTo(600);
        assertThat(cellule.fenetres()).singleElement().satisfies(fenetre -> {
            assertThat(fenetre.heureDebut()).isEqualTo(LocalTime.of(14, 0));
            assertThat(fenetre.heureFin()).isEqualTo(LocalTime.of(20, 0));
        });
    }

    @Test
    void lesColonnesSuiventLesBornesDesFenetresEtAucuneCaseNEstPartielle() {
        Stand variable = stand("VARIABLE");
        variable.getOuvertures()
                .add(new OuvertureStand(null, JOUR_1, LocalTime.of(14, 0), LocalTime.of(19, 0), null, 4));
        variable.getOuvertures()
                .add(new OuvertureStand(null, JOUR_1, LocalTime.of(19, 0), LocalTime.of(20, 0), null, 2));
        Stand libre = stand("LIBRE");

        RapportOuvertures rapport = analyze(List.of(variable, libre), deuxJours());

        // Day 1: the créneau 10-20 cut at 14:00 and 19:00 for everyone; day 2 in one piece.
        assertThat(rapport.jours().get(0).creneaux())
                .extracting(colonne ->
                        colonne.id() + ":" + colonne.tranche() + " " + colonne.heureDebut() + "-" + colonne.heureFin())
                .containsExactly("1:0 10:00-14:00", "1:1 14:00-19:00", "1:2 19:00-20:00");
        assertThat(rapport.jours().get(1).creneaux())
                .extracting(colonne -> colonne.id() + ":" + colonne.tranche())
                .containsExactly("2:0");
        assertThat(rapport.jours().get(0).nombreCreneaux()).isEqualTo(1);

        List<CelluleCreneau> cellesDuVariable =
                rapport.stands().get(0).jours().get(0).creneaux();
        assertThat(cellesDuVariable).extracting(CelluleCreneau::effectif).containsExactly(null, 4, 2);
        assertThat(cellesDuVariable).noneMatch(CelluleCreneau::partiel);
        assertThat(cellesDuVariable.get(1).segments())
                .extracting(segment -> segment.heureDebut() + "-" + segment.heureFin() + "@" + segment.effectif())
                .containsExactly("14:00-19:00@4");
        // The open-by-default stand reads its minimum under every column, whole.
        List<CelluleCreneau> cellesDuLibre =
                rapport.stands().get(1).jours().get(0).creneaux();
        assertThat(cellesDuLibre).extracting(CelluleCreneau::effectif).containsExactly(1, 1, 1);
        assertThat(cellesDuLibre).noneMatch(CelluleCreneau::partiel);
    }

    @Test
    void uneExceptionDateeEstAttribueeALException() {
        Stand stand = stand("EXCEPTION");
        stand.setHoraires(
                List.of(HoraireStand.everyDay(ModeHoraire.OUVERTURE, new FenetreHoraire(LocalTime.of(14, 0), null))));
        stand.getIndisponibilites().add(new IndisponibiliteStand(null, JOUR_1, LocalTime.of(10, 0), null, "Férié"));

        RapportOuvertures rapport = analyze(List.of(stand), deuxJours());

        List<CelluleJour> jours = rapport.stands().get(0).jours();
        assertThat(jours.get(0).source()).isEqualTo(SourceHoraire.EXCEPTION);
        assertThat(jours.get(0).etat()).isEqualTo(EtatOuverture.FERME);
        assertThat(jours.get(0).postes()).isZero();
        assertThat(jours.get(1).source()).isEqualTo(SourceHoraire.REGLE);
    }

    /**
     * The entry grid reads one cell per column: the configured headcount when
     * the stand is open on the whole column, nothing when closed — and a
     * window boundary inside a créneau cuts the créneau into columns rather
     * than leaving a partial cell.
     */
    @Test
    void chaqueJourPorteUneCelluleParCreneau() {
        List<Creneau> creneaux = deuxJours();
        creneaux.add(new Creneau(3L, 1, JOUR_1, LocalTime.of(20, 0), LocalTime.of(0, 0)));
        Stand stand = stand("PROFIL");
        stand.setEffectifMin(2);
        stand.setEffectifMax(4);
        stand.setHoraires(List.of(HoraireStand.everyDay(
                ModeHoraire.OUVERTURE,
                new FenetreHoraire(LocalTime.of(10, 0), LocalTime.of(20, 0), null),
                new FenetreHoraire(LocalTime.of(21, 0), null, 4))));

        RapportOuvertures rapport = analyze(List.of(stand), creneaux);

        assertThat(rapport.jours().get(0).creneaux())
                .extracting(colonne -> colonne.heureDebut())
                .containsExactly(LocalTime.of(10, 0), LocalTime.of(20, 0), LocalTime.of(21, 0));
        // The nocturne opens at 21:00 only: the créneau 20-00 gets two columns, nothing partial.
        List<OuvertureStandsAnalyzer.CelluleCreneau> cellules =
                rapport.stands().get(0).jours().get(0).creneaux();
        assertThat(cellules)
                .map(OuvertureStandsAnalyzerTest::withoutSegments)
                .containsExactly(
                        new OuvertureStandsAnalyzer.CelluleCreneau(1L, 2, false, false),
                        new OuvertureStandsAnalyzer.CelluleCreneau(3L, 0, null, false, false, List.of()),
                        new OuvertureStandsAnalyzer.CelluleCreneau(3L, 1, 4, false, false, List.of()));
        assertThat(rapport.stands().get(0).jours().get(1).creneaux())
                .map(OuvertureStandsAnalyzerTest::withoutSegments)
                .containsExactly(new OuvertureStandsAnalyzer.CelluleCreneau(2L, 2, false, false));
    }

    /** The recurrence preview validates a grid holding rows a rule would add: no id yet, still one column each. */
    @Test
    void unCreneauPasEncoreEcritADroitASaColonneSansIdentifiant() {
        List<Creneau> creneaux = deuxJours();
        creneaux.add(new Creneau(null, 1, JOUR_1, LocalTime.of(20, 0), LocalTime.of(23, 0)));

        RapportOuvertures rapport = analyze(List.of(stand("LIBRE")), creneaux);

        assertThat(rapport.jours().get(0).creneaux())
                .extracting(colonne -> colonne.id())
                .containsExactly(1L, null);
        assertThat(rapport.stands().get(0).jours().get(0).creneaux())
                .map(OuvertureStandsAnalyzerTest::withoutSegments)
                .containsExactly(
                        new OuvertureStandsAnalyzer.CelluleCreneau(1L, 1, false, false),
                        new OuvertureStandsAnalyzer.CelluleCreneau(null, 1, false, false));
    }

    @Test
    void unStandFermeUnJourALaCelluleVideSurChaqueCreneau() {
        Stand stand = stand("FERME");
        stand.getIndisponibilites().add(new IndisponibiliteStand(null, JOUR_1, LocalTime.of(0, 0), null, null));

        RapportOuvertures rapport = analyze(List.of(stand), deuxJours());

        assertThat(rapport.stands().get(0).jours().get(0).creneaux())
                .map(OuvertureStandsAnalyzerTest::withoutSegments)
                .containsExactly(new OuvertureStandsAnalyzer.CelluleCreneau(1L, null, false, false));
        assertThat(rapport.stands().get(0).jours().get(1).creneaux())
                .map(OuvertureStandsAnalyzerTest::withoutSegments)
                .containsExactly(new OuvertureStandsAnalyzer.CelluleCreneau(2L, 1, false, false));
    }

    /** A day cut into two windows must read as two stretches, not as one 10:00→20:00 block. */
    @Test
    void uneCoupureMeridienneDonneDeuxFenetres() {
        Stand stand = stand("BOURSE");
        stand.setHoraires(List.of(HoraireStand.everyDay(
                ModeHoraire.OUVERTURE,
                new FenetreHoraire(LocalTime.of(10, 0), LocalTime.of(12, 0)),
                new FenetreHoraire(LocalTime.of(14, 0), null))));

        RapportOuvertures rapport = analyze(List.of(stand), deuxJours());

        CelluleJour cellule = rapport.stands().get(0).jours().get(0);
        assertThat(cellule.fenetres()).hasSize(2);
        assertThat(cellule.minutesOuvertes()).isEqualTo(120 + 360);
        // One seat per open segment, like buildPostes.
        assertThat(cellule.postes()).isEqualTo(2);
    }

    /**
     * Vacations of the same day overlap by design (the handover). The cell must
     * show one continuous stretch and count its minutes once, not one band per
     * vacation.
     */
    @Test
    void desVacationsQuiSeChevauchentDonnentUneSeuleFenetre() {
        List<Creneau> vacations = new ArrayList<>(List.of(
                new Creneau(1L, 1, JOUR_1, LocalTime.of(10, 0), LocalTime.of(15, 0)),
                new Creneau(2L, 1, JOUR_1, LocalTime.of(14, 30), LocalTime.of(20, 0))));

        RapportOuvertures rapport = analyze(List.of(stand("CONTINU")), vacations);

        assertThat(rapport.jours().get(0).minutes()).isEqualTo(600);
        CelluleJour cellule = rapport.stands().get(0).jours().get(0);
        assertThat(cellule.fenetres()).singleElement().satisfies(fenetre -> {
            assertThat(fenetre.heureDebut()).isEqualTo(LocalTime.of(10, 0));
            assertThat(fenetre.heureFin()).isEqualTo(LocalTime.of(20, 0));
        });
        assertThat(cellule.minutesOuvertes()).isEqualTo(600);
        assertThat(cellule.etat()).isEqualTo(EtatOuverture.OUVERT_TOTAL);
        assertThat(cellule.postes()).isEqualTo(2);
    }

    @Test
    void unStandFermePartoutEstSignale() {
        Stand stand = stand("ABSENT");
        stand.setHoraires(
                List.of(HoraireStand.everyDay(ModeHoraire.FERMETURE, new FenetreHoraire(LocalTime.of(0, 0), null))));

        RapportOuvertures rapport = analyze(List.of(stand), deuxJours());

        assertThat(rapport.standsJamaisOuverts()).isEqualTo(1);
        assertThat(rapport.postesTotal()).isZero();
        assertThat(rapport.anomalies()).extracting(Anomaly::type).contains(AnomalyType.STAND_JAMAIS_OUVERT);
    }

    /**
     * The historical {@code 23:59} artefact: a closure ending one minute before a
     * day that closes at midnight leaves exactly one staffable minute. That is a
     * poste nobody can hold, and the screen exists to make it visible.
     */
    @Test
    void unSegmentTropCourtPourEtreUnCreneauEstSignale() {
        Stand stand = stand("UNE-MINUTE");
        List<Creneau> jusquaMinuit =
                new ArrayList<>(List.of(new Creneau(1L, 1, JOUR_1, LocalTime.of(10, 0), LocalTime.MIDNIGHT)));
        stand.getIndisponibilites()
                .add(new IndisponibiliteStand(null, JOUR_1, LocalTime.of(10, 0), LocalTime.of(23, 59), null));

        RapportOuvertures rapport = analyze(List.of(stand), jusquaMinuit);

        assertThat(rapport.stands().get(0).jours().get(0).minutesOuvertes()).isEqualTo(1);
        assertThat(rapport.anomalies())
                .filteredOn(anomalie -> anomalie.type() == AnomalyType.SEGMENT_TROP_COURT)
                .singleElement()
                .satisfies(anomalie -> {
                    assertThat(anomalie.standId()).isEqualTo("UNE-MINUTE");
                    assertThat(anomalie.date()).isEqualTo(JOUR_1);
                    assertThat(anomalie.message()).contains("1 min");
                });
    }

    /**
     * The anomaly must stay silent on a short but deliberate opening: a stand
     * open for two hours can perfectly well be staffed, and an alert that fires
     * on correct data stops being read.
     */
    @Test
    void uneOuvertureCourteMaisVoulueNEstPasSignalee() {
        Stand stand = stand("DEUX-HEURES");
        stand.setHoraires(List.of(HoraireStand.everyDay(
                ModeHoraire.OUVERTURE, new FenetreHoraire(LocalTime.of(14, 0), LocalTime.of(16, 0)))));

        RapportOuvertures rapport = analyze(List.of(stand), deuxJours());

        assertThat(rapport.stands().get(0).jours().get(0).minutesOuvertes()).isEqualTo(120);
        assertThat(rapport.anomalies())
                .filteredOn(anomalie -> anomalie.type() == AnomalyType.SEGMENT_TROP_COURT)
                .isEmpty();
    }

    /** A window entered outside the day's amplitude changes nothing — and says so. */
    @Test
    void uneFenetreHorsAmplitudeEstSignaleeSansEffet() {
        Stand stand = stand("HORS-AMPLITUDE");
        stand.getOuvertures().add(new OuvertureStand(null, JOUR_1, LocalTime.of(21, 0), LocalTime.of(23, 0), null));

        RapportOuvertures rapport = analyze(List.of(stand), deuxJours());

        assertThat(rapport.anomalies())
                .filteredOn(anomalie -> anomalie.type() == AnomalyType.FENETRE_SANS_EFFET)
                .singleElement()
                .satisfies(anomalie -> assertThat(anomalie.date()).isEqualTo(JOUR_1));
        // And the consequence: that day the stand is closed, despite the opening entered.
        assertThat(rapport.stands().get(0).jours().get(0).etat()).isEqualTo(EtatOuverture.FERME);
    }

    @Test
    void uneFenetreDansLAmplitudeNEstPasSignalee() {
        Stand stand = stand("DANS-AMPLITUDE");
        stand.getOuvertures().add(new OuvertureStand(null, JOUR_1, LocalTime.of(14, 0), LocalTime.of(16, 0), null));

        assertThat(analyze(List.of(stand), deuxJours()).anomalies())
                .filteredOn(anomalie -> anomalie.type() == AnomalyType.FENETRE_SANS_EFFET)
                .isEmpty();
    }

    @Test
    void sansCreneauLeRapportEstVide() {
        RapportOuvertures rapport = analyze(List.of(stand("SEUL")), new ArrayList<>());

        assertThat(rapport.jours()).isEmpty();
        assertThat(rapport.postesTotal()).isZero();
    }

    /**
     * A staggered grid holds one variant of every vacation per famille, and a
     * stand is paired with exactly one of them: the cells of the others carry
     * no seat and must not be typed.
     */
    @Test
    void lesCreneauxDUneAutreFamilleSontInertes() {
        List<Creneau> creneaux = new ArrayList<>(List.of(
                new Creneau(1L, 1, JOUR_1, LocalTime.of(10, 0), LocalTime.of(14, 0)),
                new Creneau(2L, 1, JOUR_1, LocalTime.of(11, 0), LocalTime.of(15, 0))));
        creneaux.get(1).setFamille(1);
        List<Stand> stands = new ArrayList<>(List.of(stand("A"), stand("B")));

        RapportOuvertures rapport = analyze(stands, creneaux);

        // The two stands land on the two families, so each sees one live cell
        // and one inert — and every stand's inert cell is the other's live one.
        List<OuvertureStandsAnalyzer.CelluleCreneau> premier =
                rapport.stands().get(0).jours().get(0).creneaux();
        List<OuvertureStandsAnalyzer.CelluleCreneau> second =
                rapport.stands().get(1).jours().get(0).creneaux();
        assertThat(premier)
                .extracting(OuvertureStandsAnalyzer.CelluleCreneau::horsFamille)
                .containsExactly(false, true);
        assertThat(second)
                .extracting(OuvertureStandsAnalyzer.CelluleCreneau::horsFamille)
                .containsExactly(true, false);
        assertThat(premier.get(1).effectif()).isNull();
    }

    /** Columns are read back by position, so a day's créneaux must be chronological whatever their ids. */
    @Test
    void lesColonnesDUnJourSontDansLOrdreDesHeuresQuelsQueSoientLesIdentifiants() {
        List<Creneau> creneaux = new ArrayList<>(List.of(
                new Creneau(9L, 1, JOUR_1, LocalTime.of(14, 0), LocalTime.of(20, 0)),
                new Creneau(2L, 1, JOUR_1, LocalTime.of(10, 0), LocalTime.of(12, 0))));

        RapportOuvertures rapport = analyze(List.of(stand("LIBRE")), creneaux);

        assertThat(rapport.jours().get(0).creneaux())
                .extracting(colonne -> colonne.heureDebut().toString())
                .containsExactly("10:00", "14:00");
        assertThat(rapport.stands().get(0).jours().get(0).creneaux())
                .extracting(OuvertureStandsAnalyzer.CelluleCreneau::creneauId)
                .containsExactly(2L, 9L);
    }
}
