package dev.sylvain.planning.service.consigne;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.ConsigneEdition;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.EmptyReferenceData;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer;
import dev.sylvain.planning.service.solve.PlanningService;
import dev.sylvain.planning.service.solve.ProblemBuilder;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The consigne of issue #4 played on the anonymised real-world grid
 * ({@code festival-hivernal.yaml}): an arrêté closing 12h-18h on two days,
 * one stand reopened 18h-22h in compensation.
 *
 * <p>What it proves, on the shape the organiser actually enters: the créneaux
 * of the nominal grid are the very same objects before and after (nothing is
 * destroyed), the days outside the consigne generate exactly the seats they
 * generated before, the midday relays of the closed days carry no seat, the
 * afternoon is shortened to its effective hours, one créneau per day is added
 * where the grid stopped short of the evening — 20h-22h on the 3rd, 21h-22h
 * on the 4th whose grid runs to 21h — and the problem is still solved to zero
 * hard: the compensation is staffable under the night, rest and weekly
 * rules.</p>
 *
 * <p>Tagged {@code scenario-lent}: run with {@code ./mvnw test -Pscenario-tests}.</p>
 */
@Tag("scenario-lent")
class ConsigneScenarioFestivalHivernalTest {

    private static final long SECONDS_LIMITE_SECURITE = 900L;

    private static final LocalDate MERCREDI = LocalDate.of(2027, 2, 3);
    private static final LocalDate JEUDI = LocalDate.of(2027, 2, 4);
    private static final String STAND_TEMOIN = "GRANDE-HALLE-OBSERVATOIRE-FABULEUX";

