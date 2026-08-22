package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeVerrouillage;
import org.junit.jupiter.api.AfterEach;

import dev.sylvain.planning.service.PlanningPersistenceService;
import dev.sylvain.planning.service.ReferenceDataService;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;

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

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    ReferenceDataService referenceData;

    @Inject
    MockMailbox mailbox;

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

        given().when().get("/api/espace-animateur/" + token)
                .then()
                .statusCode(200)
                .body("animateurId", equalTo("ECH-A"))
                .body("postes.size()", greaterThanOrEqualTo(1))
                .body("collegues.find { it.id == 'ECH-B' }.nomComplet", equalTo("Bruno Petit"));

        String demandeId = given()
                .contentType(ContentType.JSON)
                .body("[{\"creneauId\":" + CRENEAU_ID + ",\"standId\":\"ECH-S1\","
                        + "\"cibleId\":\"ECH-B\",\"motif\":\"rendez-vous médical\"}]")
                .when().post("/api/espace-animateur/" + token + "/demandes")
                .then()
                .statusCode(200)
                .body("[0].statut", equalTo("EN_ATTENTE_CIBLE"))
                .body("[0].prevalidationOk", notNullValue())
                .body("[0].cibleNom", equalTo("Bruno Petit"))
                .extract().path("[0].id");

        // The admin cannot accept while the colleague has not agreed.
        given().contentType(ContentType.JSON).body("{}")
                .when().post("/api/echanges/" + demandeId + "/acceptation")
                .then()
                .statusCode(400);

        agreementFromBruno(demandeId);

        given().when().get("/api/echanges/" + demandeId + "/impact")
                .then()
                .statusCode(200)
                .body("echangeCroise", equalTo(true))
                .body("standCibleId", equalTo("ECH-S2"));

        given().contentType(ContentType.JSON).body("{}")
                .when().post("/api/echanges/" + demandeId + "/acceptation")
                .then()
                .statusCode(200)
                .body("statut", equalTo("ACCEPTEE"));

        // The swap was applied exactly as simulated: seats traded, nothing else.
        PlanningFestival apres = persistence.loadPersistedPlanning();
        assertThat(occupant(apres, "ECH-S1")).isEqualTo("ECH-B");
        assertThat(occupant(apres, "ECH-S2")).isEqualTo("ECH-A");

        // Both animateurs are pinned on the créneau by ANIMATEUR_CRENEAU locks.
        assertThat(referenceData.listVerrouillages())
                .filteredOn(v -> v.getType() == TypeVerrouillage.ANIMATEUR_CRENEAU
                        && v.getCreneauId() != null && v.getCreneauId() == CRENEAU_ID)
                .extracting(v -> v.getAnimateurId())
                .contains("ECH-A", "ECH-B");

        // A decided demande can no longer be refused (or re-accepted).
        given().contentType(ContentType.JSON).body("{\"commentaire\":\"non\"}")
                .when().post("/api/echanges/" + demandeId + "/refus")
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
        Stand standUn = new Stand("ECH-S1", "Stand un", Set.of(), 1, 1, false);
        Stand standDeux = new Stand("ECH-S2", "Stand deux", Set.of(), 1, 1, false);
        PosteAffectation posteAlice = new PosteAffectation("ECH-P1", standUn, creneauA);
        posteAlice.setAnimateur(alice);
        PosteAffectation posteBruno = new PosteAffectation("ECH-P2", standDeux, creneauB);
        posteBruno.setAnimateur(bruno);
        persistence.persist(new PlanningFestival(JOUR, List.of(alice, bruno), List.of(posteAlice, posteBruno)));
        String token = tokenOf("ECH-A");

        // The picker's data source: Bruno's seats, slots and stands only.
        given().when().get("/api/espace-animateur/" + token + "/collegues/ECH-B/postes")
                .then()
                .statusCode(200)
                .body("[0].creneauId", equalTo((int) creneauCibleId))
                .body("[0].standId", equalTo("ECH-S2"));

        // An id nobody bears answers 404, not an empty list: an empty 200 tells
        // the caller the id simply has no seat, which is a different fact and
        // one worth probing for. Bare 404, like every other unknown entity here
        // — a body would give the prober something to read.
        given().when().get("/api/espace-animateur/" + token + "/collegues/ECH-INEXISTANT/postes")
                .then()
                .statusCode(404);

        String demandeId = given()
                .contentType(ContentType.JSON)
                .body("[{\"creneauId\":" + CRENEAU_ID + ",\"standId\":\"ECH-S1\","
                        + "\"cibleId\":\"ECH-B\",\"motif\":\"je préfère être libre ce jour-là\","
                        + "\"creneauCibleId\":" + creneauCibleId + ",\"standCibleId\":\"ECH-S2\"}]")
                .when().post("/api/espace-animateur/" + token + "/demandes")
                .then()
                .statusCode(200)
                .body("[0].statut", equalTo("EN_ATTENTE_CIBLE"))
                .body("[0].creneauCibleId", equalTo((int) creneauCibleId))
                .body("[0].standCibleNom", equalTo("Stand deux"))
                .extract().path("[0].id");

        agreementFromBruno(demandeId);

        given().contentType(ContentType.JSON).body("{}")
                .when().post("/api/echanges/" + demandeId + "/acceptation")
                .then()
                .statusCode(200)
                .body("statut", equalTo("ACCEPTEE"));

        // The seats really crossed the two créneaux.
        PlanningFestival apres = persistence.loadPersistedPlanning();
        assertThat(occupantSur(apres, "ECH-S1", CRENEAU_ID)).isEqualTo("ECH-B");
        assertThat(occupantSur(apres, "ECH-S2", creneauCibleId)).isEqualTo("ECH-A");

        // Each animateur is pinned on the créneau they now hold: the target on
        // the demandeur's, the demandeur on the target's.
        assertThat(referenceData.listVerrouillages())
                .filteredOn(v -> v.getType() == TypeVerrouillage.ANIMATEUR_CRENEAU)
                .extracting(v -> v.getAnimateurId() + "@" + v.getCreneauId())
                .contains("ECH-B@" + CRENEAU_ID, "ECH-A@" + creneauCibleId);
    }

    @Test
    void unJetonInconnuRepondIntrouvable() {
        given().when().get("/api/espace-animateur/jeton-invente")
                .then()
                .statusCode(404)
                .body("message", notNullValue());
    }

    @Test
    void laCibleDeclineEtLAdminNArbitreJamais() {
        persistTwoSeatPlanning();
        String token = tokenOf("ECH-A");

        String demandeId = given()
                .contentType(ContentType.JSON)
                .body("[{\"creneauId\":" + CRENEAU_ID + ",\"standId\":\"ECH-S1\",\"cibleId\":\"ECH-B\"}]")
                .when().post("/api/espace-animateur/" + token + "/demandes")
                .then()
                .statusCode(200)
                .extract().path("[0].id");

        // Bruno sees it among his received demandes, and declines.
        given().cookie("planning-espace", sessionBruno)
                .when().get("/api/espace-animateur/" + tokenOf("ECH-B") + "/demandes-recues")
                .then()
                .statusCode(200)
                .body("find { it.id == '" + demandeId + "' }.statut", equalTo("EN_ATTENTE_CIBLE"));
        given().cookie("planning-espace", sessionBruno).contentType(ContentType.JSON)
                .when().post("/api/espace-animateur/" + tokenOf("ECH-B") + "/demandes-recues/" + demandeId + "/refus")
                .then()
                .statusCode(200)
                .body("statut", equalTo("REFUSEE_CIBLE"));

        // Terminal: neither the admin nor a second answer can touch it.
        given().contentType(ContentType.JSON).body("{}")
                .when().post("/api/echanges/" + demandeId + "/acceptation")
                .then()
                .statusCode(400);
        given().cookie("planning-espace", sessionBruno).contentType(ContentType.JSON)
                .when().post("/api/espace-animateur/" + tokenOf("ECH-B") + "/demandes-recues/" + demandeId + "/accord")
                .then()
                .statusCode(400);

        // And only the targeted colleague may answer: Alice cannot agree in
        // Bruno's stead on a fresh demande.
        String autreDemande = given()
                .contentType(ContentType.JSON)
                .body("[{\"creneauId\":" + CRENEAU_ID + ",\"standId\":\"ECH-S1\",\"cibleId\":\"ECH-B\"}]")
                .when().post("/api/espace-animateur/" + token + "/demandes")
                .then().statusCode(200)
                .extract().path("[0].id");
        given().contentType(ContentType.JSON)
                .when().post("/api/espace-animateur/" + token + "/demandes-recues/" + autreDemande + "/accord")
                .then()
                .statusCode(400);
    }

    /** Bruno (the target) agrees: the demande enters the admin queue. */
    private void agreementFromBruno(String demandeId) {
        given().cookie("planning-espace", sessionBruno).contentType(ContentType.JSON)
                .when().post("/api/espace-animateur/" + tokenOf("ECH-B") + "/demandes-recues/" + demandeId + "/accord")
                .then()
                .statusCode(200)
                .body("statut", equalTo("PROPOSEE"));
    }

    @Test
    void leDemandeurPeutAnnulerUneDemandeEnAttente() {
        persistTwoSeatPlanning();
        String token = tokenOf("ECH-A");

        String demandeId = given()
                .contentType(ContentType.JSON)
                .body("[{\"creneauId\":" + CRENEAU_ID + ",\"standId\":\"ECH-S1\",\"cibleId\":\"ECH-B\"}]")
                .when().post("/api/espace-animateur/" + token + "/demandes")
                .then()
                .statusCode(200)
                .extract().path("[0].id");

        given().contentType(ContentType.JSON)
                .when().post("/api/espace-animateur/" + token + "/demandes/" + demandeId + "/annulation")
                .then()
                .statusCode(204);

        given().when().get("/api/espace-animateur/" + token + "/demandes")
                .then()
                .statusCode(200)
                .body("find { it.id == '" + demandeId + "' }.statut", equalTo("ANNULEE"));

        // An already-cancelled demande cannot be decided.
        given().contentType(ContentType.JSON).body("{}")
                .when().post("/api/echanges/" + demandeId + "/acceptation")
                .then()
                .statusCode(400);
    }

    /** An empty batch is a no-op, not an error: nothing stored, no mail. */
    @Test
    void unLotVideEstAccepteSansRienEnregistrer() {
        persistTwoSeatPlanning();
        String token = tokenOf("ECH-A");

        given().contentType(ContentType.JSON).body("[]")
                .when().post("/api/espace-animateur/" + token + "/demandes")
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
                .when().post("/api/espace-animateur/" + token + "/demandes")
                .then()
                .statusCode(400)
                .body("message", equalTo("Impossible d'échanger un créneau avec soi-même"));

        // Unknown target.
        given().contentType(ContentType.JSON)
                .body("[{\"creneauId\":" + CRENEAU_ID + ",\"standId\":\"ECH-S1\",\"cibleId\":\"ECH-FANTOME\"}]")
                .when().post("/api/espace-animateur/" + token + "/demandes")
                .then()
                .statusCode(400);

        // The seat on S2 is Bruno's, not Alice's.
        given().contentType(ContentType.JSON)
                .body("[{\"creneauId\":" + CRENEAU_ID + ",\"standId\":\"ECH-S2\",\"cibleId\":\"ECH-B\"}]")
                .when().post("/api/espace-animateur/" + token + "/demandes")
                .then()
                .statusCode(400);

        // Not even the valid line of the mixed batch was stored.
        given().when().get("/api/espace-animateur/" + token + "/demandes")
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

        String demandeId = given()
                .contentType(ContentType.JSON)
                .body("[{\"creneauId\":" + CRENEAU_ID + ",\"standId\":\"ECH-S1\",\"cibleId\":\"ECH-C\"}]")
                .when().post("/api/espace-animateur/" + token + "/demandes")
                .then()
                .statusCode(200)
                .body("[0].prevalidationOk", equalTo(false))
                .body("[0].contraintesViolees.size()", greaterThanOrEqualTo(1))
                .body("[0].contraintesViolees[0]", org.hamcrest.Matchers.containsString("indisponible"))
                .extract().path("[0].id");

        // Chloé holds no seat on the créneau: the impact is a simple takeover.
        given().when().get("/api/echanges/" + demandeId + "/impact")
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

        String demandeId = given()
                .contentType(ContentType.JSON)
                .body("[{\"creneauId\":" + CRENEAU_ID + ",\"standId\":\"ECH-S1\",\"cibleId\":\"ECH-B\"}]")
                .when().post("/api/espace-animateur/" + token + "/demandes")
                .then()
                .statusCode(200)
                .extract().path("[0].id");

        given().contentType(ContentType.JSON)
                .body("{\"commentaire\":\"Le repos de Bruno serait cassé\"}")
                .when().post("/api/echanges/" + demandeId + "/refus")
                .then()
                .statusCode(200)
                .body("statut", equalTo("REFUSEE"))
                .body("commentaireAdmin", equalTo("Le repos de Bruno serait cassé"));

        given().when().get("/api/espace-animateur/" + token + "/demandes")
                .then()
                .statusCode(200)
                .body("find { it.id == '" + demandeId + "' }.statut", equalTo("REFUSEE"))
                .body("find { it.id == '" + demandeId + "' }.commentaireAdmin",
                        equalTo("Le repos de Bruno serait cassé"));

        // The planning was not touched by the refusal.
        PlanningFestival apres = persistence.loadPersistedPlanning();
        assertThat(occupant(apres, "ECH-S1")).isEqualTo("ECH-A");
        assertThat(occupant(apres, "ECH-S2")).isEqualTo("ECH-B");
    }

    /** Only the demandeur can withdraw their demande — another token is a 400. */
    @Test
    void unAutreAnimateurNePeutPasAnnulerLaDemande() {
        persistTwoSeatPlanning();
        String aliceToken = tokenOf("ECH-A");
        String brunoToken = tokenOf("ECH-B");

        String demandeId = given()
                .contentType(ContentType.JSON)
                .body("[{\"creneauId\":" + CRENEAU_ID + ",\"standId\":\"ECH-S1\",\"cibleId\":\"ECH-B\"}]")
                .when().post("/api/espace-animateur/" + aliceToken + "/demandes")
                .then()
                .statusCode(200)
                .extract().path("[0].id");

        // Bruno acts with his OWN session on his own token: the 400 is the
        // ownership rule, not a session mismatch.
        RestAssured.requestSpecification = null;
        given().contentType(ContentType.JSON)
                .cookie("planning-espace", sessionBruno)
                .when().post("/api/espace-animateur/" + brunoToken + "/demandes/" + demandeId + "/annulation")
                .then()
                .statusCode(400);
        RestAssured.requestSpecification = new RequestSpecBuilder()
                .addCookie("planning-espace", sessionAlice)
                .build();

        given().when().get("/api/espace-animateur/" + aliceToken + "/demandes")
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
                .when().put("/api/animateurs/ECH-A")
                .then()
                .statusCode(200);
        assertThat(tokenOf("ECH-A")).isEqualTo(oldToken);

        String newToken = given()
                .contentType(ContentType.JSON)
                .when().post("/api/animateurs/ECH-A/jeton")
                .then()
                .statusCode(200)
                .extract().path("token");
        assertThat(newToken).isNotBlank().isNotEqualTo(oldToken);

        given().when().get("/api/espace-animateur/" + oldToken)
                .then()
                .statusCode(404);
        given().when().get("/api/espace-animateur/" + newToken)
                .then()
                .statusCode(200)
                .body("animateurId", equalTo("ECH-A"));

        // An unknown animateur cannot get a token.
        given().contentType(ContentType.JSON)
                .when().post("/api/animateurs/ECH-FANTOME/jeton")
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
        Stand standUn = new Stand("ECH-S1", "Stand un", Set.of(), 1, 1, false);
        Stand standDeux = new Stand("ECH-S2", "Stand deux", Set.of(), 1, 1, false);
        Creneau creneau = new Creneau(CRENEAU_ID, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(12, 0));
        PosteAffectation posteUn = new PosteAffectation("ECH-P1", standUn, creneau);
        posteUn.setAnimateur(alice);
        PosteAffectation posteDeux = new PosteAffectation("ECH-P2", standDeux, creneau);
        posteDeux.setAnimateur(bruno);
        persistence.persist(new PlanningFestival(JOUR, List.of(alice, bruno, chloe), List.of(posteUn, posteDeux)));

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

    private static String occupantSur(PlanningFestival planning, String standId, long creneauId) {
        return planning.getPostes().stream()
                .filter(poste -> poste.getStand() != null && standId.equals(poste.getStand().getId())
                        && poste.getCreneau() != null && poste.getCreneau().getId() != null
                        && poste.getCreneau().getId() == creneauId)
                .findFirst()
                .map(poste -> poste.getAnimateur() == null ? null : poste.getAnimateur().getId())
                .orElse(null);
    }

    private static String occupant(PlanningFestival planning, String standId) {
        return planning.getPostes().stream()
                .filter(poste -> poste.getStand() != null && standId.equals(poste.getStand().getId())
                        && poste.getCreneau() != null && poste.getCreneau().getId() != null
                        && poste.getCreneau().getId() == CRENEAU_ID)
                .findFirst()
                .map(poste -> poste.getAnimateur() == null ? null : poste.getAnimateur().getId())
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
