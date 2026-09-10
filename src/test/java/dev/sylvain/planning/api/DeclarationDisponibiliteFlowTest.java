package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.service.ReferenceDataChangeTracker;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.referentiel.TypologieItem;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end self-service declaration (issue #291) against the real PostgreSQL
 * container: the collection window the admin opens, the declaration an
 * animateur sends from their espace, the replacement of a pending one, and the
 * two admin decisions.
 *
 * <p>The heart of the feature is what does <b>not</b> happen: as long as no
 * admin has applied anything, the fiche keeps saying what it said. Half of
 * these assertions are about the referential staying still.</p>
 */
@QuarkusTest
class DeclarationDisponibiliteFlowTest {

    private static final LocalDate JOUR_UN = LocalDate.of(2026, 7, 10);
    private static final LocalDate JOUR_DEUX = LocalDate.of(2026, 7, 11);

    @Inject
    ReferenceDataService referenceData;

    @Inject
    ReferenceDataChangeTracker changeTracker;

    @Inject
    MockMailbox mailbox;

    private String session;

    /** Database-generated: the créneau ids are only known once inserted. */
    private final List<Long> creneauxCrees = new ArrayList<>();

    @BeforeEach
    void seedReferential() {
        RestAssured.requestSpecification = null;
        removeFixture();
        referenceData.createTypologie(new TypologieItem("DEC-T1", "Jeux de plateau"));
        referenceData.createTypologie(new TypologieItem("DEC-T2", "Jeux d'ambiance"));
        creneauxCrees.add(referenceData
                .createCreneau(new Creneau(null, 1, JOUR_UN, LocalTime.of(10, 0), LocalTime.of(12, 0)))
                .getId());
        creneauxCrees.add(referenceData
                .createCreneau(new Creneau(null, 2, JOUR_DEUX, LocalTime.of(10, 0), LocalTime.of(12, 0)))
                .getId());

        Animateur alice = new Animateur("DEC-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        alice.setEmail("dec-alice@example.org");
        referenceData.createAnimateur(alice);

        mailbox.clear();
        session = EspaceSessions.open(mailbox, tokenOf("DEC-A"), "dec-alice@example.org");
        RestAssured.requestSpecification =
                new RequestSpecBuilder().addCookie("planning-espace", session).build();
        closeWindow();
    }

    @AfterEach
    void cleanUp() {
        RestAssured.requestSpecification = null;
        closeWindow();
        removeFixture();
    }

    @Test
    void horsFenetreLaDeclarationEstRefuseeEtRienNEstEnregistre() {
        // Nobody ever opened the window: it is closed, unlike the foire au
        // planning, which is open until somebody closes it.
        given().when()
                .get("/api/espace-animateur/" + tokenOf("DEC-A") + "/disponibilites")
                .then()
                .statusCode(200)
                .body("collecteOuverte", is(false))
                // Contains, not equals: the suite shares one database, so the
                // event days are whatever every fixture of the run left there.
                .body("joursEvenement", hasItems("2026-07-10", "2026-07-11"));

        given().contentType(ContentType.JSON)
                .body("{\"joursIndisponibles\":[\"2026-07-10\"],\"souhaits\":[\"DEC-T1\"]}")
                .when()
                .post("/api/espace-animateur/" + tokenOf("DEC-A") + "/disponibilites")
                .then()
                .statusCode(400);

        assertThat(storedDeclarations()).isEmpty();
    }

    @Test
    void unJourHorsFenetreDeCollecteRefuseAussi() {
        // The window is open as a switch but bounded to days already past: a
        // date range that does not cover today collects nothing.
        openWindow(LocalDate.now().minusDays(10), LocalDate.now().minusDays(5), false);

        given().contentType(ContentType.JSON)
                .body("{\"joursIndisponibles\":[],\"souhaits\":[]}")
                .when()
                .post("/api/espace-animateur/" + tokenOf("DEC-A") + "/disponibilites")
                .then()
                .statusCode(400);
    }

    @Test
    void uneDeclarationResteEnAttenteEtNeTouchePasAuReferentiel() {
        openWindow(null, null, false);

        given().contentType(ContentType.JSON)
                .body("{\"joursIndisponibles\":[\"2026-07-10\"],\"souhaits\":[\"DEC-T1\"],"
                        + "\"commentaire\":\"je pars dimanche midi\"}")
                .when()
                .post("/api/espace-animateur/" + tokenOf("DEC-A") + "/disponibilites")
                .then()
                .statusCode(200)
                .body("enAttente.statut", equalTo("EN_ATTENTE"))
                .body("enAttente.joursIndisponibles", hasSize(1))
                .body("enAttente.souhaitsLabels[0]", equalTo("Jeux de plateau"));

        // The whole point: the fiche has not moved.
        Animateur alice = animateur("DEC-A");
        assertThat(alice.getJoursIndisponibles()).isEmpty();
        assertThat(alice.getSouhaits()).isEmpty();

        // The admin was told, and the mail names no day.
        assertThat(mailsToAdmin())
                .anySatisfy(mail -> assertThat(mail.getSubject()).contains("déclaration de disponibilités"));
    }

    @Test
    void unRenvoiRemplaceLaPrecedenteEnAttente() {
        openWindow(null, null, false);

        String premiereId = declarer("[\"2026-07-10\"]", "[\"DEC-T1\"]");
        String secondeId = declarer("[\"2026-07-11\"]", "[\"DEC-T2\"]");

        assertThat(secondeId).isNotEqualTo(premiereId);
        // One row, not two: the admin never arbitrates two contradictory
        // versions of the same person.
        assertThat(storedDeclarations()).hasSize(1);
        given().when()
                .get("/api/disponibilites")
                .then()
                .statusCode(200)
                .body("find { it.animateurId == 'DEC-A' }.id", equalTo(secondeId))
                .body("find { it.animateurId == 'DEC-A' }.joursIndisponibles[0]", equalTo("2026-07-11"));

        // And a single notification: five corrections in a row are one item on
        // the desk, so they are one mail.
        assertThat(mailsToAdmin()).hasSize(1);
    }

    @Test
    void lApplicationEcritSurLaFicheEtRendLesDonneesPerimees() {
        openWindow(null, null, false);
        String id = declarer("[\"2026-07-10\",\"2026-07-11\"]", "[\"DEC-T1\",\"DEC-T2\"]");
        Instant avantApplication = changeTracker.lastModifiedAt();

        given().contentType(ContentType.JSON)
                .when()
                .post("/api/disponibilites/" + id + "/application")
                .then()
                .statusCode(200)
                .body("statut", equalTo("APPLIQUEE"));

        Animateur alice = animateur("DEC-A");
        assertThat(alice.getJoursIndisponibles()).containsExactlyInAnyOrder(JOUR_UN, JOUR_DEUX);
        assertThat(alice.getSouhaits()).containsExactlyInAnyOrder("DEC-T1", "DEC-T2");

        // Applied through AnimateurService like the CRUD does, so the marker the
        // staleness indicator reads really moved.
        assertThat(changeTracker.lastModifiedAt()).isAfter(avantApplication);

        // A decided declaration cannot be decided twice.
        given().contentType(ContentType.JSON)
                .body("{}")
                .when()
                .post("/api/disponibilites/" + id + "/refus")
                .then()
                .statusCode(400);
    }

    @Test
    void unRefusNeTouchePasAuReferentielEtRouvreLaVoieAUneCorrection() {
        openWindow(null, null, false);
        String id = declarer("[\"2026-07-10\"]", "[]");

        given().contentType(ContentType.JSON)
                .body("{\"commentaire\":\"le 10 est le jour du montage\"}")
                .when()
                .post("/api/disponibilites/" + id + "/refus")
                .then()
                .statusCode(200)
                .body("statut", equalTo("REFUSEE"))
                .body("commentaireAdmin", equalTo("le 10 est le jour du montage"));

        assertThat(animateur("DEC-A").getJoursIndisponibles()).isEmpty();

        // The refusal is visible in the espace, and a corrected version goes
        // through: the refused one moved to the history.
        String corrigee = declarer("[\"2026-07-11\"]", "[]");
        assertThat(corrigee).isNotEqualTo(id);
        given().when()
                .get("/api/espace-animateur/" + tokenOf("DEC-A") + "/disponibilites")
                .then()
                .statusCode(200)
                .body("enAttente.id", equalTo(corrigee))
                .body("historique[0].commentaireAdmin", equalTo("le 10 est le jour du montage"));
    }

    @Test
    void unJourEtrangerALEvenementEtUneTypologieInconnueSontRefuses() {
        openWindow(null, null, false);

        given().contentType(ContentType.JSON)
                .body("{\"joursIndisponibles\":[\"2030-01-01\"],\"souhaits\":[]}")
                .when()
                .post("/api/espace-animateur/" + tokenOf("DEC-A") + "/disponibilites")
                .then()
                .statusCode(400);

        given().contentType(ContentType.JSON)
                .body("{\"joursIndisponibles\":[],\"souhaits\":[\"DEC-INCONNUE\"]}")
                .when()
                .post("/api/espace-animateur/" + tokenOf("DEC-A") + "/disponibilites")
                .then()
                .statusCode(400);

        assertThat(storedDeclarations()).isEmpty();
    }

    @Test
    void lOuvertureNInviteQueSiOnLuiDemande() {
        openWindow(null, null, false);
        assertThat(mailsTo("dec-alice@example.org")).isEmpty();

        given().contentType(ContentType.JSON)
                .body("{\"collecteOuverte\":true,\"prevenirAnimateurs\":true}")
                .when()
                .put("/api/disponibilites/configuration")
                .then()
                .statusCode(200)
                .body("collecteOuverte", is(true))
                // At least Alice: the shared referential may also hold
                // animateurs seeded by another class, and they are invited too.
                .body("invitation.envoyes", greaterThanOrEqualTo(1));

        assertThat(mailsTo("dec-alice@example.org"))
                .anySatisfy(mail ->
                        assertThat(mail.getText()).contains("/animateur/").contains("/disponibilites"));
    }

    @Test
    void unRefusConcurrentNEcritPasLaFicheQuandMeme() {
        openWindow(null, null, false);
        String id = declarer("[\"2026-07-10\"]", "[\"DEC-T1\"]");

        given().contentType(ContentType.JSON)
                .body("{\"commentaire\":\"pas ce jour-là\"}")
                .when()
                .post("/api/disponibilites/" + id + "/refus")
                .then()
                .statusCode(200);

        // An admin applying the very declaration another just refused: the
        // decision is claimed before the fiche is written, so this is a plain
        // refusal — not a write followed by « déjà traitée ».
        given().contentType(ContentType.JSON)
                .when()
                .post("/api/disponibilites/" + id + "/application")
                .then()
                .statusCode(400);

        assertThat(animateur("DEC-A").getJoursIndisponibles())
                .as("a refused declaration must never have touched the fiche")
                .isEmpty();
        assertThat(animateur("DEC-A").getSouhaits()).isEmpty();
    }

    @Test
    void deuxEnvoisSimultanesLaissentUneSeuleProposition() throws Exception {
        openWindow(null, null, false);
        String corps = "{\"joursIndisponibles\":[\"2026-07-10\"],\"souhaits\":[]}";
        String url = "/api/espace-animateur/" + tokenOf("DEC-A") + "/disponibilites";

        // Two tabs, or one retried request: the espace's own « envoi en cours »
        // guard is per component and stops neither. The pair used to break the
        // partial unique index and answer 500, right where the feature promises
        // that resending corrects.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> envois = pool.invokeAll(List.of(
                    () -> given().contentType(ContentType.JSON)
                            .body(corps)
                            .when()
                            .post(url)
                            .then()
                            .extract()
                            .statusCode(),
                    () -> given().contentType(ContentType.JSON)
                            .body(corps)
                            .when()
                            .post(url)
                            .then()
                            .extract()
                            .statusCode()));
            for (Future<Integer> envoi : envois) {
                assertThat(envoi.get()).as("neither submission may fail").isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(storedDeclarations()).hasSize(1);
    }

    /* -------------------------------- Helpers -------------------------------- */

    private String declarer(String jours, String souhaits) {
        return given().contentType(ContentType.JSON)
                .body("{\"joursIndisponibles\":" + jours + ",\"souhaits\":" + souhaits + "}")
                .when()
                .post("/api/espace-animateur/" + tokenOf("DEC-A") + "/disponibilites")
                .then()
                .statusCode(200)
                .extract()
                .path("enAttente.id");
    }

    private void openWindow(LocalDate debut, LocalDate fin, boolean prevenir) {
        given().contentType(ContentType.JSON)
                .body("{\"collecteOuverte\":true"
                        + (debut == null ? "" : ",\"debut\":\"" + debut + "\"")
                        + (fin == null ? "" : ",\"fin\":\"" + fin + "\"")
                        + ",\"prevenirAnimateurs\":" + prevenir + "}")
                .when()
                .put("/api/disponibilites/configuration")
                .then()
                .statusCode(200);
        mailbox.clear();
    }

    private static void closeWindow() {
        given().contentType(ContentType.JSON)
                .body("{\"collecteOuverte\":false}")
                .when()
                .put("/api/disponibilites/configuration")
                .then()
                .statusCode(200);
    }

    /** Only Alice's rows: the suite shares one database with every other class. */
    private List<String> storedDeclarations() {
        return given().when()
                .get("/api/disponibilites")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("findAll { it.animateurId == 'DEC-A' }.id", String.class);
    }

    private List<Mail> mailsToAdmin() {
        return mailbox.getMailsSentTo("admin@example.org");
    }

    private List<Mail> mailsTo(String address) {
        return mailbox.getMailsSentTo(address);
    }

    private Animateur animateur(String id) {
        return referenceData.listAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private String tokenOf(String animateurId) {
        return animateur(animateurId).getAccessToken();
    }

    /** Leaves the shared database as it was found — every id is prefixed DEC-. */
    private void removeFixture() {
        referenceData.listAnimateurs().stream()
                .map(Animateur::getId)
                .filter(id -> id.startsWith("DEC-"))
                .forEach(referenceData::deleteAnimateur);
        creneauxCrees.forEach(referenceData::deleteCreneau);
        creneauxCrees.clear();
        referenceData.listTypologies().stream()
                .map(TypologieItem::id)
                .filter(id -> id.startsWith("DEC-"))
                .forEach(referenceData::deleteTypologie);
    }
}
