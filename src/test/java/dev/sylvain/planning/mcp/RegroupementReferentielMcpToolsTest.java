package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.mcp.CreneauMcpTools.GrilleView;
import dev.sylvain.planning.mcp.DisponibiliteMcpTools.DeclarationAppliqueeMcpView;
import dev.sylvain.planning.mcp.DisponibiliteMcpTools.DisponibilitesView;
import dev.sylvain.planning.mcp.StandMcpTools.StandView;
import dev.sylvain.planning.service.espace.DeclarationDisponibiliteRepository.FenetreCollecte;
import dev.sylvain.planning.service.espace.DeclarationDisponibiliteService;
import dev.sylvain.planning.service.espace.DeclarationDisponibiliteService.NouvelleDeclaration;
import dev.sylvain.planning.service.journal.JournalActionService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The tools 4.0 groups for the stands, the animateurs, the availabilities and the timeslots
 * (ADR 0127), each called once as it should be and once as it must be refused, on the shipped
 * scenario.
 */
@QuarkusTest
class RegroupementReferentielMcpToolsTest {

    @Inject
    ScenarioMcpTools scenarioTools;

    @Inject
    StandMcpTools standTools;

    @Inject
    AnimateurMcpTools animateurTools;

    @Inject
    DisponibiliteMcpTools disponibiliteTools;

    @Inject
    CreneauMcpTools creneauTools;

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    DeclarationDisponibiliteService declarationService;

    @Inject
    JournalActionService journal;

    @BeforeEach
    void loadScenario() {
        scenarioTools.resetData("E1");
        scenarioTools.importScenario("scenario.yml", "E1");
    }

    @AfterEach
    void clearEdition() {
        scenarioTools.resetData("E1");
    }

    private String firstStand() {
        return referenceDataService.listStands().getFirst().getId();
    }

    private String firstDay() {
        return referenceDataService.listCreneaux().getFirst().getDate().toString();
    }

    @Test
    void oneStandIsListedByItsIdAndALimitBesideItIsRefused() {
        String stand = firstStand();
        int total = referenceDataService.listStands().size();

        StandMcpTools.StandsView un = standTools.listStands(stand, null, "E1");
        assertThat(un.total()).isEqualTo(total);
        assertThat(un.stands()).extracting(StandView::id).containsExactly(stand);

        assertThatThrownBy(() -> standTools.listStands(stand, 2, "E1"))
                .isInstanceOf(ToolCallException.class)
                .hasMessageContaining("limite");
    }

    @Test
    void oneAnimateurIsListedByItsIdWithoutIdentityAndALimitBesideItIsRefused() {
        String animateur = referenceDataService.listAnimateurs().getFirst().getId();

        AnimateurMcpTools.AnimateursView un = animateurTools.listAnimateurs(animateur, null, "E1");
        assertThat(un.animateurs())
                .extracting(AnimateurMcpTools.AnimateurView::id)
                .containsExactly(animateur);
        assertThat(un.total()).isEqualTo(referenceDataService.listAnimateurs().size());

        assertThatThrownBy(() -> animateurTools.listAnimateurs(animateur, 1, "E1"))
                .isInstanceOf(ToolCallException.class)
                .hasMessageContaining("limite");
    }

    @Test
    void creatingAStandCreatesItsMissingCategoryOnlyWhenAsked() {
        StandMcpTools.CreationStandComplet cree = standTools.createStand(
                "Kapla",
                null,
                List.of("TYPO-MCP-NOUVELLE"),
                1,
                2,
                false,
                false,
                null,
                null,
                true,
                null,
                null,
                null,
                "10:00-12:00",
                null,
                null,
                null,
                null,
                null,
                "E1");
        assertThat(cree.typologiesCreees()).hasSize(1);
        assertThat(cree.stand().horaires()).hasSize(1);

        assertThatThrownBy(() -> standTools.createStand(
                        "Kapla 2",
                        null,
                        List.of("INCONNUE"),
                        1,
                        2,
                        false,
                        false,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "E1"))
                .isInstanceOf(ToolCallException.class);
        assertThat(referenceDataService.listStands())
                .extracting(dev.sylvain.planning.domain.Stand::getNom)
                .doesNotContain("Kapla 2");
    }

