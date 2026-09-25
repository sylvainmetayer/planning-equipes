package dev.sylvain.planning.service.consigne;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.ConsigneEdition;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.consigne.LayerCalendar.LayerCell;
import dev.sylvain.planning.service.consigne.LayerCalendar.LayerDay;
import dev.sylvain.planning.service.consigne.LayerCalendar.LayerWindow;
import dev.sylvain.planning.service.consigne.LayerCalendar.OpeningLayers;
import dev.sylvain.planning.service.referentiel.HoraireStandResolver;
import dev.sylvain.planning.service.referentiel.HoraireStandResolver.SourceHoraire;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * {@link LayerCalendar}: each layer of a stand's day read off the resolver
 * that owns it — the nominal day off the rules alone, the effective one once
 * the consigne has run — with where the nominal day comes from.
 */
class LayerCalendarTest {

    /** A Saturday. */
    private static final LocalDate SAMEDI = LocalDate.of(2026, 7, 11);

    private static final LocalDate DIMANCHE = SAMEDI.plusDays(1);

    private static final Creneau APRES_MIDI = creneau(1, SAMEDI, 14, 20);
    private static final Creneau SOIR_AJOUTE = creneau(2, SAMEDI, 20, 22);
    private static final Creneau DIMANCHE_APRES_MIDI = creneau(3, DIMANCHE, 14, 20);

    private static final List<Creneau> GRILLE = List.of(APRES_MIDI, SOIR_AJOUTE, DIMANCHE_APRES_MIDI);

    @Test
    void aDayUnderConsigneShowsTheRuleTheBandAndWhatIsLeft() {
        ConsigneEdition consigne = new ConsigneEdition(
                SAMEDI,
                LocalTime.of(18, 0),
                LocalTime.of(20, 0),
                "Plan canicule",
                "Plan canicule",
                List.of(),
                List.of(new ConsigneEdition.Ouverture("BOURSE", LocalTime.of(20, 0), LocalTime.of(22, 0), null)),
                List.of(SOIR_AJOUTE.getId()),
                null,
                null,
                null);

        OpeningLayers couches = build(LayerCalendarTest::standWithRule, List.of(consigne), null, null);

        LayerDay samedi = couches.jours().get(0);
        assertThat(samedi.consigne().debutMinutes()).isEqualTo(18 * 60);
        assertThat(samedi.consigne().finMinutes()).isEqualTo(20 * 60);
        assertThat(samedi.consigne().motif()).isEqualTo("Plan canicule");
        assertThat(samedi.vacations())
                .extracting(LayerCalendar.LayerTimeslot::addedByConsigne)
                .containsExactly(false, true);
        LayerCell cellule = couches.stands().get(0).jours().get(0);
        assertThat(cellule.source()).isEqualTo(SourceHoraire.REGLE);
        assertThat(cellule.horaireIds()).containsExactly(7L);
        assertThat(cellule.nominal()).containsExactly(new LayerWindow(14 * 60, 20 * 60, 3));
        assertThat(cellule.reopenings()).containsExactly(new LayerWindow(20 * 60, 22 * 60, null));
        // 14h-20h amputated by the band, then the reopening at the headcount the band took.
        assertThat(cellule.effective())
                .containsExactly(new LayerWindow(14 * 60, 18 * 60, 3), new LayerWindow(20 * 60, 22 * 60, 3));

        // The Sunday has no consigne: the effective day is the nominal one.
        LayerCell dimanche = couches.stands().get(0).jours().get(1);
        assertThat(couches.jours().get(1).consigne()).isNull();
        assertThat(dimanche.reopenings()).isEmpty();
        assertThat(dimanche.effective()).isEqualTo(dimanche.nominal());
    }

