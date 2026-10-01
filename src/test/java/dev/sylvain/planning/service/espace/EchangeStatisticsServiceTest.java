package dev.sylvain.planning.service.espace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.domain.DemandeEchange;
import dev.sylvain.planning.domain.StatutDemandeEchange;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.espace.EchangeStatistics.EchangeConstraintCount;
import dev.sylvain.planning.service.espace.EchangeStatistics.EchangeDayCount;
import dev.sylvain.planning.service.espace.EchangeStatistics.EchangeDelay;
import dev.sylvain.planning.service.espace.EchangeStatistics.EchangeRate;
import dev.sylvain.planning.service.espace.EchangeStatistics.EchangeStandCount;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The statistics of the foire au planning, computed without a database on a
 * fixture whose every status and timestamp is known: each figure is the value
 * worked out by hand in the comments.
 */
class EchangeStatisticsServiceTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final LocalDate DU = LocalDate.of(2026, 6, 1);
    private static final LocalDate AU = LocalDate.of(2026, 6, 30);
    /** 10:00 in Paris. */
    private static final Instant T0 = Instant.parse("2026-06-10T08:00:00Z");

    private static final long CRENEAU_SAMEDI = 1L;
    private static final long CRENEAU_DIMANCHE = 2L;
    private static final long CRENEAU_RETIRE = 99L;
    private static final Map<Long, LocalDate> CRENEAUX = Map.of(
            CRENEAU_SAMEDI, LocalDate.of(2026, 7, 11),
            CRENEAU_DIMANCHE, LocalDate.of(2026, 7, 12));
    /** Stand C was deleted since: it has no name any more. */
    private static final Map<String, String> STANDS = Map.of("A", "Stand A", "B", "Stand B");

    private static final String REPOS = "Repos quotidien insuffisant";
    private static final String NUIT = "Mineur la nuit";

    @Test
    void everyFigureIsTheExpectedValue() {
        EchangeStatistics stats = compute(fixture(), DU, AU);

        // d9 was created at 23:30 in Paris on 31 May: outside. d8, at 00:30
        // on 1 June in Paris but still 31 May in UTC: inside.
        assertThat(stats.creees()).isEqualTo(8);
        assertThat(stats.dirigees()).isEqualTo(1);
        assertThat(stats.enAttenteCible()).isEqualTo(2);
        assertThat(stats.enAttenteOrganisation()).isEqualTo(1);
        assertThat(stats.acceptees()).isEqualTo(2);
        assertThat(stats.refusees()).isEqualTo(1);
        assertThat(stats.refuseesCible()).isEqualTo(1);
        assertThat(stats.annulees()).isEqualTo(1);
        assertThat(stats.fuseau()).isEqualTo("Europe/Paris");

        // Agreed (PROPOSEE, ACCEPTEE ×2, REFUSEE) over answered (+ REFUSEE_CIBLE).
        assertThat(stats.accordCollegues()).isEqualTo(new EchangeRate(4, 5));
        assertThat(stats.acceptationOrganisation()).isEqualTo(new EchangeRate(2, 3));
        // Accepted over every finished request, the withdrawal included.
        assertThat(stats.aboutissement()).isEqualTo(new EchangeRate(2, 5));
        assertThat(stats.prevalidees()).isEqualTo(new EchangeRate(4, 8));
        // d2 refused, d7 accepted; d4 failed its prevalidation too but was withdrawn.
        assertThat(stats.acceptationNonPrevalidees()).isEqualTo(new EchangeRate(1, 2));

        // 1 h, 2 h, 3 h, 5 h — d5 has no cibleDecideLe.
        assertThat(stats.delaiReponseCollegue()).isEqualTo(new EchangeDelay(4, 1, 9_000L, 15_840L, 9_900L));
        // 2 h, 2 h, 6 h — d3, refused by the colleague, never enters it.
        assertThat(stats.delaiArbitrage()).isEqualTo(new EchangeDelay(3, 0, 7_200L, 18_720L, 12_000L));
        // 24 h and 1 h; d2, decided and not yet published, is pending rather than measured.
        assertThat(stats.delaiCommunication()).isEqualTo(new EchangeDelay(2, 0, 45_000L, 78_120L, 45_000L));
        assertThat(stats.delaiAnnulation()).isEqualTo(new EchangeDelay(1, 0, 14_400L, 14_400L, 14_400L));

        assertThat(stats.parJourCreation()).hasSize(13);
        assertThat(stats.parJourCreation().getFirst()).isEqualTo(new EchangeDayCount(LocalDate.of(2026, 6, 1), 1));
        assertThat(stats.parJourCreation())
                .contains(
                        new EchangeDayCount(LocalDate.of(2026, 6, 2), 0),
                        new EchangeDayCount(LocalDate.of(2026, 6, 10), 2),
                        new EchangeDayCount(LocalDate.of(2026, 6, 11), 2),
                        new EchangeDayCount(LocalDate.of(2026, 6, 12), 1));
        assertThat(stats.parJourCreation().getLast()).isEqualTo(new EchangeDayCount(LocalDate.of(2026, 6, 13), 2));

        assertThat(stats.parJourEvenement())
                .containsExactly(
                        new EchangeDayCount(LocalDate.of(2026, 7, 11), 3),
                        new EchangeDayCount(LocalDate.of(2026, 7, 12), 4));
        assertThat(stats.creneauRetire()).isEqualTo(1);

        assertThat(stats.parStand())
                .containsExactly(
                        new EchangeStandCount("A", "Stand A", 4),
                        new EchangeStandCount("B", "Stand B", 3),
                        new EchangeStandCount("C", null, 1));

        // d7 names the rest rule twice: one request, counted once.
        assertThat(stats.contraintesViolees())
                .containsExactly(new EchangeConstraintCount(REPOS, 3), new EchangeConstraintCount(NUIT, 1));
    }

    @Test
    void aWithdrawalNeverEntersTheOrganisationAcceptanceRate() {
        DemandeEchange annulee = demande("x", StatutDemandeEchange.ANNULEE, T0);
        annulee.setAnnuleLe(T0.plusSeconds(60));
        // Even with a decideLe written by an older version of the column.
        annulee.setDecideLe(T0.plusSeconds(60));

        EchangeStatistics stats = compute(List.of(annulee), null, null);

        assertThat(stats.acceptationOrganisation()).isEqualTo(new EchangeRate(0, 0));
        assertThat(stats.aboutissement()).isEqualTo(new EchangeRate(0, 1));
        assertThat(stats.delaiArbitrage().mesurees()).isZero();
    }

    @Test
    void aRequestWithoutTheColleaguesTimestampIsCountedApartFromTheDelay() {
        DemandeEchange ancienne = demande("x", StatutDemandeEchange.ACCEPTEE, T0);
        ancienne.setDecideLe(T0.plus(Duration.ofHours(1)));

        EchangeStatistics stats = compute(List.of(ancienne), null, null);

        assertThat(stats.creees()).isEqualTo(1);
        assertThat(stats.delaiReponseCollegue()).isEqualTo(new EchangeDelay(0, 1, null, null, null));
        assertThat(stats.delaiArbitrage()).isEqualTo(new EchangeDelay(0, 1, null, null, null));
    }

    /**
     * The colleague's agreement is read off the colleague's answer, not the
     * statut: three requests the organisation refused before the colleague
     * said anything were never answered, and a request agreed then withdrawn
     * was — two agreed out of four answered, not four out of six.
     */
    @Test
    void theColleagueAgreementCountsOnlyTheRequestsTheColleagueAnswered() {
        List<DemandeEchange> demandes = new ArrayList<>();
        for (String id : List.of("r1", "r2", "r3")) {
            demandes.add(refusedBeforeAnyAnswer(id));
        }
        for (String id : List.of("c1", "c2")) {
            DemandeEchange refuseeCible = demande(id, StatutDemandeEchange.REFUSEE_CIBLE, T0);
            refuseeCible.setCibleDecideLe(T0.plus(Duration.ofHours(1)));
            demandes.add(refuseeCible);
        }
        DemandeEchange retiree = demande("w", StatutDemandeEchange.ANNULEE, T0);
        retiree.setCibleDecideLe(T0.plus(Duration.ofHours(1)));
        retiree.setAnnuleLe(T0.plus(Duration.ofHours(2)));
        demandes.add(retiree);
        DemandeEchange acceptee = demande("a", StatutDemandeEchange.ACCEPTEE, T0);
        acceptee.setCibleDecideLe(T0.plus(Duration.ofHours(1)));
        acceptee.setDecideLe(T0.plus(Duration.ofHours(3)));
        demandes.add(acceptee);

        EchangeStatistics stats = compute(demandes, null, null);

        assertThat(stats.accordCollegues()).isEqualTo(new EchangeRate(2, 4));
        assertThat(select(demandes, EchangeStatisticsService.Measure.ANSWERED_BY_COLLEAGUE))
                .containsExactlyInAnyOrder("c1", "c2", "w", "a");
        assertThat(select(demandes, EchangeStatisticsService.Measure.AGREED_BY_COLLEAGUE))
                .containsExactlyInAnyOrder("w", "a");
        // The organisation's word still counts as its decision.
        assertThat(stats.acceptationOrganisation()).isEqualTo(new EchangeRate(1, 4));
    }

    /**
     * A refusal by the organisation before the colleague answered is missing no
     * timestamp: the colleague never answered, and the arbitration delay starts
     * from an answer it does not have — it enters neither delay, not even as
     * « sans horodatage ».
     */
    @Test
    void anAdminRefusalBeforeAnyAnswerIsLeftOutOfBothDelays() {
        EchangeStatistics stats = compute(List.of(refusedBeforeAnyAnswer("r")), null, null);

        assertThat(stats.delaiReponseCollegue()).isEqualTo(new EchangeDelay(0, 0, null, null, null));
        assertThat(stats.delaiArbitrage()).isEqualTo(new EchangeDelay(0, 0, null, null, null));
        assertThat(stats.accordCollegues()).isEqualTo(new EchangeRate(0, 0));
    }

    /**
     * A request older than the colleague's step went straight to the
     * organisation's queue, where it was read as agreed: it keeps counting so,
     * and is the « sans horodatage » of the reply delay.
     */
    @Test
    void aRequestOlderThanTheColleaguesStepCountsAsAgreed() {
        DemandeEchange ancienne = demande("x", StatutDemandeEchange.PROPOSEE, T0);

        EchangeStatistics stats = compute(List.of(ancienne), null, null);

        assertThat(stats.accordCollegues()).isEqualTo(new EchangeRate(1, 1));
        assertThat(stats.delaiReponseCollegue()).isEqualTo(new EchangeDelay(0, 1, null, null, null));
    }

    /**
     * Every figure opens the list of exactly the requests it counted: the
     * « sur N demande(s) » of a delay is N requests, never the whole statut.
     */
    @Test
    void eachMeasureSelectsExactlyTheRequestsItsFigureCounts() {
        List<DemandeEchange> fixture = fixture();
        EchangeStatistics stats = compute(fixture, DU, AU);

        assertThat(selectIn(fixture, EchangeStatisticsService.Measure.ANSWERED_BY_COLLEAGUE))
                .hasSize(stats.accordCollegues().denominateur());
        assertThat(selectIn(fixture, EchangeStatisticsService.Measure.AGREED_BY_COLLEAGUE))
                .hasSize(stats.accordCollegues().numerateur());
        // d5, older than the column, is answered but unmeasured.
        assertThat(selectIn(fixture, EchangeStatisticsService.Measure.REPLY_DELAY))
                .containsExactlyInAnyOrder("d1", "d2", "d3", "d7")
                .hasSize(stats.delaiReponseCollegue().mesurees());
        assertThat(selectIn(fixture, EchangeStatisticsService.Measure.ARBITRATION_DELAY))
                .containsExactlyInAnyOrder("d1", "d2", "d7")
                .hasSize(stats.delaiArbitrage().mesurees());
        // d2, decided and not yet published, is not in it.
        assertThat(selectIn(fixture, EchangeStatisticsService.Measure.COMMUNICATION_DELAY))
                .containsExactlyInAnyOrder("d1", "d7")
                .hasSize(stats.delaiCommunication().mesurees());
        assertThat(selectIn(fixture, EchangeStatisticsService.Measure.CANCELLATION_DELAY))
                .containsExactly("d4")
                .hasSize(stats.delaiAnnulation().mesurees());
    }

    @Test
    void aMeasureIsReadFromItsQueryValue() {
        assertThat(EchangeStatisticsService.Measure.fromParam("delai-communication"))
                .isEqualTo(EchangeStatisticsService.Measure.COMMUNICATION_DELAY);
        assertThat(EchangeStatisticsService.Measure.fromParam(" ")).isNull();
        assertThatThrownBy(() -> EchangeStatisticsService.Measure.fromParam("tout"))
                .isInstanceOf(BusinessError.Invalid.class);
    }

    @Test
    void withoutBoundsEveryRequestCounts() {
        assertThat(compute(fixture(), null, null).creees()).isEqualTo(9);
    }

    @Test
    void noRequestGivesEmptyFiguresRatherThanZeroDelays() {
        EchangeStatistics stats = compute(List.of(), DU, AU);

        assertThat(stats.creees()).isZero();
        assertThat(stats.accordCollegues()).isEqualTo(new EchangeRate(0, 0));
        assertThat(stats.delaiArbitrage()).isEqualTo(new EchangeDelay(0, 0, null, null, null));
        assertThat(stats.parJourCreation()).isEmpty();
        assertThat(stats.parStand()).isEmpty();
        assertThat(stats.du()).isEqualTo(DU);
        assertThat(stats.au()).isEqualTo(AU);
    }

    @Test
    void theCreationDayIsCutInTheGivenZone() {
        DemandeEchange minuit = demande("x", StatutDemandeEchange.PROPOSEE, Instant.parse("2026-05-31T22:30:00Z"));

        assertThat(EchangeStatisticsService.createdIn(minuit, DU, DU, PARIS)).isTrue();
        assertThat(EchangeStatisticsService.createdIn(minuit, DU, DU, ZoneId.of("UTC")))
                .isFalse();
    }

    @Test
    void thePercentileInterpolatesBetweenRanks() {
        assertThat(EchangeStatisticsService.percentile(new long[] {1, 2, 3, 4}, 0.5))
                .isEqualTo(3L); // 2.5, rounded half up
        assertThat(EchangeStatisticsService.percentile(new long[] {10, 20, 30}, 0.5))
                .isEqualTo(20L);
        assertThat(EchangeStatisticsService.percentile(new long[] {7}, 0.9)).isEqualTo(7L);
    }

    private static List<String> select(List<DemandeEchange> demandes, EchangeStatisticsService.Measure measure) {
        return EchangeStatisticsService.select(demandes, null, null, PARIS, measure).stream()
                .map(DemandeEchange::getId)
                .toList();
    }

    private static List<String> selectIn(List<DemandeEchange> demandes, EchangeStatisticsService.Measure measure) {
        return EchangeStatisticsService.select(demandes, DU, AU, PARIS, measure).stream()
                .map(DemandeEchange::getId)
                .toList();
    }

    /** What the organisation's refusal leaves on a request still waiting for the colleague. */
    private static DemandeEchange refusedBeforeAnyAnswer(String id) {
        DemandeEchange demande = demande(id, StatutDemandeEchange.REFUSEE, T0);
        demande.setDecideLe(T0.plus(Duration.ofHours(1)));
        return demande;
    }

    private static EchangeStatistics compute(List<DemandeEchange> demandes, LocalDate du, LocalDate au) {
        return EchangeStatisticsService.compute(demandes, du, au, false, PARIS, CRENEAUX, STANDS);
    }

    private static List<DemandeEchange> fixture() {
        List<DemandeEchange> demandes = new ArrayList<>();

        DemandeEchange d1 = demande("d1", StatutDemandeEchange.ACCEPTEE, T0);
        d1.setCreneauCibleId(CRENEAU_DIMANCHE);
        d1.setStandCibleId("B");
        d1.setCibleDecideLe(T0.plus(Duration.ofHours(1)));
        d1.setDecideLe(T0.plus(Duration.ofHours(3)));
        d1.setCommuniqueeLe(T0.plus(Duration.ofHours(27)));
        d1.setPrevalidationOk(true);
        demandes.add(d1);

        Instant lendemain = T0.plus(Duration.ofDays(1));
        DemandeEchange d2 = demande("d2", StatutDemandeEchange.REFUSEE, lendemain);
        d2.setCibleDecideLe(lendemain.plus(Duration.ofHours(3)));
        d2.setDecideLe(lendemain.plus(Duration.ofHours(5)));
        d2.setPrevalidationOk(false);
        d2.setContraintesViolees(List.of(REPOS, NUIT));
        demandes.add(d2);

        DemandeEchange d3 = demande("d3", StatutDemandeEchange.REFUSEE_CIBLE, lendemain);
        d3.setCreneauId(CRENEAU_DIMANCHE);
        d3.setStandId("B");
        d3.setCibleDecideLe(lendemain.plus(Duration.ofHours(2)));
        // Never written by a colleague's refusal; set here to prove it is not read.
        d3.setDecideLe(lendemain.plus(Duration.ofDays(10)));
        d3.setPrevalidationOk(true);
        demandes.add(d3);

        Instant surlendemain = T0.plus(Duration.ofDays(2));
        DemandeEchange d4 = demande("d4", StatutDemandeEchange.ANNULEE, surlendemain);
        d4.setCreneauId(CRENEAU_RETIRE);
        d4.setStandId("B");
        d4.setAnnuleLe(surlendemain.plus(Duration.ofHours(4)));
        d4.setPrevalidationOk(false);
        d4.setContraintesViolees(List.of(REPOS));
        demandes.add(d4);

        Instant troisiemeJour = T0.plus(Duration.ofDays(3));
        DemandeEchange d5 = demande("d5", StatutDemandeEchange.PROPOSEE, troisiemeJour);
        d5.setCreneauId(CRENEAU_DIMANCHE);
        d5.setPrevalidationOk(true);
        demandes.add(d5);

        DemandeEchange d6 = demande("d6", StatutDemandeEchange.EN_ATTENTE_CIBLE, troisiemeJour);
        d6.setCreneauId(CRENEAU_DIMANCHE);
        d6.setStandId("C");
        d6.setPrevalidationOk(true);
        demandes.add(d6);

        DemandeEchange d7 = demande("d7", StatutDemandeEchange.ACCEPTEE, T0.plus(Duration.ofMinutes(30)));
        d7.setCibleDecideLe(d7.getCreeLe().plus(Duration.ofHours(5)));
        d7.setDecideLe(d7.getCreeLe().plus(Duration.ofHours(11)));
        d7.setCommuniqueeLe(d7.getCreeLe().plus(Duration.ofHours(12)));
        d7.setPrevalidationOk(false);
        d7.setContraintesViolees(List.of(REPOS, REPOS));
        demandes.add(d7);

        DemandeEchange d8 = demande("d8", StatutDemandeEchange.EN_ATTENTE_CIBLE, Instant.parse("2026-05-31T22:30:00Z"));
        d8.setCreneauId(CRENEAU_DIMANCHE);
        d8.setStandId("B");
        demandes.add(d8);

        DemandeEchange d9 = demande("d9", StatutDemandeEchange.ACCEPTEE, Instant.parse("2026-05-31T21:30:00Z"));
        demandes.add(d9);
        return demandes;
    }

    private static DemandeEchange demande(String id, StatutDemandeEchange statut, Instant creeLe) {
        DemandeEchange demande = new DemandeEchange();
        demande.setId(id);
        demande.setDemandeurId("a1");
        demande.setCibleId("a2");
        demande.setCreneauId(CRENEAU_SAMEDI);
        demande.setStandId("A");
        demande.setStatut(statut);
        demande.setCreeLe(creeLe);
        return demande;
    }
}
