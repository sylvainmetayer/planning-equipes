package dev.sylvain.planning.service.consigne;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.ConsigneEdition;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Creneau.SegmentOuvert;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.referentiel.HoraireStandResolver;
import dev.sylvain.planning.service.solve.ProblemBuilder;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link ConsigneResolver}: the band closes every stand and nothing else, an
 * opening is an extension chosen stand by stand, and the créneaux the consigne
 * added belong to nobody's usual hours. Everything is read through the seats
 * {@code ProblemBuilder} would give the solver, since that is what a consigne
 * changes.
 */
class ConsigneResolverTest {

    private static final LocalDate LUNDI = LocalDate.of(2027, 2, 1);
    private static final LocalDate MARDI = LUNDI.plusDays(1);

    private static final Creneau MATIN = creneau(1, LUNDI, 10, 12);
    private static final Creneau RELAIS_1 = creneau(2, LUNDI, 12, 13);
    private static final Creneau RELAIS_2 = creneau(3, LUNDI, 13, 14);
    private static final Creneau APRES_MIDI = creneau(4, LUNDI, 14, 20);
    private static final Creneau SOIR_AJOUTE = creneau(5, LUNDI, 20, 22);
    private static final Creneau MARDI_APRES_MIDI = creneau(6, MARDI, 14, 20);

    private static final List<Creneau> GRILLE =
            List.of(MATIN, RELAIS_1, RELAIS_2, APRES_MIDI, SOIR_AJOUTE, MARDI_APRES_MIDI);

    @Test
    void laBandeVideLesRelaisEtRaccourcitLApresMidiDeTousLesStands() {
        Stand bourse = standWithRule("BOURSE", 2);
        ConsigneEdition consigne = consigne(LocalTime.of(12, 0), LocalTime.of(18, 0), List.of());

        resolve(List.of(bourse), consigne);

        assertThat(segments(MATIN, bourse)).containsExactly(new SegmentOuvert(0, 120, 2));
        assertThat(segments(RELAIS_1, bourse)).isEmpty();
        assertThat(segments(RELAIS_2, bourse)).isEmpty();
        // 14h-20h becomes 18h-20h: minutes 240 to 360 of the créneau.
        assertThat(segments(APRES_MIDI, bourse)).containsExactly(new SegmentOuvert(240, 360, 2));
    }

    @Test
    void unStandNonOuvertResteFermeSurLeCreneauQueLaConsigneAAjoute() {
        Stand bourse = standWithRule("BOURSE", 2);
        Stand sansRegle = stand("SANS-REGLE", 1);
        ConsigneEdition consigne = consigne(LocalTime.of(12, 0), LocalTime.of(18, 0), List.of())
                .withCreneauxAjoutes(List.of(SOIR_AJOUTE.getId()));

        resolve(List.of(bourse, sansRegle), consigne);

        assertThat(segments(SOIR_AJOUTE, bourse)).isEmpty();
        assertThat(segments(SOIR_AJOUTE, sansRegle)).isEmpty();
        // The stand with no schedule at all keeps its day outside the band.
        assertThat(segments(MATIN, sansRegle)).containsExactly(new SegmentOuvert(0, 120, 1));
        assertThat(segments(RELAIS_1, sansRegle)).isEmpty();
        assertThat(segments(APRES_MIDI, sansRegle)).containsExactly(new SegmentOuvert(240, 360, 1));
    }

    @Test
    void uneOuvertureProlongeLaJourneeAvecLEffectifPerduDansLaBande() {
        Stand bourse = standWithRule("BOURSE", 3);
        ConsigneEdition consigne = consigne(
                        LocalTime.of(12, 0),
                        LocalTime.of(18, 0),
                        List.of(ouverture("BOURSE", LocalTime.of(18, 0), LocalTime.of(22, 0), null)))
                .withCreneauxAjoutes(List.of(SOIR_AJOUTE.getId()));

        resolve(List.of(bourse), consigne);

        assertThat(segments(APRES_MIDI, bourse)).containsExactly(new SegmentOuvert(240, 360, 3));
        assertThat(segments(SOIR_AJOUTE, bourse)).containsExactly(new SegmentOuvert(0, 120, 3));
        assertThat(ProblemBuilder.buildPostes(List.of(bourse), GRILLE))
                .filteredOn(poste -> poste.getCreneau() == SOIR_AJOUTE)
                .hasSize(3);
    }

