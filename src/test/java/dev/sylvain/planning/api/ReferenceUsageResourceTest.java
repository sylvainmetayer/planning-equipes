package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import dev.sylvain.planning.service.ReferenceDataService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What the delete confirmation is told before it asks: how many seats, ad hoc
 * constraints and locks name the rows about to go.
 *
 * <p>The whole point of asking the server is that the screens making the call —
 * Stands, Animateurs, Créneaux — never load the persisted plan, so no client
 * could count this on its own without fetching a planning it has no other use
 * for.</p>
 */
@QuarkusTest
class ReferenceUsageResourceTest {

    private static final String STAND = "USAGE-S1";
    private static final String STAND_LIBRE = "USAGE-S2";
    private static final String ANIMATEUR = "USAGE-A1";
    private static final long CRENEAU = 9411L;
    private static final LocalDate JOUR = LocalDate.of(2030, 7, 4);

    @Inject
    ReferenceDataService referenceData;

    @Inject
    PlanningPersistenceService persistence;

    /**
     * One stand, one animateur and one timeslot, referenced once each way:
     * a filled seat, an {@code AFFECTATION_FORCEE} naming all three at once,
     * and one lock per referential. A second seat is left empty on the same
     * stand — it must not count.
     */
    @BeforeEach
    void seedReferencedData() {
        Creneau creneau = new Creneau(CRENEAU, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(12, 0));
        Stand stand = new Stand(STAND, "Stand référencé", Set.of(), 1, 2, false);
        Stand standLibre = new Stand(STAND_LIBRE, "Stand ignoré", Set.of(), 1, 1, false);
        Animateur animateur = new Animateur(ANIMATEUR, "Alice", "Martin", LocalDate.of(1990, 1, 1), false);

        PosteAffectation pourvu = new PosteAffectation("USAGE-P1", stand, creneau);
        pourvu.setAnimateur(animateur);
        PosteAffectation vacant = new PosteAffectation("USAGE-P2", stand, creneau);
        PosteAffectation autreStand = new PosteAffectation("USAGE-P3", standLibre, creneau);
        autreStand.setAnimateur(animateur);

        persistence.persist(new PlanningEvenement(JOUR, List.of(animateur),
                List.of(pourvu, vacant, autreStand)));

        ContrainteAdHoc contrainte = new ContrainteAdHoc("USAGE-C1", TypeContrainteAdHoc.AFFECTATION_FORCEE);
        contrainte.setStand(stand);
        contrainte.setCreneau(creneau);
        contrainte.getAnimateursConcernes().add(animateur);
        referenceData.createContrainteAdHoc(contrainte);

        lock("USAGE-V-STAND", TypeVerrouillage.STAND, verrou -> verrou.setStandId(STAND));
        lock("USAGE-V-ANIM", TypeVerrouillage.ANIMATEUR, verrou -> verrou.setAnimateurId(ANIMATEUR));
        lock("USAGE-V-CRENEAU", TypeVerrouillage.CRENEAU, verrou -> verrou.setCreneauId(CRENEAU));
    }

    @AfterEach
    void removeSeededData() {
        // The plan goes first: its seats reference the stands and the timeslot,
        // and a failing delete here would report the wrong cause.
        persistence.persist(new PlanningEvenement(JOUR, List.of(), List.of()));
        List.of("USAGE-V-STAND", "USAGE-V-ANIM", "USAGE-V-CRENEAU").forEach(referenceData::deleteVerrouillage);
        referenceData.deleteContrainteAdHoc("USAGE-C1");
        referenceData.deleteCreneaux(List.of(CRENEAU));
        referenceData.deleteStand(STAND);
        referenceData.deleteStand(STAND_LIBRE);
        referenceData.deleteAnimateur(ANIMATEUR);
    }

    @Test
    void unStandRapporteSesPostesPourvusSesContraintesEtSesVerrous() {
        usages("/api/stands", STAND).body("affectations", equalTo(1))
                .body("contraintesAdHoc", equalTo(1))
                .body("verrouillages", equalTo(1));
    }

    @Test
    void unAnimateurRapporteSesPostesSesContraintesEtSesVerrous() {
        usages("/api/animateurs", ANIMATEUR).body("affectations", equalTo(2))
                .body("contraintesAdHoc", equalTo(1))
                .body("verrouillages", equalTo(1));
    }

    @Test
    void unCreneauRapporteSesPostesSesContraintesEtSesVerrous() {
        usages("/api/creneaux", String.valueOf(CRENEAU)).body("affectations", equalTo(2))
                .body("contraintesAdHoc", equalTo(1))
                .body("verrouillages", equalTo(1));
    }

    /** Zero is an answer, not an absence: the confirmation still has something to say. */
    @Test
    void unStandSansContrainteNiVerrouRapporteZeroSurCesDeuxCompteurs() {
        usages("/api/stands", STAND_LIBRE).body("affectations", equalTo(1))
                .body("contraintesAdHoc", equalTo(0))
                .body("verrouillages", equalTo(0));
    }

    /**
     * A whole selection in one request, totalled — the bulk delete shows a
     * single figure and must not pay one round trip per row to get it.
     */
    @Test
    void uneSelectionEstTotaliseeEnUnSeulAppel() {
        given().queryParam("id", STAND, STAND_LIBRE)
                .when().get("/api/stands/usages")
                .then()
                .statusCode(200)
                .body("affectations", equalTo(2))
                .body("contraintesAdHoc", equalTo(1))
                .body("verrouillages", equalTo(1));
    }

    /**
     * An id nobody knows counts as zero rather than as a 404: the dialog is
     * opened on rows a screen is already showing, and a row deleted meanwhile
     * must not turn a confirmation into an error message.
     */
    @Test
    void unIdentifiantInconnuNeCompteRienEtNeLevePas() {
        usages("/api/stands", "USAGE-INEXISTANT").body("affectations", equalTo(0))
                .body("contraintesAdHoc", equalTo(0))
                .body("verrouillages", equalTo(0));
    }

    /** A request naming nothing asks nothing, and is refused as such. */
    @Test
    void uneRequeteSansIdentifiantEstRefusee() {
        given().when().get("/api/stands/usages").then().statusCode(400);
    }

    /**
     * A timeslot id that is not a number is a broken query field, not a
     * missing row: {@code 400}, never the {@code 404} the container's own
     * conversion of a {@code List<Long>} would have produced before the
     * resource was even entered — which would have contradicted the rule
     * above, where an unknown id is precisely what never gets a {@code 404}.
     */
    @Test
    void unIdentifiantDeCreneauNonNumeriqueEstRefuseEnQuatreCents() {
        given().queryParam("id", "abc")
                .when().get("/api/creneaux/usages")
                .then()
                .statusCode(400);
    }

    private static ValidatableResponse usages(String resource, String id) {
        return given().queryParam("id", id).when().get(resource + "/usages").then().statusCode(200);
    }

    private void lock(String id, TypeVerrouillage type, Consumer<VerrouillagePlanning> cible) {
        VerrouillagePlanning verrouillage = new VerrouillagePlanning(id, type);
        cible.accept(verrouillage);
        referenceData.createVerrouillage(verrouillage);
    }
}
