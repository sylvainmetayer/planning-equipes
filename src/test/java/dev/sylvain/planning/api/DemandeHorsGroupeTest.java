package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.PlanningPersistenceService;
import dev.sylvain.planning.service.ReferenceDataService;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;

/**
 * Une édition peut porter plusieurs groupes de créneaux mais un seul planning
 * persisté : une demande d'échange soumise sur un autre groupe que celui du
 * planning courant est signalée ({@code horsGroupe}) et ne peut être ni
 * mesurée ni acceptée — seulement refusée. Le décalage est fabriqué en SQL
 * (le champ {@code groupe_creneau_id} de la demande est dénormalisé, sans FK,
 * précisément pour survivre à la vie des groupes).
 */
@QuarkusTest
class DemandeHorsGroupeTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 13);
    private static final long CRENEAU_ID = 9401L;

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    ReferenceDataService referenceData;

    @Inject
    DataSource dataSource;

    @Inject
    MockMailbox mailbox;

    @BeforeEach
    void seed() {
        Animateur alice = new Animateur("GRP-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur bruno = new Animateur("GRP-B", "Bruno", "Petit", LocalDate.of(1992, 2, 2), false);
        Stand standUn = new Stand("GRP-S1", "Stand groupe un", Set.of(), 1, 1, false);
        Stand standDeux = new Stand("GRP-S2", "Stand groupe deux", Set.of(), 1, 1, false);
        Creneau creneau = new Creneau(CRENEAU_ID, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(12, 0));
        PosteAffectation posteUn = new PosteAffectation("GRP-P1", standUn, creneau);
        posteUn.setAnimateur(alice);
        PosteAffectation posteDeux = new PosteAffectation("GRP-P2", standDeux, creneau);
        posteDeux.setAnimateur(bruno);
        persistence.persist(new PlanningFestival(JOUR, List.of(alice, bruno), List.of(posteUn, posteDeux)));

        // Alice's espace session (e-mail code flow) rides on every request.
        Animateur aliceStockee = referenceData.listAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals("GRP-A"))
                .findFirst()
                .orElseThrow();
        aliceStockee.setEmail("grp-alice@example.org");
        referenceData.updateAnimateur("GRP-A", aliceStockee);
        mailbox.clear();
        RestAssured.requestSpecification = null;
        String session = EspaceSessions.ouvrir(mailbox, jetonDe("GRP-A"), "grp-alice@example.org");
        RestAssured.requestSpecification = new RequestSpecBuilder()
                .addCookie("planning-espace", session)
                .build();
    }

    @org.junit.jupiter.api.AfterEach
    void resetSpecification() {
        RestAssured.requestSpecification = null;
    }

    @Test
    void uneDemandeDUnAutreGroupeEstSignaleeEtSeulementRefusable() throws Exception {
        String jeton = jetonDe("GRP-A");
        String demandeId = given().contentType(ContentType.JSON)
                .body("[{\"creneauId\":" + CRENEAU_ID + ",\"standId\":\"GRP-S1\",\"cibleId\":\"GRP-B\"}]")
                .when().post("/api/espace-animateur/" + jeton + "/demandes")
                .then().statusCode(200)
                .extract().path("[0].id");

        // The admin re-solved another groupe since: the persisted planning is
        // on DEFAUT, the demande (says the database) came from GRP-AUTRE.
        executer("UPDATE demande_echange SET groupe_creneau_id = 'GRP-AUTRE' WHERE id = ?", demandeId);
        executer("UPDATE planning_resolution SET groupe_creneau_id = 'DEFAUT' WHERE edition_id = 'DEFAUT'", null);

        // Both list views flag the demande, with the origin groupe named.
        given().when().get("/api/echanges")
                .then().statusCode(200)
                .body("find { it.id == '" + demandeId + "' }.horsGroupe", equalTo(true))
                .body("find { it.id == '" + demandeId + "' }.groupeCreneauNom", equalTo("GRP-AUTRE"));
        given().when().get("/api/espace-animateur/" + jeton + "/demandes")
                .then().statusCode(200)
                .body("find { it.id == '" + demandeId + "' }.horsGroupe", equalTo(true));

        // Neither measurable nor acceptable — with a business message.
        given().when().get("/api/echanges/" + demandeId + "/impact")
                .then().statusCode(400)
                .body("message", containsString("groupe de créneaux"));
        given().contentType(ContentType.JSON).body("{}")
                .when().post("/api/echanges/" + demandeId + "/acceptation")
                .then().statusCode(400)
                .body("message", containsString("groupe de créneaux"));

        // Refusing stays possible: that is how such demandes get purged.
        given().contentType(ContentType.JSON).body("{\"commentaire\":\"groupe changé\"}")
                .when().post("/api/echanges/" + demandeId + "/refus")
                .then().statusCode(200)
                .body("statut", equalTo("REFUSEE"));
    }

    private void executer(String sql, String parametre) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            if (parametre != null) {
                ps.setString(1, parametre);
            }
            ps.executeUpdate();
        }
    }

    private String jetonDe(String animateurId) {
        return referenceData.listAnimateurs().stream()
                .filter(animateur -> animateur.getId().equals(animateurId))
                .findFirst()
                .orElseThrow()
                .getJetonAcces();
    }
}