    @Test
    void aConsigneOnTwoDaysTouchesOnlyThoseDaysAndStaysFeasible() {
        PlanningService planningService = new PlanningService(
                420L,
                0L,
                ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT,
                new EmptyReferenceData(),
                new FeasibilityAnalyzer(),
                null,
                null,
                ConfigProvider.getConfig());
        PlanningEvenement nominal = planningService.buildExample("festival-hivernal.yaml");

        Map<Long, Creneau> creneauxParId = new LinkedHashMap<>();
        Map<String, Stand> standsParId = new LinkedHashMap<>();
        for (PosteAffectation poste : nominal.getPostes()) {
            creneauxParId.putIfAbsent(poste.getCreneau().getId(), poste.getCreneau());
            standsParId.putIfAbsent(poste.getStand().getId(), poste.getStand());
        }
        List<Creneau> grilleNominale = new ArrayList<>(creneauxParId.values());
        List<Stand> stands = new ArrayList<>(standsParId.values());
        Map<LocalDate, Long> siegesNominauxParDate = seatsByDate(nominal.getPostes());

        // The consignes, and the créneaux they add where the grid stops short.
        List<Creneau> grille = new ArrayList<>(grilleNominale);
        long prochainId =
                grilleNominale.stream().mapToLong(Creneau::getId).max().orElse(0) + 1;
        List<ConsigneEdition> consignes = new ArrayList<>();
        for (LocalDate date : List.of(MERCREDI, JEUDI)) {
            ConsigneEdition demandee = new ConsigneEdition(
                    date,
                    LocalTime.of(12, 0),
                    LocalTime.of(18, 0),
                    "Arrêté préfectoral canicule",
                    "Plan canicule",
                    List.of(new ConsigneEdition.Fenetre(LocalTime.of(18, 0), LocalTime.of(22, 0))),
                    List.of(new ConsigneEdition.Ouverture(
                            STAND_TEMOIN, LocalTime.of(18, 0), LocalTime.of(22, 0), null)),
                    List.of(),
                    null,
                    null,
                    null);
            List<Creneau> duJour = grilleNominale.stream()
                    .filter(c -> date.equals(c.getDate()))
                    .toList();
            ConsigneService.Plan plan = ConsigneService.planifier(demandee, duJour, duJour);
            assertThat(plan.aCreer()).as("one créneau added on " + date).hasSize(1);
            List<Long> ajoutes = new ArrayList<>();
            for (Creneau aCreer : plan.aCreer()) {
                Creneau ajoute = new Creneau(prochainId++, 0, date, aCreer.getHeureDebut(), aCreer.getHeureFin());
                grille.add(ajoute);
                ajoutes.add(ajoute.getId());
            }
            consignes.add(demandee.withCreneauxAjoutes(ajoutes));
        }
        Creneau.assignerJours(grille);
        Map<LocalDate, Creneau> ajoutesParDate = grille.stream()
                .filter(c -> !creneauxParId.containsKey(c.getId()))
                .collect(Collectors.toMap(Creneau::getDate, c -> c));
        assertThat(ajoutesParDate.get(MERCREDI).getHeureDebut()).isEqualTo(LocalTime.of(20, 0));
        assertThat(ajoutesParDate.get(JEUDI).getHeureDebut()).isEqualTo(LocalTime.of(21, 0));

        // The stands come resolved by the scenario reader; the consigne is the layer above.
        ConsigneResolver.apply(stands, consignes, grille);
        List<PosteAffectation> postes = ProblemBuilder.buildPostes(stands, grille);

        // Nothing destroyed: every nominal créneau is still the same object in the grid.
        assertThat(grille).containsAll(grilleNominale);
        // Nothing moved outside the two dates.
        Map<LocalDate, Long> seatsByDate = seatsByDate(postes);
        for (LocalDate date : siegesNominauxParDate.keySet()) {
            if (!date.equals(MERCREDI) && !date.equals(JEUDI)) {
                assertThat(seatsByDate).as(date.toString()).containsEntry(date, siegesNominauxParDate.get(date));
            }
        }
        assertThat(seatsByDate.get(MERCREDI)).isLessThan(siegesNominauxParDate.get(MERCREDI));
        assertThat(seatsByDate.get(JEUDI)).isLessThan(siegesNominauxParDate.get(JEUDI));
        // The midday relays carry no seat, the afternoon runs 18h-20h, the evening is the witness stand's.
        for (Creneau creneau : grilleNominale) {
            if (!MERCREDI.equals(creneau.getDate())) {
                continue;
            }
            List<PosteAffectation> duCreneau =
                    postes.stream().filter(p -> p.getCreneau() == creneau).toList();
            if (creneau.getHeureDebut().equals(LocalTime.of(12, 0))
                    || creneau.getHeureDebut().equals(LocalTime.of(13, 0))) {
                assertThat(duCreneau).as("relais " + creneau.getHeureDebut()).isEmpty();
            }
            if (creneau.getHeureDebut().equals(LocalTime.of(14, 0))) {
                assertThat(duCreneau)
                        .isNotEmpty()
                        .allMatch(p -> LocalTime.of(18, 0).equals(p.heureDebutEffectif()));
            }
        }
        List<PosteAffectation> soir = postes.stream()
                .filter(p -> p.getCreneau() == ajoutesParDate.get(MERCREDI))
                .toList();
        assertThat(soir)
                .isNotEmpty()
                .allMatch(p -> STAND_TEMOIN.equals(p.getStand().getId()));

        PlanningEvenement probleme = new PlanningEvenement(
                nominal.getDateDebutFestival(), nominal.getAnimateurs(), postes, nominal.getContraintesAdHoc());
        probleme.setParametresLegaux(nominal.getParametresLegaux());
        probleme.setParametresQualite(nominal.getParametresQualite());
        probleme.setFenetresRepas(nominal.getFenetresRepas());

        PlanningEvenement solved = planningService.solveUntilFeasible(probleme, SECONDS_LIMITE_SECURITE);

        assertThat(solved.getScore()).isNotNull();
        assertThat(solved.getScore().hardScore()).isZero();
        assertThat(solved.getPostes()).noneMatch(poste -> poste.getAnimateur() == null);
        // Whoever holds the evening is an adult: a minor may not work past 22h, nor rest fewer than 12h.
        Set<String> dateSoir = solved.getPostes().stream()
                .filter(p -> p.getCreneau().getDate().equals(MERCREDI)
                        && p.getCreneau().getHeureDebut().equals(LocalTime.of(20, 0)))
                .map(p -> p.getAnimateur().getId())
                .collect(Collectors.toSet());
        assertThat(dateSoir).isNotEmpty();
    }

    private static Map<LocalDate, Long> seatsByDate(List<PosteAffectation> postes) {
        return postes.stream()
                .collect(Collectors.groupingBy(p -> p.getCreneau().getDate(), TreeMap::new, Collectors.counting()));
    }
}
