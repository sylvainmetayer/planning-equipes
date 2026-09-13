package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link ContrainteAdHocContradictions#detectAll} reads its exceptions through
 * indexes instead of comparing every pair: it must still find exactly what the
 * naive reading finds, in the same order — the order is on the screen, and in
 * the refusal message of an import.
 */
class ContrainteAdHocContradictionsIndexTest {

    private static final LocalDate JOUR = LocalDate.of(2028, 7, 1);

    private final List<Creneau> creneaux = List.of(
            new Creneau(1L, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(13, 0)),
            new Creneau(2L, 1, JOUR, LocalTime.of(12, 0), LocalTime.of(15, 0)),
            new Creneau(3L, 1, JOUR, LocalTime.of(14, 0), LocalTime.of(18, 0)),
            new Creneau(4L, 2, JOUR.plusDays(1), LocalTime.of(22, 0), LocalTime.of(2, 0)));
    private final List<Stand> stands = List.of(
            new Stand("S1", "S1", Set.of("JEUX"), 1, 1, false), new Stand("S2", "S2", Set.of("JEUX"), 1, 1, false));

    /** A small population on purpose, so the random draws collide into every kind of contradiction. */
    private List<ContrainteAdHoc> draw(Random rng, int count) {
        List<Animateur> animateurs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            animateurs.add(new Animateur("A" + i, "P", "N", LocalDate.of(1990, 1, 1), false));
        }
        TypeContrainteAdHoc[] types = TypeContrainteAdHoc.values();
        List<ContrainteAdHoc> contraintes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            TypeContrainteAdHoc type = types[rng.nextInt(types.length)];
            ContrainteAdHoc contrainte = new ContrainteAdHoc("C" + i, type);
            int nombre = type == TypeContrainteAdHoc.INCOMPATIBILITE || type == TypeContrainteAdHoc.AFFINITE
                    ? 2
                    : 1 + (rng.nextInt(5) == 0 ? 1 : 0);
            List<Animateur> vises = new ArrayList<>(animateurs);
            java.util.Collections.shuffle(vises, rng);
            contrainte.setAnimateursConcernes(new ArrayList<>(vises.subList(0, nombre)));
            if (rng.nextInt(3) > 0) {
                contrainte.setCreneau(creneaux.get(rng.nextInt(creneaux.size())));
            }
            if (rng.nextInt(3) == 0) {
                contrainte.setStand(stands.get(rng.nextInt(stands.size())));
            }
            contraintes.add(contrainte);
        }
        return contraintes;
    }

    @Test
    void theIndexedReadingFindsWhatTheNaiveOneFindsInTheSameOrder() {
        int avecContradictions = 0;
        for (int graine = 0; graine < 300; graine++) {
            List<ContrainteAdHoc> contraintes = draw(new Random(graine), 5 + graine % 40);

            List<ContrainteAdHocContradictions.Contradiction> naif =
                    ContrainteAdHocContradictions.detectAllNaively(contraintes, creneaux);

            assertThat(ContrainteAdHocContradictions.detectAll(contraintes, creneaux))
                    .as("seed %d", graine)
                    .containsExactlyElementsOf(naif);
            avecContradictions += naif.isEmpty() ? 0 : 1;
        }
        // The draw must actually exercise the rules, or the equality proves nothing.
        assertThat(avecContradictions).isGreaterThan(200);
        assertThat(java.util.stream.IntStream.range(0, 300)
                        .mapToObj(graine -> ContrainteAdHocContradictions.detectAllNaively(
                                draw(new Random(graine), 5 + graine % 40), creneaux))
                        .flatMap(List::stream)
                        .map(ContrainteAdHocContradictions.Contradiction::type)
                        .distinct())
                .containsExactlyInAnyOrder(ContrainteAdHocContradictions.TypeContradiction.values());
    }

    /** Two thousand exceptions without a contradiction: 3.9 s with the naive reading. */
    @Test
    void twoThousandExceptionsAreCheckedInWellUnderASecond() {
        List<Animateur> animateurs = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            animateurs.add(new Animateur("A" + i, "P", "N", LocalDate.of(1990, 1, 1), false));
        }
        List<ContrainteAdHoc> contraintes = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            ContrainteAdHoc contrainte = new ContrainteAdHoc(
                    "C" + i, i < 200 ? TypeContrainteAdHoc.AFFECTATION_FORCEE : TypeContrainteAdHoc.INCOMPATIBILITE);
            contrainte.setAnimateursConcernes(
                    i < 200
                            ? List.of(animateurs.get(i))
                            : List.of(animateurs.get(200 + i % 50), animateurs.get(250 + (i / 50) % 50)));
            if (i < 200) {
                contrainte.setCreneau(creneaux.get(i % creneaux.size()));
            }
            contraintes.add(contrainte);
        }

        Instant debut = Instant.now();
        assertThat(ContrainteAdHocContradictions.detectAll(contraintes, creneaux))
                .isEmpty();
        assertThat(Duration.between(debut, Instant.now())).isLessThan(Duration.ofSeconds(1));
    }
}
