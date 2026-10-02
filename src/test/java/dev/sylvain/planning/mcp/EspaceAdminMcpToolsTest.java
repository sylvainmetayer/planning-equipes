package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.domain.DemandeEchange;
import dev.sylvain.planning.domain.StatutDemandeEchange;
import dev.sylvain.planning.mcp.DisponibiliteMcpTools.CollecteView;
import dev.sylvain.planning.mcp.DisponibiliteMcpTools.DeclarationMcpView;
import dev.sylvain.planning.mcp.EchangeMcpTools.DemandeView;
import dev.sylvain.planning.mcp.EchangeMcpTools.ViolationHardView;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.espace.DeclarationDisponibiliteRepository.FenetreCollecte;
import dev.sylvain.planning.service.espace.DeclarationDisponibiliteService.InvitationReport;
import dev.sylvain.planning.service.espace.EchangeStatistics;
import dev.sylvain.planning.service.espace.EchangeStatistics.EchangeConstraintCount;
import dev.sylvain.planning.service.espace.EchangeStatisticsService;
import dev.sylvain.planning.service.espace.EspaceAnimateurService.DeclarationAdminView;
import dev.sylvain.planning.service.espace.EspaceAnimateurService.DemandeEchangeView;
import dev.sylvain.planning.service.solve.PlanningWhatIf.HardViolation;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What the admin side of the espace animateur looks like once it has passed
 * through MCP: the same declarations and swap requests, minus everybody's
 * name.
 *
 * <p>Both REST views are built for a screen listing people, so each of them
 * carries a {@code *Nom} next to every id — the exact shape the privacy rule
 * of issue #107 forbids over MCP.</p>
 */
class EspaceAdminMcpToolsTest {

    private static final Instant CREE_LE = Instant.parse("2026-05-02T09:30:00Z");

    /** The deleted-animateur net alone, the case these lines exercise. */
    private static final AnonymisationViolations SANS_REFERENTIEL =
            AnonymisationViolations.of(List.of(), List.of(), List.of());

    @Test
    void aDeclarationLeavesWithoutItsAuthorsNameNorItsComment() {
        DeclarationMcpView vue = DisponibiliteMcpTools.toView(new DeclarationAdminView(
                "d1",
                "a1",
                "Camille Martin",
                "EN_ATTENTE",
                List.of(LocalDate.of(2026, 7, 11)),
                List.of("jeux-ambiance"),
                List.of("Jeux d'ambiance"),
                "je travaille le samedi",
                null,
                CREE_LE,
                null,
                List.of(LocalDate.of(2026, 7, 12)),
                List.of("Jeux experts")));

        assertThat(vue.animateurId()).isEqualTo("a1");
        assertThat(vue.statut()).isEqualTo("EN_ATTENTE");
        assertThat(vue.joursIndisponibles()).containsExactly(LocalDate.of(2026, 7, 11));
        assertThat(vue.joursActuels()).containsExactly(LocalDate.of(2026, 7, 12));
        assertThat(vue.souhaits()).containsExactly("jeux-ambiance");
        assertThat(vue.commentaireRenseigne()).isTrue();
        assertThat(vue.commentaireAdminRenseigne()).isFalse();
        assertThat(DeclarationMcpView.class.getRecordComponents())
                .extracting(composant -> composant.getName().toLowerCase())
                .doesNotContain("animateurnom", "nom");
    }

    /**
     * The invitation report names the people it could not reach, ready to be
     * shown to an admin who is looking at their fiches. Counted here: an
     * assistant acts on « deux animateurs sans adresse », and finds out which
     * ones by listing the référentiel by id.
     */
    @Test
    void theInvitationIsCountedNeverNamed() {
        CollecteView vue = DisponibiliteMcpTools.toView(
                new FenetreCollecte(true, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), true),
                new InvitationReport(12, List.of("Camille Martin", "Dominique Roy"), List.of("Alex Nguyen")));