    @Test
    void lEffectifSaisiLEmporteSurLHeritage() {
        Stand bourse = standWithRule("BOURSE", 3);
        ConsigneEdition consigne = consigne(
                        LocalTime.of(12, 0),
                        LocalTime.of(18, 0),
                        List.of(ouverture("BOURSE", LocalTime.of(20, 0), LocalTime.of(22, 0), 1)))
                .withCreneauxAjoutes(List.of(SOIR_AJOUTE.getId()));

        resolve(List.of(bourse), consigne);

        assertThat(segments(SOIR_AJOUTE, bourse)).containsExactly(new SegmentOuvert(0, 120, 1));
        // Outside its window the stand keeps its own headcount.
        assertThat(segments(APRES_MIDI, bourse)).containsExactly(new SegmentOuvert(240, 360, 3));
    }

    @Test
    void laBandeLEmporteSurUneOuvertureQuiLaChevauche() {
        Stand bourse = standWithRule("BOURSE", 2);
        ConsigneEdition consigne = consigne(
                LocalTime.of(12, 0),
                LocalTime.of(18, 0),
                List.of(ouverture("BOURSE", LocalTime.of(13, 0), LocalTime.of(20, 0), 4)));

        resolve(List.of(bourse), consigne);

        assertThat(segments(RELAIS_2, bourse)).isEmpty();
        // 18h-20h at the higher of the two headcounts stated over those minutes.
        assertThat(segments(APRES_MIDI, bourse)).containsExactly(new SegmentOuvert(240, 360, 4));
    }

    @Test
    void unStandPeutRouvrirSurPlusieursFenetres() {
        Stand bourse = standWithRule("BOURSE", 2);
        Creneau tot = creneau(7, LUNDI, 8, 10);
        ConsigneEdition consigne = consigne(
                        LocalTime.of(12, 0),
                        LocalTime.of(18, 0),
                        List.of(
                                ouverture("BOURSE", LocalTime.of(8, 0), LocalTime.of(10, 0), null),
                                ouverture("BOURSE", LocalTime.of(20, 0), LocalTime.of(22, 0), null)))
                .withCreneauxAjoutes(List.of(tot.getId(), SOIR_AJOUTE.getId()));
        List<Creneau> grille = new ArrayList<>(GRILLE);
        grille.add(tot);

        HoraireStandResolver.apply(List.of(bourse), grille);
        ConsigneResolver.apply(List.of(bourse), List.of(consigne), grille);

        assertThat(segments(tot, bourse)).containsExactly(new SegmentOuvert(0, 120, 2));
        assertThat(segments(SOIR_AJOUTE, bourse)).containsExactly(new SegmentOuvert(0, 120, 2));
        assertThat(segments(MATIN, bourse)).containsExactly(new SegmentOuvert(0, 120, 2));
    }

    @Test
    void unJourDitALaMainEstFermeParLaBandeEtRouvertSeulementSurChoix() {
        Stand panne = stand("PANNE", 2);
        panne.setOuvertures(new ArrayList<>(
                List.of(new OuvertureStand(null, LUNDI, LocalTime.of(14, 0), LocalTime.of(20, 0), "réparation", 2))));
        ConsigneEdition sansOuverture = consigne(LocalTime.of(12, 0), LocalTime.of(18, 0), List.of());

        resolve(List.of(panne), sansOuverture);
        assertThat(segments(MATIN, panne)).isEmpty();
        assertThat(segments(APRES_MIDI, panne)).containsExactly(new SegmentOuvert(240, 360, 2));

        ConsigneEdition avecOuverture = consigne(
                        LocalTime.of(12, 0),
                        LocalTime.of(18, 0),
                        List.of(ouverture("PANNE", LocalTime.of(20, 0), LocalTime.of(22, 0), null)))
                .withCreneauxAjoutes(List.of(SOIR_AJOUTE.getId()));
        resolve(List.of(panne), avecOuverture);
        assertThat(segments(SOIR_AJOUTE, panne)).containsExactly(new SegmentOuvert(0, 120, 2));
    }

