package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import dev.sylvain.planning.OidcJetons;
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
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The responsable de stand (issue #295): the published plan of their stands,
 * head counts by default, names when the edition or their right says so —
 * and nothing of anybody or anything else.
 *
 * <p>The fixture: stand {@code RESP-S1} in scope, {@code RESP-S2} out of it.
 * Alice holds a seat on each (10:00 on S1, 14:00 on S2), one S1 seat is
 * empty, Bruno works on S2 only — the responsable must never read his name,
 * nor S2's.</p>
 */
@QuarkusTest
class ResponsableResourceTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 11);

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    PlanPublicationService publication;

    @Inject
    CompteService comptes;

    @Inject
    EditionRepository editions;

    @Inject
    ReferenceDataService referenceData;

    @Inject
    DataSource dataSource;

    private String edition;

    private String email;

    private Compte compte;

    @BeforeEach
    void seed() {
        sql("DELETE FROM plan_snapshot");
        persistence.clearDatabase();
        sql("DELETE FROM parametres_responsables");
        Animateur alice = new Animateur("RESP-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur bruno = new Animateur("RESP-B", "Bruno", "Petit", LocalDate.of(2011, 2, 2), false);
        Stand s1 = new Stand("RESP-S1", "Buvette", Set.of(), 2, 2, false);
        Stand s2 = new Stand("RESP-S2", "Billetterie secrète", Set.of(), 1, 1, false);
        Creneau matin = new Creneau(9501L, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(12, 0));
        Creneau aprem = new Creneau(9502L, 1, JOUR, LocalTime.of(14, 0), LocalTime.of(16, 0));
        PosteAffectation p1 = new PosteAffectation("RESP-P1", s1, matin);
        p1.setAnimateur(alice);
        PosteAffectation p2 = new PosteAffectation("RESP-P2", s1, matin);
        PosteAffectation p3 = new PosteAffectation("RESP-P3", s2, aprem);
        p3.setAnimateur(alice);
        PosteAffectation p4 = new PosteAffectation("RESP-P4", s2, matin);
        p4.setAnimateur(bruno);
        persistence.persist(new PlanningEvenement(JOUR, List.of(alice, bruno), List.of(p1, p2, p3, p4)));

        edition = editions.activeEditionId().orElseThrow();
        email = "responsable-" + UUID.randomUUID() + "@example.org";
        compte = comptes.create(email, "Rita Responsable");
    }

    @AfterEach
    void nettoyer() {
        sql("DELETE FROM plan_snapshot");
        sql("DELETE FROM parametres_responsables");
    }

    @Test
    void sansDroitDeResponsableLaRouteEstFermeeAdministrateurCompris() {
        given().header("Authorization", porteur(email, "user"))
                .when()
                .get("/api/responsable/editions")
                .then()
                .statusCode(403);
        given().header("Authorization", porteur("responsable-admin@example.org", "user", "admin"))
                .when()
                .get("/api/responsable/editions")
                .then()
                .statusCode(403);
        given().when().get("/api/responsable/editions").then().statusCode(401);
    }

    @Test
    void leResponsableVoitSesEditions() {
        grant(null);

        given().header("Authorization", porteur(email, "user"))
                .when()
                .get("/api/responsable/editions")
                .then()
                .statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].editionId", equalTo(edition))
                .body("[0].active", equalTo(true));
        given().header("Authorization", porteur(email, "user"))
                .when()
                .get("/api/auth/me")
                .then()
                .body("roles", org.hamcrest.Matchers.hasItem("responsable-stand"));
    }

    /**
     * A right without an expiry — granted before it was mandatory, or written
     * by hand — is one that never ends, not a 500.
     */
    @Test
    void unDroitSansExpirationSeLitSansErreur() {
        Compte avec = grant(null);
        sql("UPDATE habilitation SET expire_le = NULL WHERE compte_id = '" + avec.id() + "'");
        Compte autre = comptes.create("responsable-cache-" + UUID.randomUUID() + "@example.org", null);
        comptes.deactivate(autre.id());

        given().header("Authorization", porteur(email, "user"))
                .when()
                .get("/api/responsable/editions")
                .then()
                .statusCode(200)
                .body("[0].expireLe", nullValue());
    }

    @Test
    void avantLaPublicationLeStandNAAucuneVacation() {
        grant(null);

        given().header("Authorization", porteur(email, "user"))
                .when()
                .get("/api/responsable/editions/" + edition)
                .then()
                .statusCode(200)
                .body("publieLe", nullValue())
                .body("stands.size()", equalTo(1))
                .body("stands[0].standNom", equalTo("Buvette"))
                .body("stands[0].vacations.size()", equalTo(0));
    }

    /** Off by default: « deux places, une pourvue », and not a name. */
    @Test
    void parDefautLeResponsableNeLitQueDesEffectifs() {
        grant(null);
        publication.publier();

        String vue = given().header("Authorization", porteur(email, "user"))
                .when()
                .get("/api/responsable/editions/" + edition)
                .then()
                .statusCode(200)
                .body("stands.size()", equalTo(1))
                .body("stands[0].nominatif", equalTo(false))
                .body("stands[0].vacations.size()", equalTo(1))
                .body("stands[0].vacations[0].pourvus", equalTo(1))
                .body("stands[0].vacations[0].vides", equalTo(1))
                .body("stands[0].vacations[0].personnes.size()", equalTo(0))
                .body("equipe.size()", equalTo(0))
                .extract()
                .asString();

        assertThat(vue).doesNotContain("Alice", "Martin", "Bruno", "Petit", "Billetterie", "RESP-S2");
    }

    /**
     * Switched on for the edition: first and last names, and Alice's afternoon
     * elsewhere as « occupé » — never the stand, never Bruno, never an
     * address, a birth date or a token.
     */
    @Test
    void nominatifLeResponsableLitLesNomsEtRienDePlus() {
        given().contentType(ContentType.JSON)
                .body("{\"nominatif\":true}")
                .when()
                .put("/api/parametres-responsables")
                .then()
                .statusCode(200);
        grant(null);
        Animateur alice = referenceData.listAnimateurs().stream()
                .filter(a -> a.getId().equals("RESP-A"))
                .findFirst()
                .orElseThrow();
        alice.setEmail("alice-resp@example.org");
        alice.setTelephone("06 11 22 33 44");
        referenceData.updateAnimateur("RESP-A", alice);
        publication.publier();

        String vue = given().header("Authorization", porteur(email, "user"))
                .when()
                .get("/api/responsable/editions/" + edition)
                .then()
                .statusCode(200)
                .body("stands[0].nominatif", equalTo(true))
                .body("stands[0].vacations[0].personnes.size()", equalTo(1))
                .body("stands[0].vacations[0].personnes[0].prenom", equalTo("Alice"))
                .body("stands[0].vacations[0].personnes[0].nom", equalTo("Martin"))
                .body("equipe.size()", equalTo(1))
                .body("equipe[0].prenom", equalTo("Alice"))
                .body("equipe[0].plages.size()", equalTo(2))
                .body("equipe[0].plages[0].standNom", equalTo("Buvette"))
                .body("equipe[0].plages[1].debut", equalTo("2026-07-11T14:00:00"))
                .body("equipe[0].plages[1].standNom", nullValue())
                .extract()
                .asString();

        assertThat(vue)
                .doesNotContain("Bruno", "Petit", "Billetterie", "RESP-S2", "RESP-A")
                .doesNotContain("alice-resp@example.org", "06 11 22 33 44", "1990")
                .doesNotContain(referenceData.listAnimateurs().stream()
                        .filter(a -> a.getId().equals("RESP-A"))
                        .findFirst()
                        .orElseThrow()
                        .getAccessToken());
    }

    /** The right's override wins over the edition, either way. */
    @Test
    void laSurchargeDuDroitLEmporteSurLEdition() {
        grant(true);
        publication.publier();

        given().header("Authorization", porteur(email, "user"))
                .when()
                .get("/api/responsable/editions/" + edition)
                .then()
                .body("stands[0].nominatif", equalTo(true))
                .body("stands[0].vacations[0].personnes[0].prenom", equalTo("Alice"));

        given().contentType(ContentType.JSON)
                .body("{\"nominatif\":true}")
                .when()
                .put("/api/parametres-responsables")
                .then()
                .statusCode(200);
        String autre = "responsable-anonyme-" + UUID.randomUUID() + "@example.org";
        Compte anonyme = comptes.create(autre, null);
        comptes.grant(
                anonyme.id(),
                RoleHabilitation.RESPONSABLE_STAND,
                edition,
                Instant.now().plus(Duration.ofDays(30)),
                List.of("RESP-S1"),
                false,
                "test");

        given().header("Authorization", porteur(autre, "user"))
                .when()
                .get("/api/responsable/editions/" + edition)
                .then()
                .body("stands[0].nominatif", equalTo(false))
                .body("stands[0].vacations[0].personnes.size()", equalTo(0));
    }

    /**
     * Out of scope and nonexistent read the same: a stand of the edition that
     * is not theirs, a stand that does not exist, an edition they hold no
     * right on.
     */
    @Test
    void horsPerimetreLeRefusNeDitPasSiLaCibleExiste() {
        grant(null);
        publication.publier();

        String horsPerimetre = refus("/api/responsable/editions/" + edition + "?stand=RESP-S2");
        String inexistant = refus("/api/responsable/editions/" + edition + "?stand=n-existe-pas");
        String autreEdition = refus("/api/responsable/editions/edition-inconnue");

        assertThat(horsPerimetre).isEqualTo(inexistant).isEqualTo(autreEdition);
        assertThat(horsPerimetre).doesNotContain("RESP-S2", "Billetterie");

        given().header("Authorization", porteur(email, "user"))
                .when()
                .get("/api/responsable/editions/" + edition + "?stand=RESP-S1")
                .then()
                .statusCode(200)
                .body("stands.size()", equalTo(1));
    }

    @Test
    void unDroitExpireNOuvrePlusRien() {
        Compte avec = grant(null);
        publication.publier();
        given().header("Authorization", porteur(email, "user"))
                .when()
                .get("/api/responsable/editions/" + edition)
                .then()
                .statusCode(200);

        sql("UPDATE habilitation SET expire_le = now() - interval '1 minute' WHERE compte_id = '" + avec.id() + "'");
        // The account read is cached for thirty seconds; a write through the
        // service clears it, as the organiser's own actions do.
        Compte autre = comptes.create("responsable-cache-" + UUID.randomUUID() + "@example.org", null);
        comptes.deactivate(autre.id());

        given().header("Authorization", porteur(email, "user"))
                .when()
                .get("/api/responsable/editions/" + edition)
                .then()
                .statusCode(403);
    }

    @Test
    void unDroitRetireNOuvrePlusRien() {
        Compte avec = grant(null);
        comptes.withdraw(compte.id(), avec.habilitations().getFirst().id());

        given().header("Authorization", porteur(email, "user"))
                .when()
                .get("/api/responsable/editions")
                .then()
                .statusCode(403);
    }

    @Test
    void unCompteDesactiveNeLitPlusRien() {
        grant(null);
        comptes.deactivate(compte.id());

        given().header("Authorization", porteur(email, "user"))
                .when()
                .get("/api/responsable/editions")
                .then()
                .statusCode(403);
    }

    @Test
    void leReglageDEditionSeLitEtSEcrit() {
        given().when()
                .get("/api/parametres-responsables")
                .then()
                .statusCode(200)
                .body("nominatif", equalTo(false));
        given().contentType(ContentType.JSON)
                .body("{\"nominatif\":true}")
                .when()
                .put("/api/parametres-responsables")
                .then()
                .statusCode(200)
                .body("nominatif", equalTo(true));
        given().when().get("/api/parametres-responsables").then().body("nominatif", equalTo(true));
    }

    private String refus(String chemin) {
        return given().header("Authorization", porteur(email, "user"))
                .when()
                .get(chemin)
                .then()
                .statusCode(404)
                .extract()
                .asString();
    }

    private Compte grant(Boolean nominatif) {
        return comptes.grant(
                compte.id(),
                RoleHabilitation.RESPONSABLE_STAND,
                edition,
                Instant.now().plus(Duration.ofDays(30)),
                List.of("RESP-S1"),
                nominatif,
                "test");
    }

    private void sql(String requete) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(requete);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed: " + requete, e);
        }
    }

    private static String porteur(String email, String... roles) {
        return "Bearer " + OidcJetons.jeton(email, List.of(roles), "planning-app", email, true);
    }
}