    @Test
    void anOpeningHourIsCreatedAsAPlageOrAsARuleAndAnArgumentOfTheOtherFormIsRefused() {
        String stand = firstStand();
        String jour = firstDay();

        StandView avecPlage = standTools
                .createStandHoraire(
                        stand,
                        "plage",
                        "FERMETURE",
                        jour,
                        "12:00",
                        "13:00",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "Repas",
                        "E1")
                .stand();
        assertThat(avecPlage.plages()).anySatisfy(plage -> {
            assertThat(plage.mode()).isEqualTo(ModeHoraire.FERMETURE);
            assertThat(plage.depliee()).isFalse();
        });
        assertThat(journal.list(1).getFirst().action()).isEqualTo("STAND_PLAGE_AJOUTEE");

        StandView avecRegle = standTools
                .createStandHoraire(
                        stand,
                        "modele",
                        "OUVERTURE",
                        null,
                        null,
                        null,
                        null,
                        "09:00-18:00",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "E1")
                .stand();
        assertThat(avecRegle.horaires()).isNotEmpty();
        assertThat(journal.list(1).getFirst().action()).isEqualTo("STAND_HORAIRE_AJOUTE");

        assertThatThrownBy(() -> standTools.createStandHoraire(
                        stand,
                        "plage",
                        "FERMETURE",
                        jour,
                        "14:00",
                        null,
                        null,
                        "14:00-15:00",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "E1"))
                .isInstanceOf(ToolCallException.class)
                .hasMessageContaining("fenetres ne vaut que pour la forme modele");
        assertThatThrownBy(() -> standTools.createStandHoraire(
                        stand,
                        null,
                        "FERMETURE",
                        jour,
                        "14:00",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "E1"))
                .isInstanceOf(ToolCallException.class)
                .hasMessageContaining("forme est requis");
    }

    @Test
    void anOpeningHourIsRemovedAsAPlageOrAsTheModelAndADateBesideTheModelIsRefused() {
        String stand = firstStand();
        String jour = firstDay();
        standTools.createStandHoraire(
                stand,
                "plage",
                "FERMETURE",
                jour,
                "12:00",
                "13:00",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "E1");
        standTools.createStandHoraire(
                stand,
                "modele",
                "OUVERTURE",
                null,
                null,
                null,
                null,
                "09:00-18:00",
                null,
                null,
                null,
                null,
                null,
                null,
                "E1");

        assertThatThrownBy(() -> standTools.deleteStandHoraire(stand, "modele", null, jour, "E1"))
                .isInstanceOf(ToolCallException.class)
                .hasMessageContaining("date ne vaut que pour la forme plage");

        StandView sansModele =
                standTools.deleteStandHoraire(stand, "modele", null, null, "E1").stand();
        assertThat(sansModele.horaires()).isEmpty();
        assertThat(sansModele.plages()).noneMatch(StandMcpTools.PlageView::depliee);
        assertThat(journal.list(1).getFirst().action()).isEqualTo("STAND_HORAIRES_EFFACES");

        StandView sansPlage =
                standTools.deleteStandHoraire(stand, "plage", null, jour, "E1").stand();
        assertThat(sansPlage.plages())
                .noneMatch(plage -> plage.date().toString().equals(jour));
    }

    @Test
    void theAvailabilityScreenIsReadSectionBySectionAndAStatusOutsideTheDeclarationsIsRefused() {
        DisponibilitesView collecte = disponibiliteTools.consultAvailability(null, null, "E1");
        assertThat(collecte.section()).isEqualTo("collecte");
        assertThat(collecte.collecte()).isNotNull();
        assertThat(collecte.declarations()).isNull();

        submitADeclaration(1);
        DisponibilitesView declarations = disponibiliteTools.consultAvailability("declarations", "EN_ATTENTE", "E1");
        assertThat(declarations.declarations()).hasSize(1);
        assertThat(declarations.collecte()).isNull();

        assertThat(disponibiliteTools
                        .consultAvailability("covoiturage", null, "E1")
                        .covoiturage())
                .isNotNull();

        assertThatThrownBy(() -> disponibiliteTools.consultAvailability("collecte", "EN_ATTENTE", "E1"))
                .isInstanceOf(ToolCallException.class)
                .hasMessageContaining("statut ne vaut que pour la section declarations");
    }

