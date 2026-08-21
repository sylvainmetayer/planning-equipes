package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.PlanningPersistenceService;
import dev.sylvain.planning.service.ReferenceDataService;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;

/**
 * Débit des demandes de code d'accès : le lien seul ne doit pas suffire à
 * déclencher un envoi de mail en boucle vers la boîte de l'animateur.
 *
 * <p>Le profil abaisse le plafond à deux demandes pour que le test tienne en
 * quelques requêtes ; c'est le même compteur qu'en production. Le compteur
 * vivant en mémoire pour toute la durée de l'application, chaque test a son
 * propre animateur plutôt qu'un compteur remis à zéro entre deux.</p>
 */
@QuarkusTest
@TestProfile(LimiteDemandesCodeTest.Profil.class)
class LimiteDemandesCodeTest {

    public static class Profil implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("planning.espace.code.max-demandes", "2",
                    "planning.espace.code.fenetre", "PT10M");
        }
    }

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 14);
    private static final String PLAFOND = "DEBIT-PLAFOND";
    private static final String CREDIT = "DEBIT-CREDIT";
    private static final String EMAIL_PLAFOND = "debit-plafond@example.org";
    private static final String EMAIL_CREDIT = "debit-credit@example.org";

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    ReferenceDataService referenceData;

    @Inject
    MockMailbox mailbox;

    @BeforeEach
    void seed() {
        mailbox.clear();
        Animateur plafond = new Animateur(PLAFOND, "Carla", "Roux", LocalDate.of(1991, 3, 3), false);
        Animateur credit = new Animateur(CREDIT, "Diego", "Lima", LocalDate.of(1989, 4, 4), false);
        Stand stand = new Stand("DEBIT-S1", "Stand débit", Set.of(), 1, 2, false);
        Creneau creneau = new Creneau(9601L, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(12, 0));
        PosteAffectation premier = new PosteAffectation("DEBIT-P1", stand, creneau);
        premier.setAnimateur(plafond);
        PosteAffectation second = new PosteAffectation("DEBIT-P2", stand, creneau);
        second.setAnimateur(credit);
        persistence.persist(new PlanningFestival(JOUR, List.of(plafond, credit), List.of(premier, second)));
        donnerEmail(PLAFOND, EMAIL_PLAFOND);
        donnerEmail(CREDIT, EMAIL_CREDIT);
    }

    @Test
    void auDelaDuPlafondLesDemandesDeCodeSontRefusees() {
        String jeton = jetonDe(PLAFOND);
        demanderCode(jeton).then().statusCode(200);
        demanderCode(jeton).then().statusCode(200);

        demanderCode(jeton).then()
                .statusCode(429)
                .header("Retry-After", notNullValue())
                .body("message", containsString("Trop de codes"));

        // Aucun mail de plus n'est parti : c'est tout l'objet du plafond.
        assertThat(mailbox.getMailsSentTo(EMAIL_PLAFOND)).hasSize(2);
    }

    /**
     * Ce qui est compté, ce sont les codes jamais utilisés : ouvrir la session
     * efface le compteur, sinon un animateur qui se connecte régulièrement
     * finirait par se voir refuser l'accès à son propre planning.
     */
    @Test
    void ouvrirLaSessionRendSonCreditAuCompteur() {
        String jeton = jetonDe(CREDIT);
        demanderCode(jeton).then().statusCode(200);
        demanderCode(jeton).then().statusCode(200);

        given().contentType(ContentType.JSON)
                .body("{\"code\":\"" + dernierCode(EMAIL_CREDIT) + "\"}")
                .when().post("/api/espace-animateur/" + jeton + "/session")
                .then().statusCode(204);

        demanderCode(jeton).then().statusCode(200);
    }

    private static Response demanderCode(String jeton) {
        return given().contentType(ContentType.JSON)
                .when().post("/api/espace-animateur/" + jeton + "/code");
    }

    private String dernierCode(String email) {
        List<Mail> mails = mailbox.getMailsSentTo(email);
        assertThat(mails).isNotEmpty();
        Matcher matcher = Pattern.compile("\\b(\\d{6})\\b").matcher(mails.get(mails.size() - 1).getText());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private void donnerEmail(String animateurId, String email) {
        Animateur fiche = ficheDe(animateurId);
        fiche.setEmail(email);
        referenceData.updateAnimateur(animateurId, fiche);
    }

    private String jetonDe(String animateurId) {
        return ficheDe(animateurId).getJetonAcces();
    }

    private Animateur ficheDe(String animateurId) {
        return referenceData.listAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals(animateurId))
                .findFirst()
                .orElseThrow();
    }
}