        assertThat(vue.ouverte()).isTrue();
        assertThat(vue.relanceAutomatique()).isTrue();
        assertThat(vue.invitation().envoyes()).isEqualTo(12);
        assertThat(vue.invitation().sansAdresse()).isEqualTo(2);
        assertThat(vue.invitation().echecs()).isEqualTo(1);
    }

    @Test
    void uneCollecteSansInvitationNAPasDeRapport() {
        assertThat(DisponibiliteMcpTools.toView(FenetreCollecte.closed(), null).invitation())
                .isNull();
    }

    @Test
    void aSwapRequestLeavesByIdsAndItsViolationsAreAnonymised() {
        DemandeView vue = EchangeMcpTools.toView(
                new DemandeEchangeView(
                        "e1",
                        42L,
                        LocalDate.of(2026, 7, 11),
                        LocalTime.of(10, 0),
                        LocalTime.of(12, 0),
                        "stand-a",
                        "Stand A",
                        "a1",
                        "Camille Martin",
                        "a2",
                        "Dominique Roy",
                        43L,
                        LocalDate.of(2026, 7, 12),
                        LocalTime.of(14, 0),
                        LocalTime.of(16, 0),
                        "stand-b",
                        "Stand B",
                        "je dépose mes enfants",
                        "PROPOSEE",
                        false,
                        List.of("Camille Martin (a1) dépasse 8 h le 11/07"),
                        null,
                        CREE_LE,
                        CREE_LE,
                        null,
                        null),
                SANS_REFERENTIEL);

        assertThat(vue.demandeurId()).isEqualTo("a1");
        assertThat(vue.cibleId()).isEqualTo("a2");
        assertThat(vue.standId()).isEqualTo("stand-a");
        assertThat(vue.standCibleId()).isEqualTo("stand-b");
        assertThat(vue.contraintesViolees()).containsExactly("animateur a1 dépasse 8 h le 11/07");
        // « je dépose mes enfants »: that a reason exists travels, not the reason.
        assertThat(vue.motifRenseigne()).isTrue();
        assertThat(vue.commentaireAdminRenseigne()).isFalse();
        assertThat(DemandeView.class.getRecordComponents())
                .extracting(composant -> composant.getName().toLowerCase())
                .doesNotContain("demandeurnom", "ciblenom", "standnom", "standciblenom");
    }

    @Test
    void aHardViolationOfAnImpactIsAnonymisedToo() {
        ViolationHardView vue = EchangeMcpTools.toView(
                new HardViolation("reposQuotidienMinimal", "Camille Martin (a1) enchaîne deux vacations", 2),
                SANS_REFERENTIEL);

        assertThat(vue.contrainte()).isEqualTo("reposQuotidienMinimal");
        assertThat(vue.description()).isEqualTo("animateur a1 enchaîne deux vacations");
        assertThat(vue.matchesSupplementaires()).isEqualTo(2);
    }

    /**
     * The statistics leave as aggregates: no component names a person, and the
     * constraint lines are anonymised like any violation line.
     */
    @Test
    void theFoireStatisticsLeaveAsAnonymousAggregates() {
        EchangeStatistics statistiques = EchangeStatisticsService.compute(
                List.of(nonPrevalidated()),
                null,
                null,
                false,
                ZoneId.of("Europe/Paris"),
                Map.of(42L, LocalDate.of(2026, 7, 11)),
                Map.of("stand-a", "Stand A"));

        EchangeStatistics vue = EchangeMcpTools.anonymised(statistiques, SANS_REFERENTIEL);

        assertThat(vue.creees()).isEqualTo(1);
        assertThat(vue.contraintesViolees())
                .containsExactly(new EchangeConstraintCount("animateur a1 dépasse 8 h le 11/07", 1));
        assertThat(EchangeStatistics.class.getRecordComponents())
                .extracting(composant -> composant.getName().toLowerCase())
                .noneMatch(nom -> nom.contains("animateur") || nom.contains("demandeur") || nom.contains("cibleid"));
    }

    /** The whole edition is reachable over MCP too, even when the foire window is dated. */
    @Test
    void theFoireStatisticsCanAskForTheWholeEdition() {
        assertThat(EchangeMcpTools.statisticsPeriod(null, null, true))
                .isEqualTo(new EchangeMcpTools.StatisticsPeriod(null, null, true));
        assertThat(EchangeMcpTools.statisticsPeriod(null, null, null))
                .isEqualTo(new EchangeMcpTools.StatisticsPeriod(null, null, false));
        assertThat(EchangeMcpTools.statisticsPeriod("2026-06-01", null, false))
                .isEqualTo(new EchangeMcpTools.StatisticsPeriod(LocalDate.of(2026, 6, 1), null, false));
        assertThatThrownBy(() -> EchangeMcpTools.statisticsPeriod("2026-06-01", null, true))
                .isInstanceOf(BusinessError.Invalid.class);
        assertThatThrownBy(() -> EchangeMcpTools.statisticsPeriod("2026-06-02", "2026-06-01", null))
                .isInstanceOf(BusinessError.Invalid.class);
    }

    private static DemandeEchange nonPrevalidated() {
        DemandeEchange demande = new DemandeEchange();
        demande.setId("e1");
        demande.setDemandeurId("a1");
        demande.setCibleId("a2");
        demande.setCreneauId(42L);
        demande.setStandId("stand-a");
        demande.setStatut(StatutDemandeEchange.EN_ATTENTE_CIBLE);
        demande.setCreeLe(CREE_LE);
        demande.setPrevalidationOk(false);
        demande.setContraintesViolees(List.of("Camille Martin (a1) dépasse 8 h le 11/07"));
        return demande;
    }
}