    @Test
    void aDeclarationIsAppliedOrRefusedAndACommentBesideAnApplicationIsRefused() {
        String appliquee = submitADeclaration(2);
        assertThatThrownBy(
                        () -> disponibiliteTools.decideAvailabilityDeclaration(appliquee, "appliquer", "Merci", "E1"))
                .isInstanceOf(ToolCallException.class)
                .hasMessageContaining("commentaire ne vaut que pour la décision refuser");
        assertThatThrownBy(() -> disponibiliteTools.decideAvailabilityDeclaration(appliquee, null, null, "E1"))
                .isInstanceOf(ToolCallException.class)
                .hasMessageContaining("decision est requis");

        DeclarationAppliqueeMcpView applique =
                disponibiliteTools.decideAvailabilityDeclaration(appliquee, "appliquer", null, "E1");
        assertThat(applique.declaration().statut()).isEqualTo("APPLIQUEE");

        String refusee = submitADeclaration(3);
        DeclarationAppliqueeMcpView refuse =
                disponibiliteTools.decideAvailabilityDeclaration(refusee, "refuser", "Trop tard", "E1");
        assertThat(refuse.declaration().statut()).isEqualTo("REFUSEE");
        assertThat(refuse.declaration().commentaireAdminRenseigne()).isTrue();
        assertThat(refuse.avertissements()).isEmpty();
        assertThat(journal.list(1).getFirst().action()).isEqualTo("DECLARATION_REFUSEE");
    }

    /**
     * A pending declaration of the animateur {@code rang} places from the end of the list — one per
     * test, since the submissions of one animateur are rate-limited: one off day on the first day.
     */
    private String submitADeclaration(int rang) {
        declarationService.configure(new FenetreCollecte(true, null, null, false), false);
        List<dev.sylvain.planning.domain.Animateur> animateurs = referenceDataService.listAnimateurs();
        String animateur = animateurs.get(animateurs.size() - rang).getId();
        Creneau creneau = referenceDataService.listCreneaux().getFirst();
        return declarationService
                .submit(animateur, new NouvelleDeclaration(List.of(creneau.getDate()), List.of(), null))
                .getId();
    }

    @Test
    void theGridIsReadSectionBySectionAndAnUnknownSectionIsRefused() {
        GrilleView grille = creneauTools.diagnoseCreneaux(null, "E1");
        assertThat(grille.section()).isEqualTo("grille");
        assertThat(grille.grille()).isNotNull();
        assertThat(grille.controle()).isNull();

        GrilleView controle = creneauTools.diagnoseCreneaux("controle", "E1");
        assertThat(controle.controle()).isNotNull();
        assertThat(controle.grille()).isNull();

        assertThatThrownBy(() -> creneauTools.diagnoseCreneaux("validation", "E1"))
                .isInstanceOf(ToolCallException.class)
                .hasMessageContaining("grille, controle");
    }

    @Test
    void timeslotsAreDeletedByTheirIdsAndIdsBesideAFilterOrAnUnknownIdAreRefused() {
        List<Creneau> creneaux = referenceDataService.listCreneaux();
        Long premier = creneaux.getFirst().getId();

        assertThatThrownBy(() -> creneauTools.deleteCreneaux(List.of(premier), null, null, null, true, "E1"))
                .isInstanceOf(ToolCallException.class)
                .hasMessageContaining("pas les deux");
        assertThatThrownBy(() -> creneauTools.deleteCreneaux(List.of(premier, -1L), null, null, null, null, "E1"))
                .isInstanceOf(ToolCallException.class)
                .hasMessageContaining("-1");
        assertThat(referenceDataService.listCreneaux()).hasSize(creneaux.size());

        CreneauMcpTools.BulkDeleteResult supprime =
                creneauTools.deleteCreneaux(List.of(premier), null, null, null, null, "E1");
        assertThat(supprime.supprimes()).isEqualTo(1);
        assertThat(supprime.restants()).isEqualTo(creneaux.size() - 1);
    }
}
