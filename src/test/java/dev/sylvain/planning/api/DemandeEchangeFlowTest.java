package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.service.publication.PlanPublicationService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end foire au planning (issue #165) against the real PostgreSQL
 * container: a persisted two-seat planning, the espace animateur reached by
 * the database-generated token, a submitted demande with its prevalidation,
 * the fresh admin impact, and an acceptation that applies the swap and pins
 * both animateurs on the créneau.
 */
@QuarkusTest
class DemandeEchangeFlowTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 10);
    private static final long CRENEAU_ID = 9101L;
    /** A seat the day after, the fixture of the « je prends ton mardi » family. */
    private static final long CRENEAU_AUTRE_JOUR = 9103L;

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    PlanPublicationService publication;

    @Inject
    ReferenceDataService referenceData;

    @Inject
    MockMailbox mailbox;

    @Inject
    dev.sylvain.planning.service.espace.DemandeEchangeService demandeEchangeService;

    /** Espace sessions of the two seeded animateurs (e-mail code flow). */
    private String sessionAlice;

    private String sessionBruno;

    @AfterEach
    void resetSpecification() {
        RestAssured.requestSpecification = null;
    }

    @Test
    void unEchangeCroiseEstSoumisPuisAccepteEtApplique() {
        persistTwoSeatPlanning();
        String token = tokenOf("ECH-A");

        given().when()
                .get("/api/espace-animateur/" + token)
                .then()
                .statusCode(200)
                .body("animateurId", equalTo("ECH-A"))
                .body("postes.size()", greaterThanOrEqualTo(1))
                .body("collegues.find { it.id == 'ECH-B' }.nomComplet", equalTo("Bruno Petit"));

        String demandeId = given().contentType(ContentType.JSON)
                .body("[{\"creneauId\":" + CRENEAU_ID + ",\"standId\":\"ECH-S1\","
                        + "\"cibleId\":\"ECH-B\",\"motif\":\"rendez-vous médical\"}]")
                .when()
                .post("/api/espace-animateur/" + token + "/demandes")
                .then()
                .statusCode(200)
                .body("[0].statut", equalTo("EN_ATTENTE_CIBLE"))
                .body("[0].prevalidationOk", notNullValue())
                .body("[0].cibleNom", equalTo("Bruno Petit"))
                .extract()
                .path("[0].id");

        // The admin cannot accept while the colleague has not agreed.
        given().contentType(ContentType.JSON)
                .body("{}")
                .when()
                .post("/api/echanges/" + demandeId + "/acceptation")
                .then()
                .statusCode(400);

        agreementFromBruno(demandeId);

        given().when()
                .get("/api/echanges/" + demandeId + "/impact")
                .then()
                .statusCode(200)
                .body("echangeCroise", equalTo(true))
                .body("standCibleId", equalTo("ECH-S2"));

        given().contentType(ContentType.JSON)
                .body("{}")
                .when()
                .post("/api/echanges/" + demandeId + "/acceptation")
                .then()
                .statusCode(200)
                .body("statut", equalTo("ACCEPTEE"));

        // The swap was applied exactly as simulated: seats traded, nothing else.
        PlanningEvenement apres = persistence.loadPersistedPlanning();
        assertThat(occupant(apres, "ECH-S1")).isEqualTo("ECH-B");
        assertThat(occupant(apres, "ECH-S2")).isEqualTo("ECH-A");

        // Both animateurs are pinned on the créneau by ANIMATEUR_CRENEAU locks.
        assertThat(referenceData.listVerrouillages())
                .filteredOn(v -> v.getType() == TypeVerrouillage.ANIMATEUR_CRENEAU
                        && v.getCreneauId() != null
                        && v.getCreneauId() == CRENEAU_ID)
                .extracting(v -> v.getAnimateurId())
                .contains("ECH-A", "ECH-B");

        // A decided demande can no longer be refused (or re-accepted).
        given().contentType(ContentType.JSON)
                .body("{\"commentaire\":\"non\"}")
                .when()
                .post("/api/echanges/" + demandeId + "/refus")
                .then()
                .statusCode(400);
    }

    @Test
    void unEchangeDirigeTroqueDeuxCreneauxDistinctsEtVerrouilleChacun() {
        persistTwoSeatPlanning();
        // A second day: Bruno also holds a seat on créneau 9102 — the one
        // Alice wants IN RETURN for her 9101 ("je te laisse mon vendredi,
        // je prends ton samedi").
        long creneauCibleId = 9102L;
        Animateur alice = persistedAnimateur("ECH-A");
        Animateur bruno = persistedAnimateur("ECH-B");
        Creneau creneauA = new Creneau(CRENEAU_ID, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(12, 0));
        Creneau creneauB = new Creneau(creneauCibleId, 2, JOUR.plusDays(1), LocalTime.of(14, 0), LocalTime.of(16, 0));
        Stand standUn = new Stand("ECH-S1", "Stand un", Set.of("STRATEGIE"), 1, 1, false);
        Stand standDeux = new Stand("ECH-S2", "Stand deux", Set.of("STRATEGIE"), 1, 1, false);
        PosteAffectation posteAlice = new PosteAffectation("ECH-P1", standUn, creneauA);
        posteAlice.setAnimateur(alice);
        PosteAffectation posteBruno = new PosteAffectation("ECH-P2", standDeux, creneauB);
        posteBruno.setAnimateur(bruno);
        persistence.persist(new PlanningEvenement(JOUR, List.of(alice, bruno), List.of(posteAlice, posteBruno)));
        PlansPublies.publier(publication);
        String token = tokenOf("ECH-A");

        // The picker's data source: Bruno's seats, slots and stands only.
        given().when()
                .get("/api/espace-animateur/" + token + "/collegues/ECH-B/postes")
                .then()
                .statusCode(200)
                .body("[0].creneauId", equalTo((int) creneauCibleId))
                .body("[0].standId", equalTo("ECH-S2"));

        // An id nobody bears answers 404, not an empty list: an empty 200 tells
        // the caller the id simply has no seat, which is a different fact and
        // one worth probing for. Bare 404, like every other unknown entity here
        // — a body would give the prober something to read.
        given().when()
                .get("/api/espace-animateur/" + token + "/collegues/ECH-INEXISTANT/postes")
                .then()
                .statusCode(404);

        String demandeId = given().contentType(ContentType.JSON)
                .body("[{\"creneauId\":" + CRENEAU_ID + ",\"standId\":\"ECH-S1\","
                        + "\"cibleId\":\"ECH-B\",\"motif\":\"je préfère être libre ce jour-là\","
                        + "\"creneauCibleId\":" + creneauCibleId + ",\"standCibleId\":\"ECH-S2\"}]")
                .when()
                .post("/api/espace-animateur/" + token + "/demandes")
                .then()
                .statusCode(200)
                .body("[0].statut", equalTo("EN_ATTENTE_CIBLE"))
                .body("[0].creneauCibleId", equalTo((int) creneauCibleId))
                .body("[0].standCibleNom", equalTo("Stand deux"))
                .extract()
                .path("[0].id");

        agreementFromBruno(demandeId);

        given().contentType(ContentType.JSON)
                .body("{}")
                .when()
                .post("/api/echanges/" + demandeId + "/acceptation")
                .then()
                .statusCode(200)
                .body("statut", equalTo("ACCEPTEE"));

        // The seats really crossed the two créneaux.
        PlanningEvenement apres = persistence.loadPersistedPlanning();
        assertThat(occupantSur(apres, "ECH-S1", CRENEAU_ID)).isEqualTo("ECH-B");
        assertThat(occupantSur(apres, "ECH-S2", creneauCibleId)).isEqualTo("ECH-A");

        // Each animateur is pinned on the créneau they now hold: the target on
        // the demandeur's, the demandeur on the target's.
        assertThat(referenceData.listVerrouillages())
                .filteredOn(v -> v.getType() == TypeVerrouillage.ANIMATEUR_CRENEAU)
                .extracting(v -> v.getAnimateurId() + "@" + v.getCreneauId())
                .contains("ECH-B@" + CRENEAU_ID, "ECH-A@" + creneauCibleId);
    }

    /**
     * « Qui peut me remplacer ? »: the animateur names only THEIR OWN seat and
     * the assistant answers with the three things an échange can do for them —
     * Denis is free that day and takes the seat outright (LIBERE); Bruno works
     * the other stand of that same créneau, so the two would only swap stands
     * (CROISE); and Denis also holds a seat the day after, which Alice can take
     * in return (DIRIGE). Chloé declared the day off: infeasible, never
     * proposed.
     */
    @Test
    void lAssistantProposeLesTroisFacadesDUnEchange() {
        persistTwoSeatPlanningWithSpareColleague();
        String token = tokenOf("ECH-A");

        List<Map<String, Object>> suggestions = given().when()
                .get("/api/espace-animateur/" + token + "/suggestions-echange" + "?creneauId=" + CRENEAU_ID
                        + "&standId=ECH-S1&plafond=100")
                .then()
                .statusCode(200)
                .body("creneauId", equalTo((int) CRENEAU_ID))
                .body("standId", equalTo("ECH-S1"))
                .extract()
                .jsonPath()
                .getList("suggestions");

        assertThat(suggestions)
                .extracting(suggestion -> suggestion.get("animateurId"))
                .doesNotContain("ECH-C", "ECH-A");

        // Freed: nothing comes back, the créneau simply leaves Alice's hands.
        Map<String, Object> libere = suggestionOf(suggestions, "ECH-D", "LIBERE");
        assertThat(libere.get("nomComplet")).isEqualTo("Denis Roux");
        assertThat(libere.get("standCibleNom")).isNull();
        assertThat(libere.get("creneauCibleId")).isNull();

        // Croisé: Alice stays on duty that hour, on stand deux.
        Map<String, Object> croise = suggestionOf(suggestions, "ECH-B", "CROISE");
        assertThat(croise.get("standCibleNom")).isEqualTo("Stand deux");
        assertThat(croise.get("creneauCibleId")).isNull();

        // Dirigé: a seat on ANOTHER day comes back, dated and named — this is
        // what makes the button an exchange assistant and not a hand-over one.
        Map<String, Object> dirige = suggestionOf(suggestions, "ECH-D", "DIRIGE");
        assertThat(dirige.get("creneauCibleId")).isEqualTo((int) CRENEAU_AUTRE_JOUR);
        assertThat(dirige.get("dateCible")).isEqualTo(JOUR.plusDays(1).toString());
        assertThat(dirige.get("standCibleNom")).isEqualTo("Stand deux");

        // Being freed is listed before the trades that keep Alice at work.
        assertThat(rankOf(suggestions, "ECH-D", "LIBERE")).isLessThan(rankOf(suggestions, "ECH-B", "CROISE"));

        // Each family submits as is through the ordinary form: the plain one
        // as a bare seat, the directed one carrying the seat wanted in return.
        given().contentType(ContentType.JSON)
                .body("[{\"creneauId\":" + CRENEAU_ID + ",\"standId\":\"ECH-S1\",\"cibleId\":\"ECH-D\"}]")
                .when()
                .post("/api/espace-animateur/" + token + "/demandes")
                .then()
                .statusCode(200)
                .body("[0].prevalidationOk", equalTo(true));
        given().contentType(ContentType.JSON)
                .body("[{\"creneauId\":" + CRENEAU_ID + ",\"standId\":\"ECH-S1\",\"cibleId\":\"ECH-D\","
                        + "\"creneauCibleId\":" + CRENEAU_AUTRE_JOUR + ",\"standCibleId\":\"ECH-S2\"}]")
                .when()
                .post("/api/espace-animateur/" + token + "/demandes")
                .then()
                .statusCode(200)
                .body("[0].prevalidationOk", equalTo(true))
                .body("[0].creneauCibleId", equalTo((int) CRENEAU_AUTRE_JOUR));
    }

    /** Only one's own seats are searchable — Bruno's answers 400. */
    @Test
    void lAssistantRefuseUnSiegeQuiNEstPasLeMien() {
        persistTwoSeatPlanning();
        String token = tokenOf("ECH-A");

        given().when()
                .get("/api/espace-animateur/" + token + "/suggestions-echange" + "?creneauId=" + CRENEAU_ID
                        + "&standId=ECH-S2")
                .then()
                .statusCode(400);

        // A missing créneau or stand is refused the same way.
        given().when()
                .get("/api/espace-animateur/" + token + "/suggestions-echange?standId=ECH-S1")
                .then()
                .statusCode(400);
    }

    @Test
    void unJetonInconnuRepondIntrouvable() {
        given().when()
                .get("/api/espace-animateur/jeton-invente")
                .then()
                .statusCode(404)
                .body("message", notNullValue());
    }

    @Test
    void laCibleDeclineEtLAdminNArbitreJamais() {
        persistTwoSeatPlanning();
        String token = tokenOf("ECH-A");

        String demandeId = given().contentType(ContentType.JSON)
                .body("[{\"creneauId\":" + CRENEAU_ID + ",\"standId\":\"ECH-S1\",\"cibleId\":\"ECH-B\"}]")
                .when()
                .post("/api/espace-animateur/" + token + "/demandes")
                .then()
                .statusCode(200)
                .extract()
                .path("[0].id");

        // Bruno sees it among his received demandes, and declines.
        given().cookie("planning-espace", sessionBruno)
                .when()
                .get("/api/espace-animateur/" + tokenOf("ECH-B") + "/demandes-recues")
                .then()
                .statusCode(200)
                .body("find { it.id == '" + demandeId + "' }.statut", equalTo("EN_ATTENTE_CIBLE"));
        given().cookie("planning-espace", sessionBruno)
                .contentType(ContentType.JSON)
                .when()
                .post("/api/espace-animateur/" + tokenOf("ECH-B") + "/demandes-recues/" + demandeId + "/refus")
                .then()
                .statusCode(200)
                .body("statut", equalTo("REFUSEE_CIBLE"));

        // Terminal: neither the admin nor a second answer can touch it.
        given().contentType(ContentType.JSON)
                .body("{}")
                .when()
                .post("/api/echanges/" + demandeId + "/acceptation")
                .then()
                .statusCode(400);
        given().cookie("planning-espace", sessionBruno)
                .contentType(ContentType.JSON)
                .when()
                .post("/api/espace-animateur/" + tokenOf("ECH-B") + "/demandes-recues/" + demandeId + "/accord")
                .then()
                .statusCode(400);

        // And only the targeted colleague may answer: Alice cannot agree in
        // Bruno's stead on a fresh demande.
        String autreDemande = given().contentType(ContentType.JSON)
                .body("[{\"creneauId\":" + CRENEAU_ID + ",\"standId\":\"ECH-S1\",\"cibleId\":\"ECH-B\"}]")
                .when()
                .post("/api/espace-animateur/" + token + "/demandes")
                .then()
                .statusCode(200)
                .extract()
                .path("[0].id");
        given().contentType(ContentType.JSON)
                .when()
                .post("/api/espace-animateur/" + token + "/demandes-recues/" + autreDemande + "/accord")
                .then()
                .statusCode(400);
    }

    /**
     * A batch spread over two colleagues solicits BOTH of them. The mail used
     * to go to {@code nouvelles.get(0)} alone, so everyone but the first named
     * colleague waited for an agreement they were never asked for — and the
     * demande stayed stuck in EN_ATTENTE_CIBLE.
     */
    @Test
    void unLotVisantDeuxColleguesLesSollicteTousLesDeux() {
        persistTwoSeatPlanning();
        donnerEmail("ECH-C", "ech-chloe@example.org");
        String token = tokenOf("ECH-A");
        mailbox.clear();

        // Alice offers her single seat to Bruno and to Chloé: two demandes,
        // two different targets, one submission.
        given().contentType(ContentType.JSON)
                .body("[{\"creneauId\":" + CRENEAU_ID + ",\"standId\":\"ECH-S1\",\"cibleId\":\"ECH-B\"},"
                        + "{\"creneauId\":" + CRENEAU_ID + ",\"standId\":\"ECH-S1\",\"cibleId\":\"ECH-C\"}]")
                .when()
                .post("/api/espace-animateur/" + token + "/demandes")
                .then()
                .statusCode(200)
                .body("size()", equalTo(2));

        assertThat(mailbox.getMailsSentTo("ech-bruno@example.org")).hasSize(1);
        assertThat(mailbox.getMailsSentTo("ech-chloe@example.org")).hasSize(1);
        // Each of them is told about THEIR demande only, not about the batch:
        // the count is per colleague, so nobody learns what was proposed to
        // someone else.
        assertThat(mailbox.getMailsSentTo("ech-chloe@example.org").get(0).getText())
                .contains("un échange de créneau")
                .doesNotContain("2 échanges");
    }

    /** Two seats offered to the same colleague stay one mail — announcing both. */
    @Test
    void deuxDemandesPourLeMemeCollegueTiennentEnUnSeulMail() {
        persistTwoSeatPlanning();
        String token = tokenOf("ECH-A");
        mailbox.clear();

        given().contentType(ContentType.JSON)
                .body("[{\"creneauId\":" + CRENEAU_ID + ",\"standId\":\"ECH-S1\",\"cibleId\":\"ECH-B\","
                        + "\"motif\":\"le matin\"},"
                        + "{\"creneauId\":" + CRENEAU_ID + ",\"standId\":\"ECH-S1\",\"cibleId\":\"ECH-B\","
                        + "\"motif\":\"ou alors l'après-midi\"}]")
                .when()
                .post("/api/espace-animateur/" + token + "/demandes")
                .then()
                .statusCode(200)
                .body("size()", equalTo(2));

        List<Mail> mails = mailbox.getMailsSentTo("ech-bruno@example.org");
        assertThat(mails).hasSize(1);
        assertThat(mails.get(0).getText()).contains("2 échanges de créneaux");
    }

    /** Bruno (the target) agrees: the demande enters the admin queue. */
    private void agreementFromBruno(String demandeId) {
        given().cookie("planning-espace", sessionBruno)
                .contentType(ContentType.JSON)
                .when()
                .post("/api/espace-animateur/" + tokenOf("ECH-B") + "/demandes-recues/" + demandeId + "/accord")
                .then()
                .statusCode(200)
                .body("statut", equalTo("PROPOSEE"));
    }

    @Test
    void leDemandeurPeutAnnulerUneDemandeEnAttente() {
        persistTwoSeatPlanning();
        String token = tokenOf("ECH-A");

        String demandeId = given().contentType(ContentType.JSON)
                .body("[{\"creneauId\":" + CRENEAU_ID + ",\"standId\":\"ECH-S1\",\"cibleId\":\"ECH-B\"}]")
                .when()
                .post("/api/espace-animateur/" + token + "/demandes")
                .then()
                .statusCode(200)
                .extract()
                .path("[0].id");

        given().contentType(ContentType.JSON)
                .when()
                .post("/api/espace-animateur/" + token + "/demandes/" + demandeId + "/annulation")
                .then()
                .statusCode(204);

        given().when()
                .get("/api/espace-animateur/" + token + "/demandes")
                .then()
                .statusCode(200)
                .body("find { it.id == '" + demandeId + "' }.statut", equalTo("ANNULEE"));

        // An already-cancelled demande cannot be decided.
        given().contentType(ContentType.JSON)
                .body("{}")
                .when()
                .post("/api/echanges/" + demandeId + "/acceptation")
                .then()
                .statusCode(400);
    }

    /**
     * Issue #540: an annulation is the demandeur's own withdrawal, not a
     * decision of the organisation. Nothing is written to them about it, and it
     * does not make them a recipient of a publication that had nothing else to
     * say — « votre demande a été refusée » is what they used to read after
     * withdrawing it themselves.
     */
    @Test
    void uneDemandeAnnuleeParSonAuteurNeLuiVautAucunMessageDePublication() {
        persistTwoSeatPlanning();
        String token = tokenOf("ECH-A");

        String demandeId = given().contentType(ContentType.JSON)
                .body("[{\"creneauId\":" + CRENEAU_ID + ",\"standId\":\"ECH-S1\",\"cibleId\":\"ECH-B\"}]")
                .when()
                .post("/api/espace-animateur/" + token + "/demandes")
                .then()
                .statusCode(200)
                .extract()
                .path("[0].id");

        given().contentType(ContentType.JSON)
                .when()
                .post("/api/espace-animateur/" + token + "/demandes/" + demandeId + "/annulation")
                .then()
                .statusCode(204);

        // Nothing but the annulation has happened since the fixture published,
        // so the publication has nothing to announce at all.
        mailbox.clear();
        PlansPublies.publier(publication);

        assertThat(mailbox.getMailsSentTo("ech-alice@example.org")).isEmpty();

        // And it does not stay in the queue for ever: the withdrawal stamps
        // annule_le, never decide_le, so it never enters it.
        assertThat(demandeEchangeService.decisionsNonCommuniquees())
                .extracting(dev.sylvain.planning.domain.DemandeEchange::getId)
                .doesNotContain(demandeId);
    }

    /** An empty batch is a no-op, not an error: nothing stored, no mail. */
    @Test
    void unLotVideEstAccepteSansRienEnregistrer() {
        persistTwoSeatPlanning();
        String token = tokenOf("ECH-A");

        given().contentType(ContentType.JSON)
                .body("[]")
                .when()
                .post("/api/espace-animateur/" + token + "/demandes")
                .then()
                .statusCode(200)
                .body("size()", equalTo(0));
    }

    /**
     * A batch is atomic: one invalid demande (self-swap, unknown target, seat
     * not the demandeur's) rejects the whole batch, including its valid lines.
     */
    @Test
    void unLotContenantUneDemandeInvalideEstRejeteEnBloc() {
        persistTwoSeatPlanning();
        String token = tokenOf("ECH-A");
        String marqueur = "lot-atomique-" + System.nanoTime();

        // Valid first line, self-swap second line.
        given().contentType(ContentType.JSON)
                .body("[{\"creneauId\":" + CRENEAU_ID + ",\"standId\":\"ECH-S1\","
                        + "\"cibleId\":\"ECH-B\",\"motif\":\"" + marqueur + "\"},"
                        + "{\"creneauId\":" + CRENEAU_ID + ",\"standId\":\"ECH-S1\",\"cibleId\":\"ECH-A\"}]")
                .when()
                .post("/api/espace-animateur/" + token + "/demandes")
                .then()
                .statusCode(400)
                .body("message", equalTo("Impossible d'échanger un créneau avec soi-même"));

        // Unknown target.
        given().contentType(ContentType.JSON)
                .body("[{\"creneauId\":" + CRENEAU_ID + ",\"standId\":\"ECH-S1\",\"cibleId\":\"ECH-FANTOME\"}]")
                .when()
                .post("/api/espace-animateur/" + token + "/demandes")
                .then()
                .statusCode(400);

        // The seat on S2 is Bruno's, not Alice's.
        given().contentType(ContentType.JSON)
                .body("[{\"creneauId\":" + CRENEAU_ID + ",\"standId\":\"ECH-S2\",\"cibleId\":\"ECH-B\"}]")
                .when()
                .post("/api/espace-animateur/" + token + "/demandes")
                .then()
                .statusCode(400);

        // Not even the valid line of the mixed batch was stored.
        given().when()
                .get("/api/espace-animateur/" + token + "/demandes")
                .then()
                .statusCode(200)
                .body("find { it.motif == '" + marqueur + "' }", org.hamcrest.Matchers.nullValue());
    }

    /**
     * The prevalidation flags a demande towards a colleague who declared the
     * day off: stored anyway (the admin decides), but marked infeasible with
     * the business description of the broken constraint.
     */
    @Test
    void laPrevalidationSignaleUneCibleIndisponibleCeJourLa() {
        persistTwoSeatPlanning();
        String token = tokenOf("ECH-A");

        String demandeId = given().contentType(ContentType.JSON)
                .body("[{\"creneauId\":" + CRENEAU_ID + ",\"standId\":\"ECH-S1\",\"cibleId\":\"ECH-C\"}]")
                .when()
                .post("/api/espace-animateur/" + token + "/demandes")
                .then()
                .statusCode(200)
                .body("[0].prevalidationOk", equalTo(false))
                .body("[0].contraintesViolees.size()", greaterThanOrEqualTo(1))
                .body("[0].contraintesViolees[0]", org.hamcrest.Matchers.containsString("indisponible"))
                .extract()
                .path("[0].id");

        // Chloé holds no seat on the créneau: the impact is a simple takeover.
        given().when()
                .get("/api/echanges/" + demandeId + "/impact")
                .then()
                .statusCode(200)
                .body("echangeCroise", equalTo(false))
                .body("casseContrainteDure", equalTo(true));
    }

    /** The refusal comment travels to the animateur's own espace, verbatim. */
    @Test
    void unRefusTransmetSonCommentaireALAnimateur() {
        persistTwoSeatPlanning();
        String token = tokenOf("ECH-A");

        String demandeId = given().contentType(ContentType.JSON)
                .body("[{\"creneauId\":" + CRENEAU_ID + ",\"standId\":\"ECH-S1\",\"cibleId\":\"ECH-B\"}]")
                .when()
                .post("/api/espace-animateur/" + token + "/demandes")
                .then()
                .statusCode(200)
                .extract()
                .path("[0].id");

        given().contentType(ContentType.JSON)
                .body("{\"commentaire\":\"Le repos de Bruno serait cassé\"}")
                .when()
                .post("/api/echanges/" + demandeId + "/refus")
                .then()
                .statusCode(200)
                .body("statut", equalTo("REFUSEE"))
                .body("commentaireAdmin", equalTo("Le repos de Bruno serait cassé"));

        given().when()
                .get("/api/espace-animateur/" + token + "/demandes")
                .then()
                .statusCode(200)
                .body("find { it.id == '" + demandeId + "' }.statut", equalTo("REFUSEE"))
                .body(
                        "find { it.id == '" + demandeId + "' }.commentaireAdmin",
                        equalTo("Le repos de Bruno serait cassé"));

        // The planning was not touched by the refusal.
        PlanningEvenement apres = persistence.loadPersistedPlanning();
        assertThat(occupant(apres, "ECH-S1")).isEqualTo("ECH-A");
        assertThat(occupant(apres, "ECH-S2")).isEqualTo("ECH-B");
    }

    /** Only the demandeur can withdraw their demande — another token is a 400. */
    @Test
    void unAutreAnimateurNePeutPasAnnulerLaDemande() {
        persistTwoSeatPlanning();
        String aliceToken = tokenOf("ECH-A");
        String brunoToken = tokenOf("ECH-B");

        String demandeId = given().contentType(ContentType.JSON)
                .body("[{\"creneauId\":" + CRENEAU_ID + ",\"standId\":\"ECH-S1\",\"cibleId\":\"ECH-B\"}]")
                .when()
                .post("/api/espace-animateur/" + aliceToken + "/demandes")
                .then()
                .statusCode(200)
                .extract()
                .path("[0].id");

        // Bruno acts with his OWN session on his own token: the 400 is the
        // ownership rule, not a session mismatch.
        RestAssured.requestSpecification = null;
        given().contentType(ContentType.JSON)
                .cookie("planning-espace", sessionBruno)
                .when()
                .post("/api/espace-animateur/" + brunoToken + "/demandes/" + demandeId + "/annulation")
                .then()
                .statusCode(400);
        RestAssured.requestSpecification = new RequestSpecBuilder()
                .addCookie("planning-espace", sessionAlice)
                .build();

        given().when()
                .get("/api/espace-animateur/" + aliceToken + "/demandes")
                .then()
                .statusCode(200)
                .body("find { it.id == '" + demandeId + "' }.statut", equalTo("EN_ATTENTE_CIBLE"));
    }

    /**
     * Rotating the token (issue #165: the only way it ever changes) kills the
     * old link immediately; an ordinary fiche update never rotates it.
     */
    @Test
    void laRegenerationDuJetonInvalideLAncienLienSeulement() {
        persistTwoSeatPlanning();
        String oldToken = tokenOf("ECH-A");

        // An ordinary update of the fiche keeps the token stable.
        given().contentType(ContentType.JSON)
                .body("{\"id\":\"ECH-A\",\"prenom\":\"Alice\",\"nom\":\"Martin\","
                        + "\"dateNaissance\":\"1990-01-01\",\"email\":\"alice@example.org\"}")
                .when()
                .put("/api/animateurs/ECH-A")
                .then()
                .statusCode(200);
        assertThat(tokenOf("ECH-A")).isEqualTo(oldToken);

        String newToken = given().contentType(ContentType.JSON)
                .when()
                .post("/api/animateurs/ECH-A/token")
                .then()
                .statusCode(200)
                .extract()
                .path("token");
        assertThat(newToken).isNotBlank().isNotEqualTo(oldToken);

        given().when().get("/api/espace-animateur/" + oldToken).then().statusCode(404);
        given().when()
                .get("/api/espace-animateur/" + newToken)
                .then()
                .statusCode(200)
                .body("animateurId", equalTo("ECH-A"));

        // An unknown animateur cannot get a token.
        given().contentType(ContentType.JSON)
                .when()
                .post("/api/animateurs/ECH-FANTOME/token")
                .then()
                .statusCode(404);
    }

    /**
     * Two singleton stands on the same créneau: Alice on S1, Bruno on S2.
     * Chloé is a seatless colleague who declared the day off — the fixture of
     * the prevalidation edge cases. Any ANIMATEUR_CRENEAU lock left on the
     * test animateurs by a previous test (an acceptation pins both parties) is
     * cleared first, so every test starts from a lock-free planning.
     */
    private void persistTwoSeatPlanning() {
        referenceData.listVerrouillages().stream()
                .filter(v -> v.getAnimateurId() != null && v.getAnimateurId().startsWith("ECH-"))
                .forEach(v -> referenceData.deleteVerrouillage(v.getId()));
        Animateur alice = new Animateur("ECH-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur bruno = new Animateur("ECH-B", "Bruno", "Petit", LocalDate.of(1992, 2, 2), false);
        Animateur chloe = new Animateur("ECH-C", "Chloé", "Durand", LocalDate.of(1995, 3, 3), false);
        chloe.setJoursIndisponibles(Set.of(JOUR));
        Stand standUn = new Stand("ECH-S1", "Stand un", Set.of("STRATEGIE"), 1, 1, false);
        Stand standDeux = new Stand("ECH-S2", "Stand deux", Set.of("STRATEGIE"), 1, 1, false);
        Creneau creneau = new Creneau(CRENEAU_ID, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(12, 0));
        PosteAffectation posteUn = new PosteAffectation("ECH-P1", standUn, creneau);
        posteUn.setAnimateur(alice);
        PosteAffectation posteDeux = new PosteAffectation("ECH-P2", standDeux, creneau);
        posteDeux.setAnimateur(bruno);
        persistence.persist(new PlanningEvenement(JOUR, List.of(alice, bruno, chloe), List.of(posteUn, posteDeux)));
        PlansPublies.publier(publication);

        // The espace requires an e-mail-code session since the auth follow-up:
        // both actors get an address, a session, and Alice's cookie rides on
        // every request by default (harmless on the admin routes).
        donnerEmail("ECH-A", "ech-alice@example.org");
        donnerEmail("ECH-B", "ech-bruno@example.org");
        mailbox.clear();
        RestAssured.requestSpecification = null;
        sessionAlice = EspaceSessions.open(mailbox, tokenOf("ECH-A"), "ech-alice@example.org");
        sessionBruno = EspaceSessions.open(mailbox, tokenOf("ECH-B"), "ech-bruno@example.org");
        RestAssured.requestSpecification = new RequestSpecBuilder()
                .addCookie("planning-espace", sessionAlice)
                .build();
    }

    /**
     * The two-seat planning, plus Denis: available that day and holding no
     * seat on it, so he can free Alice outright — and holding one the day
     * after, the only thing she could take in return. The fixture of the three
     * families at once.
     */
    private void persistTwoSeatPlanningWithSpareColleague() {
        persistTwoSeatPlanning();
        Animateur denis = new Animateur("ECH-D", "Denis", "Roux", LocalDate.of(1988, 4, 4), false);
        PlanningEvenement planning = persistence.loadPersistedPlanning();
        List<Animateur> animateurs = new ArrayList<>(planning.getAnimateurs());
        animateurs.removeIf(animateur -> "ECH-D".equals(animateur.getId()));
        animateurs.add(denis);
        Creneau lendemain =
                new Creneau(CRENEAU_AUTRE_JOUR, 2, JOUR.plusDays(1), LocalTime.of(14, 0), LocalTime.of(16, 0));
        Stand standDeux = new Stand("ECH-S2", "Stand deux", Set.of("STRATEGIE"), 1, 1, false);
        PosteAffectation posteDenis = new PosteAffectation("ECH-P3", standDeux, lendemain);
        posteDenis.setAnimateur(denis);
        List<PosteAffectation> postes = new ArrayList<>(planning.getPostes());
        postes.removeIf(poste -> "ECH-P3".equals(poste.getId()));
        postes.add(posteDenis);
        persistence.persist(new PlanningEvenement(JOUR, animateurs, postes));
        PlansPublies.publier(publication);
    }

    private static Map<String, Object> suggestionOf(
            List<Map<String, Object>> suggestions, String animateurId, String nature) {
        return suggestions.stream()
                .filter(suggestion ->
                        animateurId.equals(suggestion.get("animateurId")) && nature.equals(suggestion.get("nature")))
                .findFirst()
                .orElseThrow(() ->
                        new AssertionError("No " + nature + " suggestion for " + animateurId + " in " + suggestions));
    }

    private static int rankOf(List<Map<String, Object>> suggestions, String animateurId, String nature) {
        return suggestions.indexOf(suggestionOf(suggestions, animateurId, nature));
    }

    private void donnerEmail(String animateurId, String email) {
        Animateur animateur = referenceData.listAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals(animateurId))
                .findFirst()
                .orElseThrow();
        animateur.setEmail(email);
        referenceData.updateAnimateur(animateurId, animateur);
    }

    /** Occupant of the single seat of {@code standId} on the test créneau. */
    private Animateur persistedAnimateur(String id) {
        return referenceData.listAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static String occupantSur(PlanningEvenement planning, String standId, long creneauId) {
        return planning.getPostes().stream()
                .filter(poste -> poste.getStand() != null
                        && standId.equals(poste.getStand().getId())
                        && poste.getCreneau() != null
                        && poste.getCreneau().getId() != null
                        && poste.getCreneau().getId() == creneauId)
                .findFirst()
                .map(poste -> poste.getAnimateur() == null
                        ? null
                        : poste.getAnimateur().getId())
                .orElse(null);
    }

    private static String occupant(PlanningEvenement planning, String standId) {
        return planning.getPostes().stream()
                .filter(poste -> poste.getStand() != null
                        && standId.equals(poste.getStand().getId())
                        && poste.getCreneau() != null
                        && poste.getCreneau().getId() != null
                        && poste.getCreneau().getId() == CRENEAU_ID)
                .findFirst()
                .map(poste -> poste.getAnimateur() == null
                        ? null
                        : poste.getAnimateur().getId())
                .orElse(null);
    }

    /** The database-generated espace token of one seeded animateur. */
    private String tokenOf(String animateurId) {
        return referenceData.listAnimateurs().stream()
                .filter(animateur -> animateur.getId().equals(animateurId))
                .findFirst()
                .orElseThrow()
                .getAccessToken();
    }
}
