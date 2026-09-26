package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.publication.EnvoiPlanningRepository;
import dev.sylvain.planning.service.publication.EnvoiPlanningRepository.CauseEchec;
import dev.sylvain.planning.service.publication.EnvoiPlanningRepository.NatureEnvoi;
import dev.sylvain.planning.service.publication.PublicationTraceRepository.StatutEnvoi;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * « Qui a reçu quelle version » (Diffuser): the delivery ledger read back per
 * person, a failed send keeping the home screen off « à jour », the resend
 * turning « échec » into « renvoyé », and the publication aimed at a few
 * people deferring everybody else.
 */
@QuarkusTest
class EtatEnvoisResourceTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 11);
    private static final long CRENEAU_ID = 9401L;
    private static final String EMAIL_ALICE = "alice-etat@example.org";
    private static final String EMAIL_BRUNO = "bruno-etat@example.org";

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    ReferenceDataService referenceData;

    @Inject
    EnvoiPlanningRepository envois;

    @Inject
    MockMailbox mailbox;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void seed() {
        mailbox.clear();
        forget();
        persistence.clearDatabase();
        persistPlan(false);
        donnerEmail("ETAT-A", EMAIL_ALICE);
        donnerEmail("ETAT-B", EMAIL_BRUNO);
        donnerEmail("ETAT-C", null);
    }

    @AfterEach
    void nettoyer() {
        forget();
    }

    private void forget() {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM envoi_planning");
            statement.executeUpdate("DELETE FROM plan_snapshot");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clear the publications", e);
        }
    }

    @Test
    void everyPersonHasALineAndAPublicationWritesItsOutcome() {
        publier(Map.of());

        JsonPath etat = etat();
        assertThat(etat.getInt("derniereVersion.numero")).isEqualTo(1);
        assertThat(etat.getList("personnes.animateurId")).containsExactlyInAnyOrder("ETAT-A", "ETAT-B", "ETAT-C");
        Map<String, Object> alice = ligne(etat, "ETAT-A");
        assertThat(path(alice, "envoi", "statut")).isEqualTo("ENVOYE");
        assertThat(path(alice, "envoi", "nature")).isEqualTo("PUBLICATION");
        assertThat(path(alice, "version", "numero")).isEqualTo(1);
        // Chloé holds a seat and has no address: said, and never « envoyé ».
        Map<String, Object> chloe = ligne(etat, "ETAT-C");
        assertThat(chloe.get("email")).isEqualTo(false);
        assertThat(path(chloe, "envoi", "statut")).isEqualTo("SANS_EMAIL");
    }

    /**
     * The acceptance criterion of the Diffuser screen: a send that failed shows
     * « échec » on that person and the home screen stops saying « à jour ».
     * The resend then writes « envoyé » over it.
     */
    @Test
    void aFailedSendKeepsTheHomeScreenOffUpToDateUntilItIsResent() {
        publier(Map.of());
        envois.record(List.of(new EnvoiPlanningRepository.Envoi(
                "ETAT-A",
                null,
                NatureEnvoi.PUBLICATION,
                StatutEnvoi.ECHEC,
                CauseEchec.ADRESSE_REFUSEE,
                Instant.now())));

        Map<String, Object> alice = ligne(etat(), "ETAT-A");
        assertThat(path(alice, "envoi", "statut")).isEqualTo("ECHEC");
        assertThat(path(alice, "envoi", "cause")).isEqualTo("ADRESSE_REFUSEE");
        JsonPath accueil = given().when()
                .get("/api/editions/courant/etat")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(accueil.getInt("publication.envoisEnEchec")).isEqualTo(1);
        assertThat(accueil.getString("publication.statut")).isNotEqualTo("FAIT");

        given().contentType(ContentType.JSON)
                .when()
                .post("/api/planning/envoi/animateur/ETAT-A")
                .then()
                .statusCode(200);

        Map<String, Object> apres = ligne(etat(), "ETAT-A");
        assertThat(path(apres, "envoi", "statut")).isEqualTo("ENVOYE");
        assertThat(path(apres, "envoi", "nature")).isEqualTo("RENVOI");
        assertThat(given().when()
                        .get("/api/planning/publication")
                        .then()
                        .statusCode(200)
                        .extract()
                        .jsonPath()
                        .getInt("envoisEnEchec"))
                .isZero();
    }

    /**
     * « Prévenir les 2 personnes »: only the people named are written to;
     * everybody else concerned is deferred and comes back in the next count.
     */
    @Test
    void aPublicationAimedAtOnePersonDefersEverybodyElse() {
        publier(Map.of());
        mailbox.clear();
        persistPlan(true);

        JsonPath rapport = publier(Map.of("cibles", List.of("ETAT-A")));

        assertThat(rapport.getInt("envoyes")).isEqualTo(1);
        assertThat(mailbox.getMailsSentTo(EMAIL_ALICE)).hasSize(1);
        assertThat(mailbox.getMailsSentTo(EMAIL_BRUNO)).isEmpty();
        JsonPath etat = etat();
        assertThat(ligne(etat, "ETAT-B").get("aPrevenir")).isEqualTo(true);
        assertThat(ligne(etat, "ETAT-B").get("differe")).isEqualTo(true);
        assertThat(ligne(etat, "ETAT-A").get("aPrevenir")).isEqualTo(false);
    }

    /* -------------------------------- Helpers ------------------------------ */

    private JsonPath etat() {
        return given().when()
                .get("/api/planning/publication/etat")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    private JsonPath publier(Map<String, Object> corps) {
        return given().contentType(ContentType.JSON)
                .body(corps)
                .when()
                .post("/api/planning/publication")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    private static Map<String, Object> ligne(JsonPath etat, String animateurId) {
        List<Map<String, Object>> personnes = etat.getList("personnes");
        return personnes.stream()
                .filter(personne -> animateurId.equals(personne.get("animateurId")))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Object path(Map<String, Object> ligne, String objet, String champ) {
        Object valeur = ligne.get(objet);
        return valeur == null ? null : ((Map<String, Object>) valeur).get(champ);
    }

    /**
     * Alice and Bruno on two stands, Chloé on a third; {@code echange} swaps
     * Alice and Bruno, which concerns both of them.
     */
    private void persistPlan(boolean echange) {
        Animateur alice = new Animateur("ETAT-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur bruno = new Animateur("ETAT-B", "Bruno", "Petit", LocalDate.of(1992, 2, 2), false);
        Animateur chloe = new Animateur("ETAT-C", "Chloé", "Durand", LocalDate.of(1995, 3, 3), false);
        Stand un = new Stand("ETAT-S1", "Stand état un", Set.of(), 1, 1, false);
        Stand deux = new Stand("ETAT-S2", "Stand état deux", Set.of(), 1, 1, false);
        Stand trois = new Stand("ETAT-S3", "Stand état trois", Set.of(), 1, 1, false);
        Creneau creneau = new Creneau(CRENEAU_ID, 1, JOUR, LocalTime.of(14, 0), LocalTime.of(16, 0));
        PosteAffectation p1 = new PosteAffectation("ETAT-P1", un, creneau);
        p1.setAnimateur(echange ? bruno : alice);
        PosteAffectation p2 = new PosteAffectation("ETAT-P2", deux, creneau);
        p2.setAnimateur(echange ? alice : bruno);
        PosteAffectation p3 = new PosteAffectation("ETAT-P3", trois, creneau);
        p3.setAnimateur(chloe);
        persistence.persist(new PlanningEvenement(JOUR, List.of(alice, bruno, chloe), List.of(p1, p2, p3)));
    }

    private void donnerEmail(String animateurId, String email) {
        Animateur animateur = referenceData.listAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals(animateurId))
                .findFirst()
                .orElseThrow();
        animateur.setEmail(email);
        referenceData.updateAnimateur(animateurId, animateur);
    }
}
