package dev.sylvain.planning.api;

import static dev.sylvain.planning.OidcTests.token;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import dev.sylvain.planning.OidcTests;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.compte.Compte;
import dev.sylvain.planning.service.compte.CompteService;
import dev.sylvain.planning.service.compte.RoleHabilitation;
import dev.sylvain.planning.service.edition.EditionRepository;
import dev.sylvain.planning.service.edition.EditionService;
import dev.sylvain.planning.service.publication.PlanPublicationService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The RH right: the compliance report of the edition it names, in the three forms,
 * journalled, closed to every other account and to every other edition.
 */
@QuarkusTest
@TestProfile(OidcTests.Profil.class)
class RhResourceTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 13);

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    PlanPublicationService publication;

    @Inject
    CompteService comptes;

    @Inject
    EditionRepository editionsRepository;

    @Inject
    EditionService editionService;

    @Inject
    DataSource dataSource;

    private String edition;
    private String autreEdition;

    @BeforeEach
    void seed() {
        sql("DELETE FROM plan_snapshot");
        persistence.clearDatabase();
        Animateur ada = new Animateur("RH-A", "Ada", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur bob = new Animateur("RH-B", "Bob", "Petit", LocalDate.of(2011, 2, 2), false);
        Stand stand = new Stand("RH-S1", "Buvette", Set.of(), 1, 2, false);
        List<PosteAffectation> postes = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Creneau c = new Creneau(9600L + i, 1, JOUR.plusDays(i), LocalTime.of(9, 0), LocalTime.of(19, 30));
            PosteAffectation p = new PosteAffectation("RH-P" + i, stand, c);
            p.setAnimateur(ada);
            postes.add(p);
        }
        Creneau nuit = new Creneau(9700L, 1, JOUR, LocalTime.of(21, 0), LocalTime.of(23, 30));
        PosteAffectation pn = new PosteAffectation("RH-PN", stand, nuit);
        pn.setAnimateur(bob);
        postes.add(pn);
        persistence.persist(new PlanningEvenement(JOUR, List.of(ada, bob), postes));
        edition = editionsRepository.activeEditionId().orElseThrow();
        autreEdition = editionService
                .create(new Edition(null, "AUTRE-" + UUID.randomUUID(), false, null))
                .getId();
    }

    @AfterEach
    void clean() {
        sql("DELETE FROM plan_snapshot");
        sql("DELETE FROM animateur_contact");
        sql("DELETE FROM journal_action WHERE action LIKE '%CONFORMITE%' OR action LIKE 'CONTACTS%'");
    }

    private String bearer(RoleHabilitation role, String editionId) {
        String adresse = "rh-" + UUID.randomUUID() + "@example.org";
        Compte c = comptes.create(adresse, null);
        comptes.grant(c.id(), role, editionId, null);
        return token("s-" + UUID.randomUUID(), adresse, true);
    }

    private String rh() {
        return bearer(RoleHabilitation.RH, edition);
    }

    @Test
    void theRhRightReadsTheReportOfItsEditionOnlyAfterAPublication() {
        String rh = rh();
        given().header("Authorization", rh)
                .get("/api/rh/editions/" + edition + "/conformite")
                .then()
                .statusCode(409);
        publication.publier();
        given().header("Authorization", rh)
                .get("/api/rh/editions/" + edition + "/conformite")
                .then()
                .statusCode(200)
                .body("editionId", equalTo(edition))
                .body("personnes", hasSize(2))
                .body("synthese.mineurs", equalTo(1))
                .body("personnes.find { it.animateurId == 'RH-A' }.semainesEnDepassement", hasSize(1))
                .body("personnes.find { it.animateurId == 'RH-B' }.mineur", equalTo(true));
    }

    @Test
    void everyOtherAccountAndEveryOtherEditionIsRefused() {
        publication.publier();
        String path = "/api/rh/editions/" + edition + "/conformite";
        given().get(path).then().statusCode(401);
        given().header("Authorization", bearer(RoleHabilitation.ORGANISATEUR, edition))
                .get(path)
                .then()
                .statusCode(403);
        // An RH of another edition reads nothing of this one, not even that it exists.
        given().header("Authorization", bearer(RoleHabilitation.RH, autreEdition))
                .get(path)
                .then()
                .statusCode(404);
        given().header("Authorization", rh())
                .get("/api/rh/editions/inconnue/conformite")
                .then()
                .statusCode(404);
        // The RH right opens nothing of the administration.
        given().header("Authorization", rh()).get("/api/animateurs").then().statusCode(403);
    }

    @Test
    void anAdministratorReadsAnyEditionAndTheListFollowsTheRights() {
        publication.publier();
        String admin = bearer(RoleHabilitation.ADMIN, null);
        given().header("Authorization", admin)
                .get("/api/rh/editions/" + edition + "/conformite")
                .then()
                .statusCode(200);
        given().header("Authorization", rh())
                .get("/api/rh/editions")
                .then()
                .statusCode(200)
                .body("id", org.hamcrest.Matchers.contains(edition));
    }

    @Test
    void theExportsAreFilesAndEveryReadLeavesATrace() throws Exception {
        publication.publier();
        String rh = rh();
        byte[] pdf = given().header("Authorization", rh)
                .get("/api/rh/editions/" + edition + "/conformite.pdf")
                .then()
                .statusCode(200)
                .extract()
                .asByteArray();
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
        String csv = given().header("Authorization", rh)
                .get("/api/rh/editions/" + edition + "/conformite.csv")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        assertThat(csv).contains("Ada Martin;non;").contains("Bob Petit;oui;");
        given().header("Authorization", rh)
                .get("/api/rh/editions/" + edition + "/conformite")
                .then()
                .statusCode(200);
        // No X-Edition-Id was sent: the trace is filed under the edition of the report all the same.
        assertThat(journalCount("CONFORMITE_CONSULTEE")).isEqualTo(1);
        assertThat(journalCount("EXPORT_CONFORMITE_PDF")).isEqualTo(1);
        assertThat(journalCount("EXPORT_CONFORMITE_CSV")).isEqualTo(1);
    }

    /** A number built at run time: nothing here is somebody's. */
    private static String phone() {
        return "+" + (33_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L));
    }

    @Test
    void theRhRightSavesAndReadsContactsAndIsWarnedForAMinorWithoutARepresentative() throws Exception {
        String rh = rh();
        String urgence = phone();
        String path = "/api/rh/editions/" + edition + "/contacts/RH-B";
        given().header("Authorization", rh)
                .contentType("application/json")
                .body(java.util.Map.of("urgenceNom", "Camille", "urgenceTelephone", urgence))
                .put(path)
                .then()
                .statusCode(200)
                .body("contacts.urgenceTelephone", equalTo(urgence))
                .body("avertissements", hasSize(1))
                .body("avertissements[0].type", equalTo("MINEUR_SANS_REPRESENTANT"))
                .body("avertissements[0].message", org.hamcrest.Matchers.containsString("RH-B"))
                .body(
                        "avertissements[0].message",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Bob")));
        given().header("Authorization", rh)
                .get("/api/rh/editions/" + edition + "/contacts")
                .then()
                .statusCode(200)
                .body("animateurId", org.hamcrest.Matchers.contains("RH-B"))
                .body("[0].mineur", equalTo(true))
                .body("[0].contacts.urgenceNom", equalTo("Camille"));
        given().header("Authorization", rh)
                .contentType("application/json")
                .body(java.util.Map.of("representantNom", "Dominique", "representantTelephone", phone()))
                .put(path)
                .then()
                .statusCode(200)
                .body("avertissements", hasSize(0));
        given().header("Authorization", rh)
                .contentType("application/json")
                .body(java.util.Map.of("urgenceTelephone", "12"))
                .put(path)
                .then()
                .statusCode(400);
        assertThat(journalCount("CONTACTS_CONSULTES")).isEqualTo(1);
        assertThat(journalCount("CONTACTS_MODIFIES")).isEqualTo(2);
    }

    @Test
    void contactsAreClosedToOrganisersAndToTheRhOfAnotherEdition() {
        String path = "/api/rh/editions/" + edition + "/contacts";
        given().header("Authorization", bearer(RoleHabilitation.ORGANISATEUR, edition))
                .get(path)
                .then()
                .statusCode(403);
        given().header("Authorization", bearer(RoleHabilitation.ORGANISATEUR, edition))
                .contentType("application/json")
                .body("{}")
                .put(path + "/RH-B")
                .then()
                .statusCode(403);
        given().header("Authorization", bearer(RoleHabilitation.RH, autreEdition))
                .get(path)
                .then()
                .statusCode(404);
    }

    @Test
    void theReportListsTheMinorsWhoseRepresentativeIsMissingUntilItIsRecorded() {
        publication.publier();
        String rh = rh();
        String report = "/api/rh/editions/" + edition + "/conformite";
        given().header("Authorization", rh)
                .get(report)
                .then()
                .statusCode(200)
                .body("mineursSansRepresentant.animateurId", org.hamcrest.Matchers.contains("RH-B"));
        given().header("Authorization", rh)
                .contentType("application/json")
                .body(java.util.Map.of("representantNom", "Dominique"))
                .put("/api/rh/editions/" + edition + "/contacts/RH-B")
                .then()
                .statusCode(200);
        given().header("Authorization", rh).get(report).then().body("mineursSansRepresentant", hasSize(0));
        assertThat(given().header("Authorization", rh)
                        .get(report + ".csv")
                        .then()
                        .statusCode(200)
                        .extract()
                        .asString())
                .contains("Bob Petit;oui;")
                .contains(";renseigne");
    }

    private int journalCount(String action) throws Exception {
        try (var connection = dataSource.getConnection();
                var ps = connection.prepareStatement(
                        "SELECT count(*) FROM journal_action WHERE edition_id = ? AND action = ?")) {
            ps.setString(1, edition);
            ps.setString(2, action);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
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
