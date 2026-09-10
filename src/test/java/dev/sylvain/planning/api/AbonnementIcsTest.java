package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.edition.EditionService;
import dev.sylvain.planning.service.publication.PlanPublicationService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import dev.sylvain.planning.service.ReferenceDataService;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;

/**
 * The permanent calendar subscription (issue #324). What is proved here is
 * exactly what the feature claims and what makes it risky:
 *
 * <ul>
 *   <li>a bare, <b>repeated</b> {@code GET} carrying nothing but the token
 *       serves the calendar — no cookie, no session, ever. A test that opened
 *       a session first would prove nothing at all;</li>
 *   <li>the perimeter of that token is one document. It opens no espace, no
 *       swap request, no declaration, and it writes nothing;</li>
 *   <li>the two tokens do not substitute for one another, in either
 *       direction;</li>
 *   <li>rotating the subscription revokes the old URL and leaves the espace
 *       token alone;</li>
 *   <li>the feed follows republication, and answers an empty calendar — never
 *       a 404 — when nothing has been published yet.</li>
 * </ul>
 */
@QuarkusTest
class AbonnementIcsTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 18);
    private static final long CRENEAU_ID = 9401L;
    private static final String ANIMATEUR = "ABO-A";
    private static final String EMAIL = "abo-alice@example.org";
    /** A second, deliberately empty edition: nothing has ever been published there. */
    private static final String EDITION_VIERGE = "ABO-EDITION-VIERGE";

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    PlanPublicationService publication;

    @Inject
    ReferenceDataService referenceData;

    @Inject
    EditionService editionService;

    @Inject
    EditionContext editionContext;

    @Inject
    MockMailbox mailbox;

    @BeforeEach
    void seed() {
        persistence.persist(planning("Stand abonnement un"));
        donnerEmail();
        PlansPublies.publier(publication);
        mailbox.clear();
    }

    private PlanningEvenement planning(String nomDuStand) {
        Animateur alice = new Animateur(ANIMATEUR, "Alice", "Abonnee", LocalDate.of(1990, 1, 1), false);
        Stand stand = new Stand("ABO-S1", nomDuStand, Set.of(), 1, 1, false);
        Creneau creneau = new Creneau(CRENEAU_ID, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(12, 0));
        PosteAffectation poste = new PosteAffectation("ABO-P1", stand, creneau);
        poste.setAnimateur(alice);
        return new PlanningEvenement(JOUR, List.of(alice), List.of(poste));
    }

    private void donnerEmail() {
        Animateur animateur = referenceData.listAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals(ANIMATEUR))
                .findFirst()
                .orElseThrow();
        animateur.setEmail(EMAIL);
        referenceData.updateAnimateur(ANIMATEUR, animateur);
    }

    private String abonnementToken() {
        return referenceData.abonnementToken(ANIMATEUR);
    }

    private String accessToken() {
        return referenceData.listAnimateurs().stream()
                .filter(animateur -> animateur.getId().equals(ANIMATEUR))
                .findFirst()
                .orElseThrow()
                .getAccessToken();
    }

    /**
     * The heart of the issue: a calendar client holds no cookie and never
     * opens a session, and it comes back on its own schedule. Both calls run
     * on a request specification of their own — nothing is carried over.
     */
    @Test
    void aBareRepeatedGetServesTheCalendarWithoutAnySession() {
        String url = "/api/abonnements/" + abonnementToken() + "/planning.ics";

        for (int appel = 0; appel < 3; appel++) {
            String ics = given().when().get(url)
                    .then()
                    .statusCode(200)
                    .contentType(containsString("text/calendar"))
                    .header("Cache-Control", containsString("no-cache"))
                    .extract().asString();
            assertThat(ics).startsWith("BEGIN:VCALENDAR").contains("Stand abonnement un");
        }
    }

    /**
     * The body is pinned here because it is the one thing a calendar client
     * will display to its owner, and because it is what the guard's javadoc
     * and {@code docs/securite.md} describe: a fixed sentence that echoes
     * neither the token nor whether it ever existed.
     */
    @Test
    void anUnknownSubscriptionTokenIs404() {
        String corps = given().when().get("/api/abonnements/jeton-invente/planning.ics")
                .then().statusCode(404)
                .contentType(containsString("text/plain"))
                .extract().asString();
        assertThat(corps).isEqualTo("Abonnement inconnu ou révoqué")
                .doesNotContain("jeton-invente");
    }

    /**
     * Rotation <b>is</b> the revocation: the URL that leaked stops serving,
     * immediately and for good, and a working one takes its place.
     */
    @Test
    void rotatingTheTokenRevokesTheOldUrlAndLeavesTheEspaceTokenAlone() {
        String ancien = abonnementToken();
        String espaceAvant = accessToken();
        String session = EspaceSessions.open(mailbox, espaceAvant, EMAIL);

        String nouveau = given().cookie("planning-espace", session)
                .contentType(ContentType.JSON)
                .when().post("/api/espace-animateur/" + espaceAvant + "/abonnement")
                .then().statusCode(200)
                .extract().path("abonnementToken");

        assertThat(nouveau).isNotBlank().isNotEqualTo(ancien);
        given().when().get("/api/abonnements/" + ancien + "/planning.ics").then().statusCode(404);
        given().when().get("/api/abonnements/" + nouveau + "/planning.ics").then().statusCode(200);
        // The espace link printed on an already-distributed PDF must survive.
        assertThat(accessToken()).isEqualTo(espaceAvant);
    }

    /**
     * The subscription token opens one document. Everything else it could be
     * pasted into answers 404 — the espace routes resolve their {@code jeton}
     * against the other column entirely.
     */
    @Test
    void theSubscriptionTokenOpensNothingButTheCalendar() {
        String token = abonnementToken();

        given().when().get("/api/espace-animateur/" + token).then().statusCode(404);
        given().when().get("/api/espace-animateur/" + token + "/demandes").then().statusCode(404);
        given().when().get("/api/espace-animateur/" + token + "/demandes-recues").then().statusCode(404);
        given().when().get("/api/espace-animateur/" + token + "/disponibilites").then().statusCode(404);
        given().when().get("/api/espace-animateur/" + token + "/planning.pdf").then().statusCode(404);
        given().contentType(ContentType.JSON)
                .when().post("/api/espace-animateur/" + token + "/code")
                .then().statusCode(404);
        // And the calendar route itself is read-only: there is no write to find.
        given().contentType(ContentType.JSON)
                .when().post("/api/abonnements/" + token + "/planning.ics")
                .then().statusCode(405);
    }

    /** The espace token is not a subscription: the two columns never overlap. */
    @Test
    void theEspaceTokenDoesNotOpenTheCalendarFeed() {
        given().when().get("/api/abonnements/" + accessToken() + "/planning.ics")
                .then().statusCode(404);
    }

    /**
     * « Mon calendrier montre l'ancien planning » is the very complaint this
     * feature answers: the feed is rebuilt on each call, so a republication
     * lands at the client's next sync with nothing to resubscribe to.
     */
    @Test
    void theFeedFollowsRepublicationWithoutResubscribing() {
        String url = "/api/abonnements/" + abonnementToken() + "/planning.ics";
        given().when().get(url).then().statusCode(200)
                .body(containsString("Stand abonnement un"));

        persistence.persist(planning("Stand abonnement deux"));
        PlansPublies.publier(publication);

        given().when().get(url).then().statusCode(200)
                .body(containsString("Stand abonnement deux"));
    }

    /**
     * Nothing published yet is a normal state, not an error: a client that met
     * a 404 here would disable the feed, and its owner would have to subscribe
     * again without ever being told. A fresh edition is exactly that state.
     */
    @Test
    void anEditionThatNeverPublishedServesAnEmptyCalendar() {
        dropEditionIfPresent();
        editionService.create(new Edition(EDITION_VIERGE, "Édition sans publication", false, null));
        String token = editionContext.executeIn(EDITION_VIERGE, () -> {
            referenceData.createAnimateur(
                    new Animateur("ABO-VIDE", "Bruno", "Vierge", LocalDate.of(1991, 3, 3), false));
            return referenceData.abonnementToken("ABO-VIDE");
        });

        String ics = given().when().get("/api/abonnements/" + token + "/planning.ics")
                .then().statusCode(200)
                .extract().asString();
        assertThat(ics).startsWith("BEGIN:VCALENDAR").endsWith("END:VCALENDAR\r\n").doesNotContain("BEGIN:VEVENT");

        dropEditionIfPresent();
        // A deleted edition takes its tokens with it: the URL simply stops existing.
        given().when().get("/api/abonnements/" + token + "/planning.ics").then().statusCode(404);
    }

    /** Leftover of a previous run, or of a failure halfway through this one. */
    private void dropEditionIfPresent() {
        if (editionService.listEditions().stream()
                .anyMatch(edition -> edition.getId().equals(EDITION_VIERGE))) {
            editionService.delete(EDITION_VIERGE);
        }
    }

    /** A deleted fiche takes its subscription with it — no orphan calendar survives. */
    @Test
    void deletingTheAnimateurKillsTheSubscription() {
        String token = abonnementToken();
        given().when().get("/api/abonnements/" + token + "/planning.ics").then().statusCode(200);

        referenceData.deleteAnimateur(ANIMATEUR);

        given().when().get("/api/abonnements/" + token + "/planning.ics").then().statusCode(404);
    }
}
