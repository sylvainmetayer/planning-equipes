package dev.sylvain.planning.mcp;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.mcp.GenerateurCreneauxMcpTools.EtatView;
import dev.sylvain.planning.mcp.GenerateurCreneauxMcpTools.GenerationView;
import dev.sylvain.planning.service.BusinessError;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The generator's tools end to end (ADR 0126), each in an edition of its own:
 * a template defined and given dates, previewed and applied, recognised back
 * from the grid, withdrawn and deleted; a recurrence and the stands' source;
 * and the 3.x tools, which answer through the same generator.
 */
@QuarkusTest
class GenerateurCreneauxMcpToolsTest {

    private static final String JOUR = "09:00-12:00, 12:00-13:00 R, 14:00-18:00";

    @Inject
    GenerateurCreneauxMcpTools tools;

    @Inject
    JourneeTypeMcpTools legacy;

    @Inject
    CreneauMcpTools creneaux;

    @Test
    void aTemplateIsDefinedGivenDatesPreviewedAppliedThenRecognised() {
        String edition = createEdition("Générateur MCP gabarits");

        EtatView etat = tools.modifyGabarit("Jour normal", JOUR, "2031-05-01", "2031-05-02", null, edition);
        assertThat(etat.gabarits())
                .singleElement()
                .satisfies(gabarit -> assertThat(gabarit.vacations()).contains("12:00-13:00 R"));
        assertThat(etat.calendrier())
                .extracting(GenerateurCreneauxMcpTools.DateView::gabarit)
                .containsExactly("Jour normal", "Jour normal");
        assertThat(etat.datesEnEcart()).hasSize(2);

        // Dates only, on a template that exists: the vacations are kept.
        etat = tools.modifyGabarit("jour NORMAL", null, null, null, List.of("2031-05-03"), edition);
        assertThat(etat.calendrier()).hasSize(3);

        GenerationView apercu = tools.previewGeneration(
                "gabarits", null, null, null, null, null, null, null, null, null, null, edition);
        assertThat(apercu.source()).isEqualTo("gabarits");
        assertThat(apercu.application().crees()).isEqualTo(9);
        assertThat(apercu.controle()).isNotNull();
        assertThat(creneaux.listCreneaux(edition)).isEmpty();

        GenerationView ecrit =
                tools.applyGeneration("GABARITS", null, null, null, null, null, null, null, null, null, null, edition);
        assertThat(ecrit.nombreGeneres()).isEqualTo(9);
        assertThat(ecrit.creneaux())
                .allSatisfy(creneau -> assertThat(creneau.id()).isNotNull());
        assertThat(creneaux.listCreneaux(edition)).hasSize(9);
        assertThat(tools.listGabarits(edition).datesEnEcart()).isEmpty();

        String vacations = etat.gabarits().get(0).vacations();
        GenerationView reconnu =
                tools.previewGeneration("grille", null, null, null, null, null, null, null, null, null, null, edition);
        assertThat(reconnu.reconnaissance().gabarits())
                .singleElement()
                .satisfies(gabarit -> assertThat(gabarit.vacations()).isEqualTo(vacations));
        assertThat(reconnu.reconnaissance().calendrier()).hasSize(3);
        GenerationView remplace =
                tools.applyGeneration(" grille ", null, null, null, null, null, null, null, null, null, null, edition);
        assertThat(remplace.source()).isEqualTo("grille");
        assertThat(tools.listGabarits(edition).gabarits()).singleElement();

        // Withdrawing dates keeps their timeslots; deleting the template too.
        String nom = tools.listGabarits(edition).gabarits().get(0).nom();
        etat = tools.deleteGabarit(null, List.of("2031-05-03"), edition);
        assertThat(etat.calendrier())
                .extracting(GenerateurCreneauxMcpTools.DateView::date)
                .doesNotContain(LocalDate.of(2031, 5, 3));
        etat = tools.deleteGabarit(nom, null, edition);
        assertThat(etat.gabarits()).isEmpty();
        assertThat(etat.calendrier()).isEmpty();
        assertThat(creneaux.listCreneaux(edition)).hasSize(9);
    }