    @Test
    void uneJourneeEntiereFermeTout() {
        Stand bourse = standWithRule("BOURSE", 2);
        Stand sansRegle = stand("SANS-REGLE", 1);
        ConsigneEdition consigne = consigne(LocalTime.MIDNIGHT, null, List.of());

        resolve(List.of(bourse, sansRegle), consigne);

        for (Creneau creneau : List.of(MATIN, RELAIS_1, RELAIS_2, APRES_MIDI)) {
            assertThat(segments(creneau, bourse)).isEmpty();
            assertThat(segments(creneau, sansRegle)).isEmpty();
        }
        assertThat(bourse.getIndisponibilitesEffectives())
                .filteredOn(fermeture -> LUNDI.equals(fermeture.getDate()))
                .extracting(IndisponibiliteStand::getMotif)
                .containsExactly(ConsigneResolver.MOTIF_CONSIGNE);
    }

    @Test
    void uneAutreDateEtLesListesPersisteesNeSontPasTouchees() {
        Stand bourse = standWithRule("BOURSE", 2);
        ConsigneEdition consigne = consigne(
                LocalTime.of(12, 0),
                LocalTime.of(18, 0),
                List.of(ouverture("BOURSE", LocalTime.of(18, 0), LocalTime.of(22, 0), null)));

        resolve(List.of(bourse), consigne);

        assertThat(segments(MARDI_APRES_MIDI, bourse)).containsExactly(new SegmentOuvert(0, 360, 2));
        assertThat(bourse.getOuvertures()).isEmpty();
        assertThat(bourse.getIndisponibilites()).isEmpty();
    }

    @Test
    void sansConsigneRienNeChange() {
        Stand bourse = standWithRule("BOURSE", 2);
        HoraireStandResolver.apply(List.of(bourse), GRILLE);
        List<OuvertureStand> avant = List.copyOf(bourse.getOuverturesEffectives());

        ConsigneResolver.apply(List.of(bourse), List.of(), GRILLE);

        assertThat(bourse.getOuverturesEffectives()).containsExactlyElementsOf(avant);
    }

    /* ------------------------------ fixtures ------------------------------ */

    private static void resolve(List<Stand> stands, ConsigneEdition consigne) {
        HoraireStandResolver.apply(stands, GRILLE);
        ConsigneResolver.apply(stands, List.of(consigne), GRILLE);
    }

    private static List<SegmentOuvert> segments(Creneau creneau, Stand stand) {
        return creneau.segmentsOuverts(stand);
    }

    private static Creneau creneau(long id, LocalDate date, int debut, int fin) {
        return new Creneau(id, 1, date, LocalTime.of(debut, 0), LocalTime.of(fin, 0));
    }

    private static Stand stand(String id, int effectifMin) {
        return new Stand(id, id, Set.of(), effectifMin, Math.max(effectifMin, 4), false);
    }

    /** Open 10h-12h then 14h-20h every day, at {@code effectif}. */
    private static Stand standWithRule(String id, int effectif) {
        Stand stand = stand(id, 1);
        stand.setHoraires(List.of(HoraireStand.everyDay(
                ModeHoraire.OUVERTURE,
                new FenetreHoraire(LocalTime.of(10, 0), LocalTime.of(12, 0), effectif),
                new FenetreHoraire(LocalTime.of(14, 0), LocalTime.of(20, 0), effectif))));
        return stand;
    }

    private static ConsigneEdition consigne(
            LocalTime debut, LocalTime fin, List<ConsigneEdition.Ouverture> ouvertures) {
        return new ConsigneEdition(
                LUNDI, debut, fin, "arrêté préfectoral", null, List.of(), ouvertures, List.of(), null, null);
    }

    private static ConsigneEdition.Ouverture ouverture(
            String standId, LocalTime debut, LocalTime fin, Integer effectif) {
        return new ConsigneEdition.Ouverture(standId, debut, fin, effectif);
    }
}
