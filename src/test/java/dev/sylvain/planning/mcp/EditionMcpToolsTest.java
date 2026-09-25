package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.mcp.CreneauMcpTools.CreneauView;
import dev.sylvain.planning.mcp.EditionMcpTools.EditionView;
import dev.sylvain.planning.service.edition.EditionService;
import dev.sylvain.planning.service.edition.EtatEditionView;
import dev.sylvain.planning.service.edition.EtatEditionView.Statut;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkiverse.mcp.server.ToolManager;
import io.quarkiverse.mcp.server.ToolManager.ToolArgument;
import io.quarkiverse.mcp.server.ToolManager.ToolInfo;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Issue #181: the edition an MCP call works in must be nameable, and naming it
 * must actually move the call.
 *
 * <p>The isolation assertion is the one that matters — writing with
 * {@code edition} set must land in that edition <b>and nowhere else</b>. It is
 * also what proves the mechanism is wired at all: the argument is read by
 * {@link EditionCibleeInterceptor}, so an argument accepted but ignored would
 * look exactly like the silent default-edition behaviour this issue closes.</p>
 */
@QuarkusTest
class EditionMcpToolsTest {

    private static final String NOM_TEST = "Édition de test MCP";
    private static final String NOM_COPIE = "Copie de test MCP";
    private static final LocalDate DATE_TEST = LocalDate.of(2027, 1, 4);

    @Inject
    EditionMcpTools editionTools;

    @Inject
    CreneauMcpTools creneauTools;

    @Inject
    EditionService editionService;

    @Inject
    ToolManager toolManager;

    @AfterEach
    void nettoyer() {
        // The ids are drawn by the application (ADR 0050): the test's
        // editions are found back by their names.
        editionService.listEditions().stream()
                .filter(edition -> NOM_TEST.equals(edition.getNom()) || NOM_COPIE.equals(edition.getNom()))
                .forEach(edition -> editionService.delete(edition.getId()));
    }

    private String createTestEdition() {
        return editionTools.createEdition(NOM_TEST).id();
    }

    @Test
    void writingInTheDesignatedEditionLeavesTheOthersAlone() {
        String editionTest = createTestEdition();

        CreneauView cree = creneauTools
                .createCreneau("2027-01-04", "09:00", "12:00", null, editionTest)
                .creneau();

        assertThat(creneauTools.listCreneaux(editionTest))
                .extracting(CreneauView::id)
                .contains(cree.id());
        assertThat(creneauTools.listCreneaux(null))
                .as("l'édition courante ne doit rien avoir reçu")
                .extracting(CreneauView::date)
                .doesNotContain(DATE_TEST);
    }

    @Test
    void anUnknownEditionFailsInsteadOfFallingBackOnTheCurrentOne() {
        assertThatThrownBy(() -> creneauTools.listCreneaux("edition-qui-nexiste-pas"))
                .isInstanceOf(ToolCallException.class)
                .hasMessageContaining("Édition inconnue")
                .hasMessageContaining("lister_editions");
    }

    @Test
    void anEditionCanBeDesignatedByItsName() {
        String editionTest = createTestEdition();
        creneauTools.createCreneau("2027-01-04", "09:00", "12:00", null, NOM_TEST);

        assertThat(creneauTools.listCreneaux(editionTest)).hasSize(1);
    }

    @Test
    void listingEditionsGivesWhatIsNeededToRecogniseEachOne() {
        String editionTest = createTestEdition();
        creneauTools.createCreneau("2027-01-04", "09:00", "12:00", null, editionTest);

        List<EditionView> editions = editionTools.listEditions();

        assertThat(editions).extracting(EditionView::id).contains(editionTest);
        EditionView test = editions.stream()
                .filter(edition -> edition.id().equals(editionTest))
                .findFirst()
                .orElseThrow();
        assertThat(test.nombreCreneaux()).isEqualTo(1);
        assertThat(test.premiereDate()).isEqualTo(DATE_TEST);
        assertThat(test.derniereDate()).isEqualTo(DATE_TEST);
        assertThat(test.courante()).isFalse();
        assertThat(test.defaut()).isFalse();

        assertThat(editions)
                .filteredOn(EditionView::courante)
                .as("exactement une édition est celle où travaillent les outils sans argument edition")
                .hasSize(1)
                .first()
                .isEqualTo(editionTools.currentEdition());
    }

