package dev.sylvain.planning.api;

import static dev.sylvain.planning.OidcTests.token;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sylvain.planning.OidcTests;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.compte.Compte;
import dev.sylvain.planning.service.compte.CompteService;
import dev.sylvain.planning.service.compte.RoleHabilitation;
import dev.sylvain.planning.service.edition.EditionRepository;
import dev.sylvain.planning.service.publication.PlanPublicationService;
import dev.sylvain.planning.service.responsable.ReglageNoms;
import dev.sylvain.planning.service.responsable.ReglageResponsableService;
import dev.sylvain.planning.service.responsable.ReglagesResponsables;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import dev.sylvain.planning.testing.PhaseReset;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

/**
 * The responsable de stand: the published shifts of their stands on the active
 * edition, shifts only by default, names when the setting says so — and nothing
 * of anybody or anything else, the sweep of every route included.
 *
 * <p>The fixture: stand {@code RESP-S1} in scope, {@code RESP-S2} out of it.
 * Alice holds a seat on each, one S1 seat is empty, Bruno works on S2 only —
 * the responsable must never read his name, nor S2's.</p>
 */
@QuarkusTest
@TestProfile(OidcTests.Profil.class)
class ResponsableResourceTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 11);
    private static final String S2 = "Billetterie secrete";

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    PlanPublicationService publication;

    @Inject
    CompteService comptes;

    @Inject
    EditionRepository editions;

    @Inject
    ReglageResponsableService reglages;

    @Inject
    dev.sylvain.planning.service.edition.EditionService editionService;

    @Inject
    dev.sylvain.planning.service.EditionContext editionContext;

    @Inject
    DataSource dataSource;

    private String edition;
    private String email;
    private final String subject = "s-" + UUID.randomUUID();
    private Compte compte;

    @BeforeEach
    void seed() {
        sql("DELETE FROM version_plan");
        persistence.clearDatabase();
        sql("DELETE FROM reglage_noms_responsable");
        persistWorkingPlan(false);
        edition = editions.activeEditionId().orElseThrow();
        email = "responsable-" + UUID.randomUUID() + "@example.org";
        compte = comptes.create(email, "Rita Responsable");
        comptes.grant(compte.id(), RoleHabilitation.RESPONSABLE_STAND, edition, null, List.of("RESP-S1"));
        publication.publier();
    }

    /** The working plan: S1 holds Alice and an empty seat (or Bruno on it), S2 holds Alice and Bruno. */
    private void persistWorkingPlan(boolean secondSeatFilled) {
        Animateur alice = new Animateur("RESP-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur bruno = new Animateur("RESP-B", "Bruno", "Petit", LocalDate.of(2011, 2, 2), false);
        Stand s1 = new Stand("RESP-S1", "Buvette", Set.of(), 2, 2, false);
        Stand s2 = new Stand("RESP-S2", S2, Set.of(), 1, 1, false);
        Creneau matin = new Creneau(9501L, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(12, 0));
        Creneau aprem = new Creneau(9502L, 1, JOUR, LocalTime.of(14, 0), LocalTime.of(16, 0));
        PosteAffectation p1 = new PosteAffectation("RESP-P1", s1, matin);
        p1.setAnimateur(alice);
        PosteAffectation p2 = new PosteAffectation("RESP-P2", s1, matin);
        if (secondSeatFilled) {
            p2.setAnimateur(bruno);
        }
        PosteAffectation p3 = new PosteAffectation("RESP-P3", s2, aprem);
        p3.setAnimateur(alice);
        PosteAffectation p4 = new PosteAffectation("RESP-P4", s2, matin);
        p4.setAnimateur(bruno);
        persistence.persist(new PlanningEvenement(JOUR, List.of(alice, bruno), List.of(p1, p2, p3, p4)));
    }

    @AfterEach
    void clean() {
        sql("DELETE FROM version_plan");
        sql("DELETE FROM reglage_noms_responsable");
    }

    @Test
    void theRouteIsClosedToEveryoneButAResponsable() {
        given().header("Authorization", bearer(RoleHabilitation.ADMIN, null))
                .get("/api/responsable/stands")
                .then()
                .statusCode(403);
        given().header("Authorization", bearer(RoleHabilitation.ORGANISATEUR, edition))
                .get("/api/responsable/stands")
                .then()
                .statusCode(403);
        given().get("/api/responsable/stands").then().statusCode(401);
    }

    @Test
    void byDefaultTheResponsableReadsShiftsAndGapsWithoutAName() {
        String corps = given().header("Authorization", bearer())
                .get("/api/responsable/stands")
                .then()
                .statusCode(200)
                .body("stands", hasSize(1))
                .body("stands[0].standNom", equalTo("Buvette"))
                .body("stands[0].nominatif", equalTo(false))
                .body("stands[0].vacations", hasSize(1))
                .body("stands[0].vacations[0].pourvus", equalTo(1))
                .body("stands[0].vacations[0].vides", equalTo(1))
                .body("stands[0].vacations[0].personnes", hasSize(0))
                .extract()
                .asString();
        assertThat(corps).doesNotContain("Alice", "Martin", "Bruno", "Petit", S2, "RESP-S2", "dateNaissance");
    }

    @Test
    void namesShowWhenTheEditionSaysSoAndAStandCanOverrideItEitherWay() {
        reglages.replace(new ReglagesResponsables(new ReglageNoms(true, null), Map.of()));
        given().header("Authorization", bearer())
                .get("/api/responsable/stands")
                .then()
                .body("stands[0].nominatif", equalTo(true))
                .body("stands[0].vacations[0].personnes[0].prenom", equalTo("Alice"))
                .body("stands[0].vacations[0].personnes[0].nom", equalTo("Martin"));

        reglages.replace(
                new ReglagesResponsables(new ReglageNoms(true, null), Map.of("RESP-S1", new ReglageNoms(false, null))));
        String corps = given().header("Authorization", bearer())
                .get("/api/responsable/stands")
                .then()
                .body("stands[0].nominatif", equalTo(false))
                .extract()
                .asString();
        assertThat(corps).doesNotContain("Alice", "Martin");

        reglages.replace(
                new ReglagesResponsables(ReglageNoms.HORAIRES_SEULS, Map.of("RESP-S1", new ReglageNoms(true, null))));
        given().header("Authorization", bearer())
                .get("/api/responsable/stands")
                .then()
                .body("stands[0].nominatif", equalTo(true));
    }

    @Test
    void anOpeningDateKeepsNamesBackUntilItIsReached() {
        Instant demain = Instant.now().plus(1, ChronoUnit.DAYS);
        reglages.replace(new ReglagesResponsables(new ReglageNoms(true, demain), Map.of()));
        String avant = given().header("Authorization", bearer())
                .get("/api/responsable/stands")
                .then()
                .body("stands[0].nominatif", equalTo(false))
                .body("stands[0].nomsAttendus", org.hamcrest.Matchers.notNullValue())
                .extract()
                .asString();
        assertThat(avant).doesNotContain("Alice");

        reglages.replace(new ReglagesResponsables(
                new ReglageNoms(true, Instant.now().minus(1, ChronoUnit.HOURS)), Map.of()));
        given().header("Authorization", bearer())
                .get("/api/responsable/stands")
                .then()
                .body("stands[0].nominatif", equalTo(true))
                .body("stands[0].nomsAttendus", nullValue());
    }

    @Test
    void theSheetFollowsTheSameSettingAsTheScreen() throws Exception {
        byte[] sans = given().header("Authorization", bearer())
                .get("/api/responsable/stands/RESP-S1/affichette.pdf")
                .then()
                .statusCode(200)
                .contentType("application/pdf")
                .extract()
                .asByteArray();
        assertThat(textOf(sans)).contains("Buvette", "à pourvoir").doesNotContain("Alice", "Martin");

        reglages.replace(new ReglagesResponsables(new ReglageNoms(true, null), Map.of()));
        byte[] avec = given().header("Authorization", bearer())
                .get("/api/responsable/stands/RESP-S1/affichette.pdf")
                .then()
                .statusCode(200)
                .extract()
                .asByteArray();
        assertThat(textOf(avec)).contains("Alice Martin").doesNotContain("Bruno", S2);

        given().header("Authorization", bearer())
                .get("/api/responsable/stands/RESP-S2/affichette.pdf")
                .then()
                .statusCode(404);

        reglages.replace(new ReglagesResponsables(
                new ReglageNoms(true, Instant.now().plus(1, ChronoUnit.DAYS)), Map.of()));
        byte[] attendu = given().header("Authorization", bearer())
                .get("/api/responsable/stands/RESP-S1/affichette.pdf")
                .then()
                .statusCode(200)
                .extract()
                .asByteArray();
        assertThat(textOf(attendu)).doesNotContain("Alice", "Martin");
    }

    @Test
    void nothingOpensOnAnEditionThatIsNotActive() {
        String autre = UUID.randomUUID().toString();
        sql("INSERT INTO edition (id, nom, active) VALUES ('" + autre + "', 'Autre', FALSE)");
        try {
            String inactif = bearerWithRightOn(autre);
            given().header("Authorization", inactif)
                    .get("/api/responsable/stands")
                    .then()
                    .statusCode(200)
                    .body("stands", hasSize(0))
                    .body("editionNom", nullValue());
        } finally {
            sql("DELETE FROM habilitation_stand WHERE habilitation_id IN "
                    + "(SELECT id FROM habilitation WHERE edition_id = '" + autre + "')");
            sql("DELETE FROM habilitation WHERE edition_id = '" + autre + "'");
            sql("DELETE FROM edition WHERE id = '" + autre + "'");
        }
    }

    @Test
    void anExpiredWithdrawnOrDeactivatedRightOpensNothing() {
        sql("UPDATE habilitation SET expire_le = now() - interval '1 minute' WHERE compte_id = '" + compte.id() + "'");
        given().header("Authorization", bearer())
                .get("/api/responsable/stands")
                .then()
                .statusCode(403);

        sql("UPDATE habilitation SET expire_le = NULL WHERE compte_id = '" + compte.id() + "'");
        given().header("Authorization", bearer())
                .get("/api/responsable/stands")
                .then()
                .statusCode(200);
        comptes.deactivate(compte.id());
        given().header("Authorization", bearer())
                .get("/api/responsable/stands")
                .then()
                .statusCode(403);
        comptes.reactivate(compte.id());

        String droit = comptes.list().stream()
                .filter(c -> c.id().equals(compte.id()))
                .findFirst()
                .orElseThrow()
                .habilitations()
                .getFirst()
                .id();
        comptes.withdraw(compte.id(), droit);
        given().header("Authorization", bearer())
                .get("/api/responsable/stands")
                .then()
                .statusCode(403);
    }

    @Test
    void theResponsableReadsThePublishedPlanNotTheWorkingOne() {
        persistWorkingPlan(true);
        given().header("Authorization", bearer())
                .get("/api/responsable/stands")
                .then()
                .body("stands[0].vacations[0].vides", equalTo(1))
                .body("stands[0].vacations[0].pourvus", equalTo(1));
    }

    @Test
    void theAnswersAreNeverCached() {
        given().header("Authorization", bearer())
                .get("/api/responsable/stands")
                .then()
                .header("Cache-Control", "no-store");
        given().header("Authorization", bearer())
                .get("/api/responsable/stands/RESP-S1/affichette.pdf")
                .then()
                .header("Cache-Control", "no-store");
    }

    @Test
    void aPartialRequestNeverErasesTheExceptionOfAStandItDoesNotName() {
        reglages.replace(
                new ReglagesResponsables(new ReglageNoms(true, null), Map.of("RESP-S1", new ReglageNoms(false, null))));
        reglages.replace(new ReglagesResponsables(new ReglageNoms(true, null), Map.of()));
        assertThat(reglages.get().parStand()).containsKey("RESP-S1");

        java.util.HashMap<String, ReglageNoms> lift = new java.util.HashMap<>();
        lift.put("RESP-S1", null);
        reglages.replace(new ReglagesResponsables(new ReglageNoms(true, null), lift));
        assertThat(reglages.get().parStand()).isEmpty();
    }

    @Test
    void onlyAnAdministratorSetsWhatTheResponsablesRead() {
        String corps = "{\"parDefaut\":{\"nomsVisibles\":true,\"ouvertureLe\":null},\"parStand\":{}}";
        given().header("X-Edition-Id", edition)
                .header("Authorization", bearer(RoleHabilitation.ORGANISATEUR, edition))
                .contentType("application/json")
                .body(corps)
                .put("/api/reglage-responsables")
                .then()
                .statusCode(403);
        given().header("X-Edition-Id", edition)
                .header("Authorization", bearer(RoleHabilitation.ADMIN, null))
                .contentType("application/json")
                .body(corps)
                .put("/api/reglage-responsables")
                .then()
                .statusCode(200);
    }

    @Test
    void aResetBringsTheSettingBackToShiftsOnlyAndWithdrawsTheRights() {
        reglages.replace(new ReglagesResponsables(new ReglageNoms(true, null), Map.of()));
        // The fixture published, which froze the structure; emptying is a preparation gesture (ADR 0115).
        PhaseReset.backToPreparation();
        persistence.clearDatabase();
        assertThat(reglages.get().parDefaut().nomsVisibles()).isFalse();
        given().header("Authorization", bearer())
                .get("/api/responsable/stands")
                .then()
                .statusCode(403);
    }

    @Test
    void aDuplicatedEditionCopiesTheSettingsClosed() {
        reglages.replace(new ReglagesResponsables(
                new ReglageNoms(true, null), Map.of("RESP-S1", new ReglageNoms(true, Instant.now()))));
        String copie = editionService
                .duplicate(
                        edition,
                        new dev.sylvain.planning.domain.Edition(null, "Copie " + UUID.randomUUID(), false, null),
                        false)
                .getId();
        try {
            ReglagesResponsables copiees = editionContext.executeIn(copie, () -> reglages.get());
            assertThat(copiees.parDefaut().nomsVisibles()).isFalse();
            assertThat(copiees.parStand().get("RESP-S1")).isEqualTo(ReglageNoms.HORAIRES_SEULS);
        } finally {
            editionService.delete(copie);
        }
    }

    @Test
    void theResponsableOpensNoRouteOfTheAdministrationAndSeesNoOtherData() throws Exception {
        reglages.replace(new ReglagesResponsables(ReglageNoms.HORAIRES_SEULS, Map.of()));
        String bearer = bearer();
        JsonNode paths = new ObjectMapper()
                .readTree(Files.readString(Path.of("docs/schema/openapi.json")))
                .get("paths");
        List<String> fuites = new ArrayList<>();
        int refusees = 0;
        for (var it = paths.fields(); it.hasNext(); ) {
            var route = it.next();
            String chemin = route.getKey();
            if (chemin.startsWith("/api/responsable") || chemin.contains("{") || chemin.equals("/api/jobs/stream")) {
                continue;
            }
            if (route.getValue().has("get")) {
                var reponse = given().header("Authorization", bearer).get(chemin);
                if (reponse.statusCode() == 403) {
                    refusees++;
                }
                String corps = reponse.asString();
                if (corps.contains("Alice") || corps.contains("Bruno") || corps.contains(S2)) {
                    fuites.add(chemin + " -> " + reponse.statusCode());
                }
            }
        }
        assertThat(refusees).as("routes de lecture refusées").isGreaterThan(50);
        assertThat(fuites).as("routes qui rendent un nom ou un autre stand").isEmpty();
        for (var ecriture : List.of(given().header("Authorization", bearer)
                .contentType("application/json")
                .body("{}"))) {
            ecriture.post("/api/stands").then().statusCode(403);
            ecriture.put("/api/reglage-responsables").then().statusCode(403);
            ecriture.post("/api/comptes").then().statusCode(403);
        }
        given().header("Authorization", bearer)
                .delete("/api/stands/RESP-S1")
                .then()
                .statusCode(403);
    }

    @Test
    void aStandOfAnotherEditionOrAnUnknownOneCannotBeGranted() {
        Compte autre = comptes.create("responsable-" + UUID.randomUUID() + "@example.org", null);
        org.junit.jupiter.api.Assertions.assertThrows(
                dev.sylvain.planning.service.BusinessError.Invalid.class,
                () -> comptes.grant(autre.id(), RoleHabilitation.RESPONSABLE_STAND, edition, null, List.of("NOPE")));
        org.junit.jupiter.api.Assertions.assertThrows(
                dev.sylvain.planning.service.BusinessError.Invalid.class,
                () -> comptes.grant(autre.id(), RoleHabilitation.RESPONSABLE_STAND, edition, null, List.of()));
        org.junit.jupiter.api.Assertions.assertThrows(
                dev.sylvain.planning.service.BusinessError.Invalid.class,
                () -> comptes.grant(autre.id(), RoleHabilitation.RESPONSABLE_STAND, null, null, List.of("RESP-S1")));
        org.junit.jupiter.api.Assertions.assertThrows(
                dev.sylvain.planning.service.BusinessError.Invalid.class,
                () -> comptes.grant(autre.id(), RoleHabilitation.ORGANISATEUR, edition, null, List.of("RESP-S1")));
    }

    private String bearer() {
        // One subject for the whole test: the first sign-in binds the account to it for good.
        return token(subject, email, true);
    }

    private String bearer(RoleHabilitation role, String editionId) {
        String adresse = "autre-" + UUID.randomUUID() + "@example.org";
        Compte c = comptes.create(adresse, null);
        comptes.grant(c.id(), role, editionId, null);
        return token("s-" + UUID.randomUUID(), adresse, true);
    }

    private String bearerWithRightOn(String editionId) {
        String adresse = "inactif-" + UUID.randomUUID() + "@example.org";
        Compte c = comptes.create(adresse, null);
        sql("INSERT INTO habilitation (id, compte_id, role, edition_id, cree_par) VALUES ('" + UUID.randomUUID()
                + "', '" + c.id() + "', 'RESPONSABLE_STAND', '" + editionId + "', 'admin')");
        sql("INSERT INTO habilitation_stand (habilitation_id, stand_id) SELECT id, 'RESP-S1' FROM habilitation "
                + "WHERE compte_id = '" + c.id() + "'");
        return token("s-" + UUID.randomUUID(), adresse, true);
    }

    private static String textOf(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        try {
            return new PdfTextExtractor(reader).getTextFromPage(1);
        } finally {
            reader.close();
        }
    }

    private void sql(String requete) {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute(requete);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
