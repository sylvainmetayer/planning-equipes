package dev.sylvain.planning.service.journee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.journee.ChangementsJournee.AnimateurLine;
import dev.sylvain.planning.service.journee.ChangementsJournee.ReferenceChangements;
import dev.sylvain.planning.service.journee.ChangementsJournee.SeatChangeType;
import dev.sylvain.planning.service.journee.ChangementsJournee.SeatLine;
import dev.sylvain.planning.service.publication.PublicationDiffService;
import dev.sylvain.planning.service.publication.PublicationDiffService.TypeChangement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The comparison behind the Changements rendering, on hand-built plans: seats
 * are matched on their natural key, the seats of one cell are interchangeable,
 * and the per-person lines are the publication's own sentences kept to the
 * day. Plain JUnit — the comparison is a pure function.
 */
class ChangementsJourneeServiceTest {

    private static final LocalDate SAMEDI = LocalDate.of(2026, 7, 11);
    private static final LocalDate DIMANCHE = LocalDate.of(2026, 7, 12);
    private static final Instant REFERENCE_LE = Instant.parse("2026-07-01T10:00:00Z");

    private static final Animateur CAMILLE =
            new Animateur("camille", "Camille", "Durand", LocalDate.of(1990, 1, 1), false);
    private static final Animateur DOMINIQUE =
            new Animateur("dominique", "Dominique", "Petit", LocalDate.of(1991, 2, 2), false);
    private static final Animateur SASHA = new Animateur("sasha", "Sasha", "Roy", LocalDate.of(1992, 3, 3), false);

    private static final Stand CIRQUE = new Stand("cirque", "Cirque", Set.of(), 1, 2, false);
    private static final Stand NINJA = new Stand("ninja", "Ninja", Set.of(), 1, 2, false);

    private static final Creneau SAMEDI_APREM = new Creneau(1L, 1, SAMEDI, LocalTime.of(14, 0), LocalTime.of(18, 0));
    private static final Creneau SAMEDI_MATIN = new Creneau(2L, 1, SAMEDI, LocalTime.of(10, 0), LocalTime.of(12, 0));
    private static final Creneau DIMANCHE_APREM =
            new Creneau(3L, 2, DIMANCHE, LocalTime.of(14, 0), LocalTime.of(18, 0));

    private final PublicationDiffService diff = new PublicationDiffService();

    /** A plan built seat by seat; poste ids are deliberately different between the two sides. */
    private static final class Plan {
        private final List<PosteAffectation> postes = new ArrayList<>();
        private final String prefix;

        Plan(String prefix) {
            this.prefix = prefix;
        }

        Plan seat(Stand stand, Creneau creneau, Animateur animateur) {
            PosteAffectation poste = new PosteAffectation(prefix + postes.size(), stand, creneau);
            poste.setAnimateur(animateur);
            postes.add(poste);
            return this;
        }

        PlanningEvenement build() {
            return new PlanningEvenement(SAMEDI, List.of(CAMILLE, DOMINIQUE, SASHA), List.copyOf(postes));
        }
    }

    private ChangementsJournee compare(LocalDate jour, Plan avant, Plan apres) {
        return ChangementsJourneeService.compare(
                jour, ReferenceChangements.PUBLICATION, REFERENCE_LE, avant.build(), apres.build(), diff);
    }

    @Test
    void anUnchangedDayHasNoLineAndCarriesItsReference() {
        Plan avant = new Plan("a").seat(CIRQUE, SAMEDI_APREM, CAMILLE);
        Plan apres = new Plan("b").seat(CIRQUE, SAMEDI_APREM, CAMILLE);

        ChangementsJournee changements = compare(SAMEDI, avant, apres);

        assertThat(changements.referenceDisponible()).isTrue();
        assertThat(changements.referenceLe()).isEqualTo(REFERENCE_LE);
        assertThat(changements.reference()).isEqualTo(ReferenceChangements.PUBLICATION);
        assertThat(changements.parVacation()).isEmpty();
        assertThat(changements.parAnimateur()).isEmpty();
        assertThat(changements.nouveaux() + changements.retires() + changements.remplaces())
                .isZero();
    }

    @Test
    void aReplacedHolderReadsAsOneReplacementOnTheSeatAndTwoPeopleConcerned() {
        Plan avant = new Plan("a").seat(CIRQUE, SAMEDI_APREM, CAMILLE);
        Plan apres = new Plan("b").seat(CIRQUE, SAMEDI_APREM, DOMINIQUE);

        ChangementsJournee changements = compare(SAMEDI, avant, apres);

        assertThat(changements.remplaces()).isEqualTo(1);
        assertThat(changements.parVacation()).singleElement().satisfies(ligne -> {
            assertThat(ligne.type()).isEqualTo(SeatChangeType.REMPLACE);
            assertThat(ligne.standNom()).isEqualTo("Cirque");
            assertThat(ligne.heureDebut()).isEqualTo(LocalTime.of(14, 0));
            assertThat(ligne.avant().nomAffiche()).isEqualTo("Camille Durand");
            assertThat(ligne.apres().animateurId()).isEqualTo("dominique");
        });
        assertThat(changements.animateursConcernes()).isEqualTo(2);
        assertThat(changements.parAnimateur())
                .extracting(AnimateurLine::animateurId)
                .containsExactly("camille", "dominique");
        assertThat(changements.parAnimateur().get(0).changements())
                .extracting(change -> change.type(), change -> change.libelle())
                .containsExactly(tuple(TypeChangement.RETRAIT, "samedi 11/07 : Cirque 14h-18h (retiré)"));
        assertThat(changements.parAnimateur().get(1).changements())
                .extracting(change -> change.libelle())
                .containsExactly("samedi 11/07 : Cirque 14h-18h (nouveau)");
    }