    /**
     * The checklist reads the edition its argument designates — a fresh one
     * has every line to do, and a timeslot created there is counted there, not
     * in the current edition — and names nobody: the view is counts and dates.
     */
    @Test
    void etatEditionReadsTheDesignatedEditionAndNamesNobody() {
        String editionTest = createTestEdition();

        EtatEditionView vide = editionTools.editionState(editionTest);

        assertThat(vide.editionId()).isEqualTo(editionTest);
        assertThat(vide.editionNom()).isEqualTo(NOM_TEST);
        assertThat(vide.referentiels().statut()).isEqualTo(Statut.A_FAIRE);
        assertThat(vide.resolution().resolue()).isFalse();
        assertThat(vide.resolution().solveEnCours()).isFalse();
        assertThat(vide.publication().jamaisPublie()).isTrue();

        creneauTools.createCreneau("2027-01-04", "09:00", "12:00", null, editionTest);

        assertThat(editionTools.editionState(editionTest).referentiels().creneaux())
                .isEqualTo(1);
        assertThat(editionTools.editionState(null).editionId())
                .as("sans argument, l'édition courante")
                .isNotEqualTo(editionTest);
        for (var composant : EtatEditionView.class.getRecordComponents()) {
            assertThat(composant.getType().isRecord() || composant.getType() == String.class)
                    .as(
                            "la vue ne porte que des blocs de chiffres et l'identité de l'édition : %s",
                            composant.getName())
                    .isTrue();
        }
    }

    @Test
    void duplicatingAnEditionCopiesItsDataWithoutTouchingTheOriginal() {
        String editionTest = createTestEdition();
        creneauTools.createCreneau("2027-01-04", "09:00", "12:00", null, editionTest);

        EditionView copie = editionTools.duplicateEdition(editionTest, NOM_COPIE, null);
        String editionCopie = copie.id();

        assertThat(copie.nombreCreneaux()).isEqualTo(1);
        creneauTools.createCreneau("2027-01-05", "09:00", "12:00", null, editionCopie);
        assertThat(creneauTools.listCreneaux(editionTest))
                .as("la copie vit sa vie : l'originale ne bouge plus")
                .hasSize(1);
    }

    /**
     * The Java signature is only half the contract: what an assistant can
     * actually pass is the schema the extension publishes. This walks the
     * registered tools rather than the compiled methods, so a change in how
     * {@code @ToolArg} is processed shows up here.
     */
    @Test
    void largumentEditionEstPublieAuxClientsMcp() throws Exception {
        int verifies = 0;
        for (ToolInfo outil : toolManager) {
            Method methode = outil.method().orElse(null);
            if (methode == null
                    || Arrays.stream(methode.getParameters())
                            .noneMatch(parametre -> parametre.isAnnotationPresent(EditionArg.class))) {
                continue;
            }
            ToolArgument edition = outil.arguments().stream()
                    .filter(argument -> argument.name().equals("edition"))
                    .findFirst()
                    .orElse(null);
            assertThat(edition)
                    .as("l'outil %s doit publier son argument edition", outil.name())
                    .isNotNull();
            assertThat(edition.required())
                    .as("l'argument edition de %s doit être facultatif", outil.name())
                    .isFalse();
            assertThat(edition.description()).isEqualTo(EditionArg.DESCRIPTION);
            verifies++;
        }
        long attendus = OutilsMcp.all().stream()
                .filter(outil -> Arrays.stream(outil.getParameters())
                        .anyMatch(parametre -> parametre.isAnnotationPresent(EditionArg.class)))
                .count();
        assertThat(verifies)
                .as("tous les outils déclarant un argument edition doivent aussi le publier")
                .isEqualTo((int) attendus);
    }

    @Test
    void deletingTheCurrentEditionIsRefused() {
        String courante = editionTools.currentEdition().id();

        assertThatThrownBy(() -> editionTools.deleteEdition(courante)).isInstanceOf(ToolCallException.class);
    }
}
