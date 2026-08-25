package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.PlanningPersistenceService;
import dev.sylvain.planning.service.ReferenceDataService;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;

/**
 * Publishing (issue #245) against the real database and the mock mailbox: the
 * count is per person, the mails go to those people only, and a change that
 * moves nobody sends nothing at all.
 */
@QuarkusTest
class PublicationResourceTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 11);
    private static final long CRENEAU_ID = 9301L;
    private static final String EMAIL_ALICE = "alice-pub@example.org";
    private static final String EMAIL_CHLOE = "chloe-pub@example.org";

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    ReferenceDataService referenceData;

    @Inject
    MockMailbox mailbox;

    @Inject
    DataSource dataSource;

    /**
     * Alice holds a seat and has an address, Bruno holds one without an
     * address, Chloé has an address and no seat: the three cases a publication
     * has to tell apart.
     */
    @BeforeEach
    void seed() {
        mailbox.clear();
        oublierLesPublications();
        persistence.clearDatabase();
        persisterPlan("MAIL-A", "MAIL-B");
        donnerEmail("PUB-A", EMAIL_ALICE);
        donnerEmail("PUB-B", null);
        donnerEmail("PUB-C", EMAIL_CHLOE);
    }

    /**
     * A published snapshot survives {@code clearDatabase()} — that is what
     * snapshots are for — and refuses to be deleted through the API. The suite
     * shares one database, so it is cleared here rather than left to leak into
     * the next test class as a plan somebody was supposedly sent.
     */
    @AfterEach
    void nettoyer() {
        oublierLesPublications();
    }

    private void oublierLesPublications() {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM plan_snapshot");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clear the plan snapshots", e);
        }
    }

    /* ------------------------------- Nominal ------------------------------- */

    @Test
    void avantToutePublicationToutLeMondeEstConcerne() {
        JsonPath apercu = apercu();

        assertThat(apercu.getBoolean("jamaisPublie")).isTrue();
        assertThat(apercu.getObject("dernierePublicationLe", Object.class)).isNull();
        assertThat(apercu.getInt("nombreConcernes")).isEqualTo(2);
        assertThat(apercu.getList("destinataires.nomAffiche"))
                .containsExactly("Alice Martin", "Bruno Petit");
        assertThat(apercu.getList("destinataires.premiereDiffusion")).containsOnly(true);
    }

    @Test
    void laPremierePublicationEnvoieLePlanningEtRetientSaDate() {
        JsonPath rapport = publier();

        assertThat(rapport.getInt("envoyes")).isEqualTo(1);
        assertThat(rapport.getList("sansEmail")).containsExactly("Bruno Petit");
        assertThat(rapport.getList("echecs")).isEmpty();
        assertThat(rapport.getString("publieLe")).isNotBlank();

        List<Mail> mails = mailbox.getMailsSentTo(EMAIL_ALICE);
        assertThat(mails).hasSize(1);
        assertThat(mails.get(0).getSubject()).contains("votre planning individuel");
        assertThat(mails.get(0).getAttachments()).hasSize(1);
        // Chloé holds no seat: publishing must leave her alone.
        assertThat(mailbox.getMailsSentTo(EMAIL_CHLOE)).isEmpty();

        assertThat(apercu().getString("dernierePublicationLe")).isNotBlank();
    }

    @Test
    void unSiegeQuiChangeDeMainNeReveilleQueLesDeuxPersonnesConcernees() {
        publier();
        mailbox.clear();

        // Le siège de Bruno passe à Chloé : Alice ne bouge pas.
        assertThat(persistence.reaffecterPoste("PUB-P2", "PUB-C")).isTrue();

        JsonPath apercu = apercu();
        assertThat(apercu.getInt("nombreConcernes")).isEqualTo(2);
        assertThat(apercu.getList("destinataires.nomAffiche"))
                .containsExactly("Bruno Petit", "Chloé Durand");

        JsonPath rapport = publier();
        assertThat(rapport.getInt("envoyes")).isEqualTo(1);
        assertThat(rapport.getList("sansEmail")).containsExactly("Bruno Petit");
        assertThat(mailbox.getMailsSentTo(EMAIL_ALICE)).isEmpty();

        // Chloé n'avait jamais rien reçu : pour elle ce n'est pas un
        // changement, c'est son planning.
        List<Mail> versChloe = mailbox.getMailsSentTo(EMAIL_CHLOE);
        assertThat(versChloe).hasSize(1);
        assertThat(versChloe.get(0).getSubject()).contains("votre planning individuel");

        assertThat(lignesTracees("PUB-B")).contains("samedi 11/07 : Stand pub deux 14h-16h (retiré)");
    }

    @Test
    void unDeplacementSeDitCommeUnRemplacementDansLeMail() {
        publier();
        mailbox.clear();

        // Alice quitte le stand un pour le stand deux, aux mêmes heures.
        persisterPlan("MAIL-B", "MAIL-A");

        JsonPath rapport = publier();
        assertThat(rapport.getInt("envoyes")).isEqualTo(1);

        Mail versAlice = mailbox.getMailsSentTo(EMAIL_ALICE).get(0);
        assertThat(versAlice.getSubject()).contains("votre planning a changé");
        assertThat(versAlice.getText())
                .contains("samedi 11/07 : Stand pub deux 14h-16h remplace Stand pub un 14h-16h");
    }

    @Test
    void laTraceDitQuiAEtePrevenuDeQuoiEtQuand() {
        publier();

        JsonPath trace = given().when().get("/api/planning/publication/destinataires")
                .then().statusCode(200).extract().jsonPath();

        assertThat(trace.getList("nomAffiche")).containsExactly("Alice Martin", "Bruno Petit");
        assertThat(trace.getList("statut")).containsExactly("ENVOYE", "SANS_EMAIL");
        assertThat(trace.getList("envoyeLe", String.class)).allSatisfy(date -> assertThat(date).isNotBlank());
        assertThat(trace.getList("changements[0]", String.class))
                .contains("samedi 11/07 : Stand pub un 14h-16h (nouveau)");
    }

    /* -------------------------------- Limites ------------------------------ */

    @Test
    void unePublicationQuiNeConcernePersonneEstRefusee() {
        publier();
        mailbox.clear();

        assertThat(apercu().getInt("nombreConcernes")).isZero();
        given().when().post("/api/planning/publication")
                .then()
                .statusCode(409)
                .body("message", containsString("Personne n'est concerné"));
        assertThat(mailbox.getTotalMessagesSent()).isZero();
    }

    @Test
    void resoudreANouveauSansRienDeplacerNeReveillePersonne() {
        publier();
        mailbox.clear();

        // Même plan, ré-persisté : les identifiants de poste peuvent bouger, les
        // emplois du temps non.
        persisterPlan("MAIL-A", "MAIL-B");

        assertThat(apercu().getInt("nombreConcernes")).isZero();
        assertThat(mailbox.getTotalMessagesSent()).isZero();
    }

    @Test
    void sansPlanningResoluIlNYARienAPublier() {
        persistence.clearDatabase();

        assertThat(apercu().getBoolean("planVide")).isTrue();
        given().when().post("/api/planning/publication")
                .then()
                .statusCode(409)
                .body("message", containsString("Aucun planning résolu"));
    }

    @Test
    void lInstantanePublieNePeutPasEtreSupprime() {
        long snapshotId = publier().getLong("snapshotId");

        given().when().delete("/api/planning/snapshots/" + snapshotId)
                .then()
                .statusCode(409)
                .body("message", containsString("plan publié"));
    }

    @Test
    void laTraceEstVideTantQueRienNAEtePublie() {
        given().when().get("/api/planning/publication/destinataires")
                .then()
                .statusCode(200)
                .body("size()", equalTo(0));
    }

    @Test
    void unDestinataireSansAdresseEstNommeSansFaireEchouerLaPublication() {
        JsonPath rapport = publier();

        assertThat(rapport.getList("sansEmail")).contains("Bruno Petit");
        assertThat(rapport.getList("echecs")).isEmpty();
        // Il reste dans la trace : « prévenu » et « à prévenir » ne doivent pas
        // se confondre au prochain aperçu.
        assertThat(apercu().getInt("nombreConcernes")).isZero();
    }

    /* -------------------------------- Helpers ------------------------------ */

    private JsonPath apercu() {
        return given().when().get("/api/planning/publication")
                .then().statusCode(200).extract().jsonPath();
    }

    private JsonPath publier() {
        return given().contentType(ContentType.JSON)
                .when().post("/api/planning/publication")
                .then().statusCode(200).extract().jsonPath();
    }

    /** Persists the two seats, {@code standUn} first, held by the given animateurs. */
    private void persisterPlan(String surStandUn, String surStandDeux) {
        Animateur alice = new Animateur("PUB-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur bruno = new Animateur("PUB-B", "Bruno", "Petit", LocalDate.of(1992, 2, 2), false);
        Animateur chloe = new Animateur("PUB-C", "Chloé", "Durand", LocalDate.of(1995, 3, 3), false);
        Stand standUn = new Stand("PUB-S1", "Stand pub un", Set.of(), 1, 1, false);
        Stand standDeux = new Stand("PUB-S2", "Stand pub deux", Set.of(), 1, 1, false);
        Creneau creneau = new Creneau(CRENEAU_ID, 1, JOUR, LocalTime.of(14, 0), LocalTime.of(16, 0));
        PosteAffectation posteUn = new PosteAffectation("PUB-P1", standUn, creneau);
        posteUn.setAnimateur("MAIL-A".equals(surStandUn) ? alice : bruno);
        PosteAffectation posteDeux = new PosteAffectation("PUB-P2", standDeux, creneau);
        posteDeux.setAnimateur("MAIL-A".equals(surStandDeux) ? alice : bruno);
        persistence.persist(new PlanningEvenement(JOUR, List.of(alice, bruno, chloe),
                List.of(posteUn, posteDeux)));
    }

    /** The sentences the last publication recorded for one animateur. */
    private List<String> lignesTracees(String animateurId) {
        JsonPath trace = given().when().get("/api/planning/publication/destinataires")
                .then().statusCode(200).extract().jsonPath();
        List<String> ids = trace.getList("animateurId", String.class);
        int index = ids.indexOf(animateurId);
        assertThat(index).as("animateur %s absent de la trace", animateurId).isNotNegative();
        return trace.getList("changements[" + index + "]", String.class);
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
