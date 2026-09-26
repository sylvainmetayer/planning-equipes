package dev.sylvain.planning.service.analyse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.SeatPlaces;
import dev.sylvain.planning.service.SplitSeatFixture;
import dev.sylvain.planning.service.analyse.EquiteService.LigneEquite;
import dev.sylvain.planning.service.analyse.MargeAnalyzer.CelluleMarge;
import dev.sylvain.planning.service.analyse.PlanningHoursService.HeuresAnimateur;
import dev.sylvain.planning.service.analyse.PlanningKpiService.PlanningKpi;
import dev.sylvain.planning.service.analyse.TypologieAnalyzer.LigneTypologie;
import dev.sylvain.planning.service.referentiel.TypologieItem;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * A seat split on the day (ADR 0066) is one place whose hours are both parts'
 * windows, and a narrowed seat is one place on its narrowed window: every
 * report of the plan read on {@link SplitSeatFixture} — three places, two
 * staffed, Ada 0 h 20, Bob 2 h 40, Cyd 3 h.
 */
class SplitSeatReportsTest {

    private static final double TWENTY_MINUTES = 20 / 60.0;
    private static final double TWO_HOURS_FORTY = 160 / 60.0;

    private final SplitSeatFixture fixture = new SplitSeatFixture();
    private final PlanningEvenement plan = fixture.plan();

    @Test
    void theKpiCountThePlaceOnceAndTheHoursOfBothParts() {
        PlanningKpi kpi = PlanningKpiService.compute(
                PlanningKpiService.affectationsOf(plan.getPostes()), null, Map.of(), null, null, null);

        assertThat(kpi.postesTotal()).isEqualTo(3);
        assertThat(kpi.postesPourvus()).isEqualTo(2);
        assertThat(kpi.animateursAffectes()).isEqualTo(3);
        assertThat(kpi.heuresTotal()).isCloseTo(6.0, within(1e-9));
        assertThat(kpi.heuresMin()).isCloseTo(TWENTY_MINUTES, within(1e-9));
        assertThat(kpi.couvertureParJour())
                .containsExactly(
                        Map.entry(SplitSeatFixture.SATURDAY.toString(), new PlanningKpiService.DayCoverage(3, 2)));
    }

    @Test
    void theEquityCountsEachPersonsSeatAndTheirOwnWindow() {
        Map<String, LigneEquite> lines = EquiteService.compute(plan, new ParametresLegaux(), Set.of()).lignes().stream()
                .collect(Collectors.toMap(LigneEquite::animateurId, Function.identity()));

        assertThat(lines.get("A-ADA").postes()).isEqualTo(1);
        assertThat(lines.get("A-ADA").heuresTotal()).isCloseTo(TWENTY_MINUTES, within(1e-9));
        assertThat(lines.get("A-BOB").postes()).isEqualTo(1);
        assertThat(lines.get("A-BOB").heuresTotal()).isCloseTo(TWO_HOURS_FORTY, within(1e-9));
        assertThat(lines.get("A-CYD").heuresTotal()).isCloseTo(3.0, within(1e-9));
    }

    @Test
    void theRestOfASeatItsHolderKeptIsNotASeatMoreForThem() {
        LigneEquite ada =
                EquiteService.compute(fixture.planKeptByItsHolder(), new ParametresLegaux(), Set.of()).lignes().stream()
                        .filter(ligne -> ligne.animateurId().equals("A-ADA"))
                        .findFirst()
                        .orElseThrow();

        assertThat(ada.postes()).isEqualTo(1);
        assertThat(ada.heuresTotal()).isCloseTo(3.0, within(1e-9));
    }

    @Test
    void theHoursReportSumsEachPartOnItsWindow() {
        Map<String, Double> totals = new PlanningHoursService()
                .compute(plan).animateurs().stream()
                        .collect(Collectors.toMap(HeuresAnimateur::animateurId, HeuresAnimateur::total));

        assertThat(totals.get("A-ADA")).isCloseTo(TWENTY_MINUTES, within(1e-9));
        assertThat(totals.get("A-BOB")).isCloseTo(TWO_HOURS_FORTY, within(1e-9));
        assertThat(totals.get("A-CYD")).isCloseTo(3.0, within(1e-9));
    }

    @Test
    void theGameCategoriesCountOneSeatHeldPerPlaceAndEveryHour() {
        Map<String, LigneTypologie> lines = TypologieAnalyzer.compute(
                        plan,
                        List.of(
                                new TypologieItem("STRATEGIE", null, "Stratégie", false, null, null, null),
                                new TypologieItem("AMBIANCE", null, "Ambiance", false, null, null, null)),
                        List.of())
                .typologies()
                .stream()
                .collect(Collectors.toMap(LigneTypologie::typologie, Function.identity()));

        assertThat(lines.get("STRATEGIE").postes()).isEqualTo(2);
        assertThat(lines.get("STRATEGIE").heures()).isCloseTo(6.0, within(1e-9));
        assertThat(lines.get("STRATEGIE").animateursAffectes()).hasSize(3);
        assertThat(lines.get("AMBIANCE").postes()).isZero();
    }

    @Test
    void theMarginAfterTheSolveCountsPlacesNotRows() {
        CelluleMarge cell = new MargeAnalyzer()
                .analyze(
                        MargeAnalyzer.Mode.APRES,
                        plan.getPostes(),
                        plan.getAnimateurs(),
                        new ParametresLegaux(),
                        List.of())
                .jours()
                .getFirst()
                .cellules()
                .getFirst();

        assertThat(cell.sieges()).isEqualTo(3);
        assertThat(cell.siegesPourvus()).isEqualTo(2);
        assertThat(cell.besoin()).isEqualTo(1);
    }

    /**
     * The diagnostic's unassigned count, the day's « sièges empty » and the
     * places to fill of Aujourd'hui count empty rows: an empty row is always
     * the current part of its place — a seat is split only while somebody
     * holds it —, so they count places without being told.
     */
    @Test
    void anEmptyRowIsAlwaysAPlace() {
        List<PosteAffectation> empty = plan.getPostes().stream()
                .filter(poste -> poste.getAnimateur() == null)
                .toList();

        assertThat(empty).containsExactly(fixture.narrowed);
        assertThat(SeatPlaces.places(plan.getPostes())).containsAll(empty);
        assertThat(fixture.narrowed.getDureeEffectiveMinutes()).isEqualTo(160);
    }
}