    @Test
    void anEmptiedSeatIsARetraitAndAFilledOneANouveau() {
        Plan avant = new Plan("a").seat(CIRQUE, SAMEDI_APREM, CAMILLE).seat(NINJA, SAMEDI_APREM, null);
        Plan apres = new Plan("b").seat(CIRQUE, SAMEDI_APREM, null).seat(NINJA, SAMEDI_APREM, DOMINIQUE);

        ChangementsJournee changements = compare(SAMEDI, avant, apres);

        assertThat(changements.parVacation())
                .extracting(SeatLine::standNom, SeatLine::type)
                .containsExactly(tuple("Cirque", SeatChangeType.RETIRE), tuple("Ninja", SeatChangeType.NOUVEAU));
        assertThat(changements.retires()).isEqualTo(1);
        assertThat(changements.nouveaux()).isEqualTo(1);
    }

    /** Two seats of one cell swapping holders is nothing anybody has to be told. */
    @Test
    void theSeatsOfOneCellAreInterchangeable() {
        Plan avant = new Plan("a").seat(CIRQUE, SAMEDI_APREM, CAMILLE).seat(CIRQUE, SAMEDI_APREM, DOMINIQUE);
        Plan apres = new Plan("b").seat(CIRQUE, SAMEDI_APREM, DOMINIQUE).seat(CIRQUE, SAMEDI_APREM, CAMILLE);

        assertThat(compare(SAMEDI, avant, apres).parVacation()).isEmpty();
    }

    /** Only the day asked for: Sunday's change never shows on Saturday's tab. */
    @Test
    void anotherDaysChangeStaysOutOfTheDay() {
        Plan avant = new Plan("a").seat(CIRQUE, SAMEDI_APREM, CAMILLE).seat(CIRQUE, DIMANCHE_APREM, CAMILLE);
        Plan apres = new Plan("b").seat(CIRQUE, SAMEDI_APREM, CAMILLE).seat(CIRQUE, DIMANCHE_APREM, DOMINIQUE);

        ChangementsJournee samedi = compare(SAMEDI, avant, apres);
        ChangementsJournee dimanche = compare(DIMANCHE, avant, apres);

        assertThat(samedi.parVacation()).isEmpty();
        assertThat(samedi.parAnimateur()).isEmpty();
        assertThat(dimanche.remplaces()).isEqualTo(1);
        assertThat(dimanche.parAnimateur()).hasSize(2);
    }

    /** A move within the day pairs into one déplacement sentence, as the mail words it. */
    @Test
    void aMoveWithinTheDayIsWordedAsAReplacementForThePerson() {
        Plan avant = new Plan("a").seat(CIRQUE, SAMEDI_APREM, CAMILLE);
        Plan apres = new Plan("b").seat(NINJA, SAMEDI_APREM, CAMILLE);

        ChangementsJournee changements = compare(SAMEDI, avant, apres);

        assertThat(changements.parVacation())
                .extracting(SeatLine::standNom, SeatLine::type)
                .containsExactly(tuple("Cirque", SeatChangeType.RETIRE), tuple("Ninja", SeatChangeType.NOUVEAU));
        assertThat(changements.parAnimateur()).singleElement().satisfies(ligne -> {
            assertThat(ligne.nomAffiche()).isEqualTo("Camille Durand");
            assertThat(ligne.changements())
                    .extracting(change -> change.type(), change -> change.libelle())
                    .containsExactly(
                            tuple(TypeChangement.DEPLACEMENT, "samedi 11/07 : Ninja 14h-18h remplace Cirque 14h-18h"));
        });
    }

    /** Lines read as the day does: by hour, then by stand. */
    @Test
    void seatLinesAreOrderedByHourThenStand() {
        Plan avant = new Plan("a");
        Plan apres = new Plan("b")
                .seat(NINJA, SAMEDI_APREM, CAMILLE)
                .seat(CIRQUE, SAMEDI_APREM, DOMINIQUE)
                .seat(NINJA, SAMEDI_MATIN, SASHA);

        assertThat(compare(SAMEDI, avant, apres).parVacation())
                .extracting(SeatLine::heureDebut, SeatLine::standNom)
                .containsExactly(
                        tuple(LocalTime.of(10, 0), "Ninja"),
                        tuple(LocalTime.of(14, 0), "Cirque"),
                        tuple(LocalTime.of(14, 0), "Ninja"));
    }

    @Test
    void aMissingReferenceIsSaidRatherThanCountedAsZero() {
        ChangementsJournee changements = ChangementsJournee.withoutReference(SAMEDI, ReferenceChangements.RESOLUTION);

        assertThat(changements.referenceDisponible()).isFalse();
        assertThat(changements.referenceLe()).isNull();
        assertThat(changements.reference()).isEqualTo(ReferenceChangements.RESOLUTION);
    }

    @Test
    void theQueryParamNamesOneOfTheTwoReferencesOrNothing() {
        assertThat(ReferenceChangements.fromParam(null)).isNull();
        assertThat(ReferenceChangements.fromParam("publication")).isEqualTo(ReferenceChangements.PUBLICATION);
        assertThat(ReferenceChangements.fromParam("Resolution")).isEqualTo(ReferenceChangements.RESOLUTION);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ReferenceChangements.fromParam("hier"))
                .isInstanceOf(dev.sylvain.planning.service.BusinessError.Invalid.class);
    }
}