    @Test
    void aDatedExceptionIsToldFromARuleAndAStandWithoutHoursSaysItIsOpenByDefault() {
        OpeningLayers couches = build(
                () -> {
                    Stand stand = standWithRule();
                    stand.setOuvertures(new ArrayList<>(List.of(new OuvertureStand(
                            null, DIMANCHE, LocalTime.of(15, 0), LocalTime.of(17, 0), "Tournoi", 2))));
                    return stand;
                },
                List.of(),
                null,
                null);
        LayerCell dimanche = couches.stands().get(0).jours().get(1);
        assertThat(dimanche.source()).isEqualTo(SourceHoraire.EXCEPTION);
        assertThat(dimanche.motif()).isEqualTo("Tournoi");
        assertThat(dimanche.horaireIds()).isEmpty();
        assertThat(dimanche.nominal()).containsExactly(new LayerWindow(15 * 60, 17 * 60, 2));

        OpeningLayers libre = build(() -> stand("LIBRE"), List.of(), null, null);
        LayerCell jour = libre.stands().get(0).jours().get(0);
        assertThat(jour.source()).isEqualTo(SourceHoraire.DEFAUT);
        assertThat(jour.nominal()).containsExactly(new LayerWindow(0, 24 * 60, 1));
    }

    @Test
    void theRangeKeepsOnlyTheDaysAskedForThatCarryATimeslot() {
        OpeningLayers couches = build(() -> stand("LIBRE"), List.of(), DIMANCHE, DIMANCHE.plusDays(3));

        assertThat(couches.jours()).extracting(LayerDay::date).containsExactly(DIMANCHE);
        assertThat(couches.stands().get(0).jours()).hasSize(1);
    }

    @Test
    void aNightTimeslotCarriesTheNextMorningWindowsPastMidnight() {
        Creneau nuit = new Creneau(9L, 1, SAMEDI, LocalTime.of(22, 0), LocalTime.of(2, 0));
        List<Creneau> grille = List.of(nuit);
        Supplier<Stand> fabrique = () -> stand("LIBRE");
        Stand nominal = fabrique.get();
        Stand effectif = fabrique.get();
        HoraireStandResolver.apply(List.of(nominal), grille);
        HoraireStandResolver.apply(List.of(effectif), grille);

        OpeningLayers couches = LayerCalendar.build(List.of(nominal), List.of(effectif), List.of(), grille, null, null);

        assertThat(couches.jours().get(0).vacations().get(0).finMinutes()).isEqualTo(26 * 60);
        // Open all day, then the next morning up to where the night ends: one stretch.
        assertThat(couches.stands().get(0).jours().get(0).nominal()).containsExactly(new LayerWindow(0, 26 * 60, 1));
    }

    /** Two copies of the same stand, one per resolution, as the service reads them. */
    private static OpeningLayers build(
            Supplier<Stand> fabrique, List<ConsigneEdition> consignes, LocalDate du, LocalDate au) {
        Stand nominal = fabrique.get();
        Stand effectif = fabrique.get();
        HoraireStandResolver.apply(List.of(nominal), GRILLE);
        HoraireStandResolver.apply(List.of(effectif), GRILLE);
        ConsigneResolver.apply(List.of(effectif), consignes, GRILLE);
        return LayerCalendar.build(List.of(nominal), List.of(effectif), consignes, GRILLE, du, au);
    }

    private static Creneau creneau(long id, LocalDate date, int debut, int fin) {
        return new Creneau(id, 1, date, LocalTime.of(debut, 0), LocalTime.of(fin, 0));
    }

    private static Stand stand(String id) {
        Stand stand = new Stand(id, id, Set.of(), 1, 4, false);
        stand.setIndisponibilites(new ArrayList<>());
        stand.setOuvertures(new ArrayList<>());
        stand.setHoraires(new ArrayList<>());
        return stand;
    }

    /** « BOURSE », open 14h-20h every day at 3, by rule 7. */
    private static Stand standWithRule() {
        Stand stand = stand("BOURSE");
        HoraireStand regle = HoraireStand.everyDay(
                ModeHoraire.OUVERTURE, new FenetreHoraire(LocalTime.of(14, 0), LocalTime.of(20, 0), 3));
        regle.setId(7L);
        stand.setHoraires(List.of(regle));
        return stand;
    }
}