    @Test
    void aRecurrenceIsAddedAndTheStandsWithoutHoursHaveNothingToDerive() {
        String edition = createEdition("Générateur MCP récurrence");

        GenerationView apercu = tools.previewGeneration(
                "recurrence",
                "10:00-12:00,14:00-16:00",
                "PLAGE",
                "2031-06-02",
                "2031-06-04",
                null,
                null,
                List.of("2031-06-03"),
                null,
                null,
                null,
                edition);
        assertThat(apercu.nombreGeneres()).isEqualTo(4);
        assertThat(creneaux.listCreneaux(edition)).isEmpty();
        GenerationView ecrit = tools.applyGeneration(
                "recurrence",
                "10:00-12:00,14:00-16:00",
                "PLAGE",
                "2031-06-02",
                "2031-06-04",
                null,
                null,
                List.of("2031-06-03"),
                null,
                null,
                null,
                edition);
        assertThat(ecrit.nombreGeneres()).isEqualTo(4);
        assertThat(creneaux.listCreneaux(edition)).hasSize(4);

        GenerationView stands = tools.previewGeneration(
                "stands", null, null, "2031-06-02", "2031-06-04", null, null, null, "00:00", null, true, edition);
        assertThat(stands.source()).isEqualTo("stands");
        assertThat(stands.nombreGeneres()).isZero();
        assertThat(stands.joursSansFenetre()).hasSize(3);
        assertThatThrownBy(() -> tools.applyGeneration(
                        "stands",
                        null,
                        null,
                        "2031-06-02",
                        "2031-06-04",
                        null,
                        null,
                        null,
                        "00:00",
                        30,
                        false,
                        edition))
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("rien à dériver");
    }

    @Test
    void aMalformedCallIsRefusedWithItsReason() {
        String edition = createEdition("Générateur MCP refus");
        refused(
                () -> tools.previewGeneration(
                        null, null, null, null, null, null, null, null, null, null, null, edition),
                "source est requise");
        refused(
                () -> tools.applyGeneration(
                        "planning", null, null, null, null, null, null, null, null, null, null, edition),
                "source inconnue");
        refused(
                () -> tools.previewGeneration(
                        "gabarits", null, null, null, null, null, null, null, null, null, null, edition),
                "Aucune date");
        refused(() -> tools.modifyGabarit("Jour normal", null, null, null, null, edition), "Rien à modifier");
        refused(
                () -> tools.modifyGabarit("Jour normal", "09:00-12:00", "2031-05-02", null, null, edition),
                "dateDebut et dateFin");
        refused(() -> tools.modifyGabarit(" ", "09:00-12:00", null, null, null, edition), "nom est requis");
        refused(() -> tools.deleteGabarit(null, null, edition), "soit nom");
        refused(() -> tools.deleteGabarit("Jour normal", List.of("2031-05-02"), edition), "soit nom");

        assertThatThrownBy(() -> tools.modifyGabarit("Inconnu", null, null, null, List.of("2031-05-02"), edition))
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(BusinessError.NotFound.class)
                .hasMessageContaining("Inconnu");
        assertThatThrownBy(() -> tools.deleteGabarit("Inconnu", null, edition))
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(BusinessError.NotFound.class);
    }

    /** The 3.x tools keep their names and their answers, and go through the generator. */
    @Test
    void theOldToolsStillWork() {
        String edition = createEdition("Générateur MCP anciens outils");

        assertThat(legacy.defineJourneeType("Nocturne", "18:00-23:00", edition).vacations())
                .isEqualTo("18:00-23:00");
        assertThat(legacy.defineJourneeType("Nocturne", "18:00-22:00", edition).vacations())
                .isEqualTo("18:00-22:00");
        JourneeTypeMcpTools.EtatView etat =
                legacy.assignJourneeType("Nocturne", "2031-07-01", "2031-07-02", null, edition);
        assertThat(etat.journeesTypes()).singleElement();
        assertThat(etat.calendrier())
                .extracting(JourneeTypeMcpTools.AffectationView::journeeTypeId)
                .containsOnly(etat.journeesTypes().get(0).id());
        assertThat(legacy.listJourneeTypes(edition).calendrier()).hasSize(2);

        assertThat(legacy.previewJourneeTypesApplication(edition).crees()).isEqualTo(2);
        assertThat(legacy.materializeJourneeTypes(edition).crees()).isEqualTo(2);
        assertThat(legacy.previewJourneeTypesRecognition(edition).calendrier()).hasSize(2);
        assertThat(legacy.recognizeJourneeTypes(edition).journeesTypes()).singleElement();

        assertThat(legacy.removeJourneeTypeDates(List.of("2031-07-02"), edition).calendrier())
                .hasSize(1);
        String nom = legacy.listJourneeTypes(edition).journeesTypes().get(0).nom();
        assertThat(legacy.deleteJourneeType(nom, edition).journeesTypes()).isEmpty();
        assertThatThrownBy(() -> legacy.deleteJourneeType("Absente", edition))
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(BusinessError.NotFound.class);
        assertThat(creneaux.listCreneaux(edition)).hasSize(2);
    }

    private static void refused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String phrase) {
        assertThatThrownBy(call)
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining(phrase);
    }

    private static String createEdition(String name) {
        return given().contentType("application/json")
                .body("{\"nom\":\"" + name + "\"}")
                .when()
                .post("/api/editions")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
    }
}
