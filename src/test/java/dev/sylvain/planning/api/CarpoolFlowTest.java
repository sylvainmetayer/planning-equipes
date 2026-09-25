package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.service.journal.EntreeJournal;
import dev.sylvain.planning.service.journal.JournalActionService;
import dev.sylvain.planning.service.referentiel.ContrainteAdHocService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import dev.sylvain.planning.service.solve.SolverJobService;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * « Je viens avec… » end to end: asked for from the Covoiturage tab of the
 * espace while the collection is open, apart from the declaration, validated by
 * the admin into an {@code ARRIVEE_GROUPEE} exception — or set aside with a
 * reason — and each decision told by mail; a validated car is then the
 * Covoiturage tab's to cancel, never the Ajustements manuels screen's.
 */
@QuarkusTest
class CarpoolFlowTest {

    private static final LocalDate JOUR_UN = LocalDate.of(2026, 7, 10);

    @Inject
    ReferenceDataService referenceData;

    @Inject
    MockMailbox mailbox;

    @Inject
    JournalActionService journal;

    @Inject
    SolverJobService solverJobs;

    @Inject
    PlanningPersistenceService persistence;

    private final List<Long> creneauxCrees = new ArrayList<>();

    private final Map<String, String> sessions = new java.util.HashMap<>();

    /** Label (COV-A…) → the id the application generated for that animateur. */
    private final Map<String, String> ids = new java.util.HashMap<>();

    @BeforeEach
    void seed() {
        RestAssured.requestSpecification = null;
        removeFixture();
        creneauxCrees.add(referenceData
                .createCreneau(new Creneau(null, 1, JOUR_UN, LocalTime.of(10, 0), LocalTime.of(12, 0)))
                .getId());
        for (String label : List.of("COV-A", "COV-B", "COV-C", "COV-D", "COV-E")) {
            Animateur animateur = new Animateur(null, label, label, LocalDate.of(1990, 1, 1), false);
            animateur.setEmail(email(label));
            ids.put(label, referenceData.createAnimateur(animateur).getId());
        }
        mailbox.clear();
        for (String label : List.of("COV-A", "COV-B")) {
            sessions.put(label, EspaceSessions.open(mailbox, tokenOf(label), email(label)));
        }
        setWindow(true);
    }

    @AfterEach
    void cleanUp() {
        RestAssured.requestSpecification = null;
        setWindow(false);
        removeFixture();
    }

    @Test
    void aCarRequestedByBothIsConfirmedAndOnlyItsValidationCreatesTheException() {
        declareDays("COV-A", "[\"2026-07-10\"]").statusCode(200);
        requestCarpool("COV-A", idsOf("COV-B"))
                .statusCode(200)
                .body("status", equalTo("EN_ATTENTE"))
                .body("teammateIds", hasItems(ids.get("COV-B")));
        requestCarpool("COV-B", idsOf("COV-A")).statusCode(200);

        JsonPath demandes = carpools();
        String demandeA = demandes.getString(of("COV-A") + ".id");
        assertThat(demandes.getBoolean(of("COV-A") + ".confirmedByAll")).isTrue();
        // Alice declared the 10th off and Bob did not: one day the car cannot hold.
        assertThat(demandes.getInt(of("COV-A") + ".divergentDayCount")).isEqualTo(1);

        // Applying the declaration writes the days, and nothing of the car.
        given().contentType(ContentType.JSON)
                .when()
                .post("/api/disponibilites/" + pendingDeclarationOf("COV-A") + "/application")
                .then()
                .statusCode(200);
        assertThat(groupedArrivals()).isEmpty();
        assertThat(carpools().getString(of("COV-A") + ".status")).isEqualTo("EN_ATTENTE");

        mailbox.clear();
        given().contentType(ContentType.JSON)
                .when()
                .post("/api/disponibilites/coequipiers/" + demandeA + "/validation")
                .then()
                .statusCode(200)
                .body("request.status", equalTo("VALIDEE"))
                .body("avertissements[0].type", equalTo("ARRIVEE_GROUPEE_JOURS_DIVERGENTS"));

        assertThat(groupedArrivals())
                .singleElement()
                .satisfies(contrainte -> assertThat(contrainte.getAnimateursConcernes().stream()
                                .map(Animateur::getId)
                                .toList())
                        .containsExactly(ids.get("COV-A"), ids.get("COV-B")));
        // Bob's own demand named the same car: validated with it, not left pending.
        assertThat(carpools().getString(of("COV-A") + ".status")).isEqualTo("VALIDEE");
        assertThat(carpools().getString(of("COV-B") + ".status")).isEqualTo("VALIDEE");
        // Every member hears it, with the others named and a link to the tab.
        for (String label : List.of("COV-A", "COV-B")) {
            assertThat(mailbox.getMailsSentTo(email(label))).singleElement().satisfies(mail -> {
                assertThat(mail.getSubject()).contains("arrivée groupée est validée");
                assertThat(mail.getText()).contains("/covoiturage");
            });
        }
    }

    @Test
    void aCarNamingOneselfOrMoreThanThreeTeammatesIsRefused() {
        requestCarpool("COV-A", idsOf("COV-A")).statusCode(400);
        requestCarpool("COV-A", idsOf("COV-B", "COV-C", "COV-D", "COV-E")).statusCode(400);
        requestCarpool("COV-A", "[\"COV-INCONNU\"]").statusCode(400);
        assertThat(carpools().getString(of("COV-A") + ".id")).isNull();
    }

    @Test
    void aRequestIsOpenOnlyWhileTheCollectionIsAndStaysReadableAfterwards() {
        requestCarpool("COV-A", idsOf("COV-B")).statusCode(200);
        setWindow(false);

        requestCarpool("COV-A", idsOf("COV-C")).statusCode(400);
        carpoolView("COV-A")
                .body("collectionOpen", is(false))
                .body("status", equalTo("EN_ATTENTE"))
                .body("teammateIds", equalTo(List.of(ids.get("COV-B"))));
    }

    @Test
    void aCarOfTwoIncompatibleAnimateursIsRefusedAtValidation() {
        ContrainteAdHoc incompatibilite = new ContrainteAdHoc(null, TypeContrainteAdHoc.INCOMPATIBILITE);
        incompatibilite.setAnimateursConcernes(new ArrayList<>(List.of(animateur("COV-A"), animateur("COV-B"))));
        referenceData.writeContrainteAdHoc(incompatibilite);
        requestCarpool("COV-A", idsOf("COV-B")).statusCode(200);
        String demande = carpools().getString(of("COV-A") + ".id");

        given().contentType(ContentType.JSON)
                .when()
                .post("/api/disponibilites/coequipiers/" + demande + "/validation")
                .then()
                .statusCode(400);
        assertThat(groupedArrivals()).isEmpty();
        assertThat(carpools().getString(of("COV-A") + ".status")).isEqualTo("EN_ATTENTE");
    }

    @Test
    void aCarSetAsideWritesNothingTellsTheRequesterWhyAndLetsThemAskAgain() {
        requestCarpool("COV-A", idsOf("COV-B")).statusCode(200);
        String demande = carpools().getString(of("COV-A") + ".id");
        mailbox.clear();

        given().contentType(ContentType.JSON)
                .body("{\"reason\":\"Bob ne vient que le samedi.\"}")
                .when()
                .post("/api/disponibilites/coequipiers/" + demande + "/ecart")
                .then()
                .statusCode(200)
                .body("status", is("ECARTEE"))
                .body("reason", equalTo("Bob ne vient que le samedi."));
        assertThat(groupedArrivals()).isEmpty();
        carpoolView("COV-A").body("status", equalTo("ECARTEE")).body("reason", equalTo("Bob ne vient que le samedi."));
        assertThat(mailbox.getMailsSentTo(email("COV-A")))
                .singleElement()
                .satisfies(mail -> assertThat(mail.getText()).contains("Bob ne vient que le samedi."));
        // Nobody else was part of a decision: Bob hears nothing.
        assertThat(mailbox.getMailsSentTo(email("COV-B"))).isNullOrEmpty();

        requestCarpool("COV-A", idsOf("COV-C")).statusCode(200).body("status", equalTo("EN_ATTENTE"));
    }

    @Test
    void aReasonLongerThanASentenceIsRefused() {
        requestCarpool("COV-A", idsOf("COV-B")).statusCode(200);
        String demande = carpools().getString(of("COV-A") + ".id");

        given().contentType(ContentType.JSON)
                .body("{\"reason\":\"" + "x".repeat(501) + "\"}")
                .when()
                .post("/api/disponibilites/coequipiers/" + demande + "/ecart")
                .then()
                .statusCode(400);
        assertThat(carpools().getString(of("COV-A") + ".status")).isEqualTo("EN_ATTENTE");
    }

    @Test
    void aValidatedCarIsTheOrganisationsToChange() {
        requestCarpool("COV-A", idsOf("COV-B")).statusCode(200);
        validate(carpools().getString(of("COV-A") + ".id"));

        // Leaving the car, or naming another one, from the espace: refused.
        requestCarpool("COV-A", "[]").statusCode(409);
        requestCarpool("COV-A", idsOf("COV-C")).statusCode(409);
        // Bob never asked for anything, but rides in the validated car all the same.
        requestCarpool("COV-B", idsOf("COV-C")).statusCode(409);

        assertThat(groupedArrivals()).hasSize(1);
        assertThat(carpools().getList("findAll { it.status == 'EN_ATTENTE' }")).isEmpty();
        carpoolView("COV-A").body("status", equalTo("VALIDEE")).body("teammateIds", equalTo(List.of(ids.get("COV-B"))));
        carpoolView("COV-B").body("status", equalTo("VALIDEE")).body("teammateIds", equalTo(List.of(ids.get("COV-A"))));
    }

    @Test
    void aDemandForACarThatAlreadyExistsJoinsItInsteadOfDoublingIt() {
        requestCarpool("COV-A", idsOf("COV-B")).statusCode(200);
        // Meanwhile the organisation wrote the very same car by hand.
        ContrainteAdHoc parLaMain = new ContrainteAdHoc(null, TypeContrainteAdHoc.ARRIVEE_GROUPEE);
        parLaMain.setAnimateursConcernes(new ArrayList<>(List.of(animateur("COV-B"), animateur("COV-A"))));
        String existante =
                referenceData.writeContrainteAdHoc(parLaMain).contrainte().getId();

        validate(carpools().getString(of("COV-A") + ".id"))
                .body("request.status", equalTo("VALIDEE"))
                .body("request.contrainteId", equalTo(existante));

        assertThat(groupedArrivals()).extracting(ContrainteAdHoc::getId).containsExactly(existante);
    }

    @Test
    void theDeclarationAndTheCarNeverTouchEachOther() {
        requestCarpool("COV-A", idsOf("COV-B")).statusCode(200);

        // A declaration still carrying the old field: the field is ignored.
        given().cookie("planning-espace", sessions.get("COV-A"))
                .contentType(ContentType.JSON)
                .body("{\"joursIndisponibles\":[],\"souhaits\":[],\"covoiturage\":[]}")
                .when()
                .post("/api/espace-animateur/" + tokenOf("COV-A") + "/disponibilites")
                .then()
                .statusCode(200);
        assertThat(carpools().getString(of("COV-A") + ".status")).isEqualTo("EN_ATTENTE");

        // Refusing the declaration leaves the car pending, and withdrawing the
        // car leaves the declaration pending.
        given().contentType(ContentType.JSON)
                .body("{\"commentaire\":\"non\"}")
                .when()
                .post("/api/disponibilites/" + pendingDeclarationOf("COV-A") + "/refus")
                .then()
                .statusCode(200);
        assertThat(carpools().getString(of("COV-A") + ".status")).isEqualTo("EN_ATTENTE");
        declareDays("COV-A", "[]").statusCode(200);
        requestCarpool("COV-A", "[]").statusCode(200).body("status", is(nullValue()));
        assertThat(carpools().getString(of("COV-A") + ".id")).isNull();
        assertThat(pendingDeclarationOf("COV-A")).isNotNull();
    }

    @Test
    void cancellingAValidatedCarDeletesTheExceptionMarksEveryDemandAndTellsEachMember() {
        requestCarpool("COV-A", idsOf("COV-B")).statusCode(200);
        requestCarpool("COV-B", idsOf("COV-A")).statusCode(200);
        String demandeA = carpools().getString(of("COV-A") + ".id");
        validate(demandeA);
        assertThat(groupedArrivals()).hasSize(1);
        mailbox.clear();

        cancel(demandeA, "{\"reason\":\"La voiture est en panne.\"}")
                .statusCode(200)
                .body("status", equalTo("ANNULEE"))
                .body("reason", equalTo("La voiture est en panne."))
                .body("decidedAt", is(org.hamcrest.Matchers.notNullValue()));

        assertThat(groupedArrivals()).isEmpty();
        for (String label : List.of("COV-A", "COV-B")) {
            assertThat(carpools().getString(of(label) + ".status")).isEqualTo("ANNULEE");
            assertThat(carpools().getString(of(label) + ".reason")).isEqualTo("La voiture est en panne.");
            assertThat(mailbox.getMailsSentTo(email(label))).singleElement().satisfies(mail -> {
                assertThat(mail.getSubject()).contains("arrivée groupée est annulée");
                assertThat(mail.getText())
                        .contains("La voiture est en panne.")
                        .contains("nouvelle demande depuis l'onglet Covoiturage")
                        .contains("/covoiturage");
            });
        }
        // The action is journalled by the demand's id; the reason travels nowhere near it.
        EntreeJournal ligne = journal.list(20).stream()
                .filter(entree -> "COVOITURAGE_ANNULE".equals(entree.action()))
                .findFirst()
                .orElseThrow();
        assertThat(ligne.entiteId()).isEqualTo(demandeA);
        assertThat(ligne.resultat()).isEqualTo(EntreeJournal.Resultat.SUCCES);
        assertThat(ligne.toString()).doesNotContain("panne");

        // Both members read it in their espace, reason included, with a blank picker.
        carpoolView("COV-A")
                .body("status", equalTo("ANNULEE"))
                .body("reason", equalTo("La voiture est en panne."))
                .body("teammateIds", equalTo(List.of(ids.get("COV-B"))));
        carpoolView("COV-B").body("status", equalTo("ANNULEE"));
    }

    @Test
    void aMemberWhoNeverAskedReadsTheCancellationToo() {
        sessions.put("COV-C", EspaceSessions.open(mailbox, tokenOf("COV-C"), email("COV-C")));
        requestCarpool("COV-A", idsOf("COV-C")).statusCode(200);
        String demande = carpools().getString(of("COV-A") + ".id");
        validate(demande);

        cancel(demande, "{}").statusCode(200).body("reason", is(nullValue()));

        carpoolView("COV-C").body("status", equalTo("ANNULEE")).body("teammateIds", equalTo(List.of(ids.get("COV-A"))));
    }

    @Test
    void onlyAValidatedCarCanBeCancelledAndOnlyOnce() {
        requestCarpool("COV-A", idsOf("COV-B")).statusCode(200);
        String demande = carpools().getString(of("COV-A") + ".id");

        // Pending: nothing to cancel.
        cancel(demande, "{}").statusCode(409);

        validate(demande);
        cancel(demande, "{}").statusCode(200);
        // Twice: the group is gone.
        cancel(demande, "{}").statusCode(409);

        // Set aside: nothing to cancel either.
        requestCarpool("COV-A", idsOf("COV-C")).statusCode(200);
        String nouvelle = carpools()
                .getString("find { it.animateurId == '" + ids.get("COV-A") + "' && it.status == 'EN_ATTENTE' }.id");
        given().contentType(ContentType.JSON)
                .body("{}")
                .when()
                .post("/api/disponibilites/coequipiers/" + nouvelle + "/ecart")
                .then()
                .statusCode(200);
        cancel(nouvelle, "{}").statusCode(409);

        cancel("demande-inconnue", "{}").statusCode(404);
        cancel(demande, "{\"reason\":\"" + "x".repeat(501) + "\"}").statusCode(400);
    }

    /**
     * A running solve still scores the grouped arrival it started with: the
     * cancellation waits for it, refused like every write the landing would
     * race, and leaves the group and its demands as they were.
     */
    @Test
    void aCarCannotBeCancelledWhileASolveHoldsTheEdition() {
        requestCarpool("COV-A", idsOf("COV-B")).statusCode(200);
        String demande = carpools().getString(of("COV-A") + ".id");
        validate(demande);

        Creneau creneau = new Creneau(9591L, 1, JOUR_UN, LocalTime.of(14, 0), LocalTime.of(16, 0));
        Stand stand = new Stand("COV-SOLVE-S", "Stand COV-SOLVE-S", Set.of("STRATEGIE"), 1, 1, false);
        Animateur animateur = new Animateur("COV-SOLVE-A", "Prenom", "Nom", LocalDate.of(1990, 1, 1), false);
        PosteAffectation poste = new PosteAffectation("COV-SOLVE-P", stand, creneau);
        poste.setAnimateur(animateur);
        PlanningEvenement probleme = new PlanningEvenement(JOUR_UN, List.of(animateur), List.of(poste));
        String jobId = null;
        try {
            waitForFreeSolver();
            persistence.persist(probleme);
            jobId = solverJobs.submitSolve(probleme, 60L).getId();
            assertThat(solverJobs.findActive()).isPresent();

            cancel(demande, "{}").statusCode(409);

            assertThat(groupedArrivals()).hasSize(1);
            assertThat(carpools().getString(of("COV-A") + ".status")).isEqualTo("VALIDEE");
        } finally {
            if (jobId != null) {
                solverJobs.cancel(jobId);
            }
            // The cancelled solve still lands its best solution: let it, then wipe.
            waitForFreeSolver();
            persistence.persist(new PlanningEvenement(JOUR_UN, List.of(), List.of()));
            referenceData.deleteStand("COV-SOLVE-S");
            referenceData.deleteAnimateur("COV-SOLVE-A");
            referenceData.deleteCreneaux(List.of(9591L));
        }
    }

    private void waitForFreeSolver() {
        await().atMost(Duration.ofSeconds(120))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> solverJobs.findActive().isEmpty());
    }

    @Test
    void afterACancellationANewRequestIsOpenOnlyDuringTheCollection() {
        requestCarpool("COV-A", idsOf("COV-B")).statusCode(200);
        String demande = carpools().getString(of("COV-A") + ".id");
        validate(demande);
        cancel(demande, "{}").statusCode(200);

        requestCarpool("COV-A", idsOf("COV-C")).statusCode(200).body("status", equalTo("EN_ATTENTE"));
        requestCarpool("COV-A", "[]").statusCode(200);
        setWindow(false);
        requestCarpool("COV-A", idsOf("COV-C")).statusCode(400);
    }

    @Test
    void aCancellationOutsideTheCollectionTellsTheGroupToContactTheOrganisation() {
        requestCarpool("COV-A", idsOf("COV-B")).statusCode(200);
        String demande = carpools().getString(of("COV-A") + ".id");
        validate(demande);
        setWindow(false);
        mailbox.clear();

        cancel(demande, "{}").statusCode(200);

        assertThat(mailbox.getMailsSentTo(email("COV-B")))
                .singleElement()
                .satisfies(mail -> assertThat(mail.getText())
                        .contains("adressez-vous à l'organisation")
                        .doesNotContain("nouvelle demande"));
    }

    @Test
    void aCarBackedGroupedArrivalIsRefusedOnTheAjustementsRouteAndAHandMadeOneIsNot() {
        requestCarpool("COV-A", idsOf("COV-B")).statusCode(200);
        validate(carpools().getString(of("COV-A") + ".id"));
        ContrainteAdHoc issue = groupedArrivals().getFirst();

        JsonPath liste = given().when()
                .get("/api/contraintes-ad-hoc")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(liste.getBoolean("find { it.id == '" + issue.getId() + "' }.issueDeCovoiturage"))
                .isTrue();

        given().when()
                .delete("/api/contraintes-ad-hoc/" + issue.getId())
                .then()
                .statusCode(409)
                .body("message", equalTo(ContrainteAdHocService.CARPOOL_BACKED));
        given().contentType(ContentType.JSON)
                .body(Map.of(
                        "id",
                        issue.getId(),
                        "type",
                        "ARRIVEE_GROUPEE",
                        "animateursConcernes",
                        List.of(Map.of("id", ids.get("COV-A")), Map.of("id", ids.get("COV-C")))))
                .when()
                .post("/api/contraintes-ad-hoc")
                .then()
                .statusCode(409)
                .body("message", equalTo(ContrainteAdHocService.CARPOOL_BACKED));
        assertThat(groupedArrivals()).extracting(ContrainteAdHoc::getId).containsExactly(issue.getId());

        ContrainteAdHoc parLaMain = new ContrainteAdHoc(null, TypeContrainteAdHoc.ARRIVEE_GROUPEE);
        parLaMain.setAnimateursConcernes(new ArrayList<>(List.of(animateur("COV-C"), animateur("COV-D"))));
        String main = referenceData.writeContrainteAdHoc(parLaMain).contrainte().getId();
        assertThat(liste(main)).isFalse();
        given().when().delete("/api/contraintes-ad-hoc/" + main).then().statusCode(204);
    }

    private Boolean liste(String contrainteId) {
        return given().when()
                .get("/api/contraintes-ad-hoc")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getBoolean("find { it.id == '" + contrainteId + "' }.issueDeCovoiturage");
    }

    private io.restassured.response.ValidatableResponse cancel(String demande, String body) {
        return given().contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/disponibilites/coequipiers/" + demande + "/annulation")
                .then();
    }

    private io.restassured.response.ValidatableResponse validate(String demande) {
        return given().contentType(ContentType.JSON)
                .when()
                .post("/api/disponibilites/coequipiers/" + demande + "/validation")
                .then()
                .statusCode(200);
    }

    private io.restassured.response.ValidatableResponse carpoolView(String label) {
        return given().cookie("planning-espace", sessions.get(label))
                .when()
                .get("/api/espace-animateur/" + tokenOf(label) + "/covoiturage")
                .then()
                .statusCode(200);
    }

    private io.restassured.response.ValidatableResponse declareDays(String label, String jours) {
        return given().cookie("planning-espace", sessions.get(label))
                .contentType(ContentType.JSON)
                .body("{\"joursIndisponibles\":" + jours + ",\"souhaits\":[]}")
                .when()
                .post("/api/espace-animateur/" + tokenOf(label) + "/disponibilites")
                .then();
    }

    private io.restassured.response.ValidatableResponse requestCarpool(String label, String teammateIds) {
        return given().cookie("planning-espace", sessions.get(label))
                .contentType(ContentType.JSON)
                .body("{\"teammateIds\":" + teammateIds + "}")
                .when()
                .post("/api/espace-animateur/" + tokenOf(label) + "/covoiturage")
                .then();
    }

    private String pendingDeclarationOf(String label) {
        return given().when()
                .get("/api/disponibilites")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("find { it.animateurId == '" + ids.get(label) + "' && it.statut == 'EN_ATTENTE' }.id");
    }

    /** Only this class's rows: the suite shares one database. */
    private static JsonPath carpools() {
        return given().when()
                .get("/api/disponibilites/coequipiers")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    private List<ContrainteAdHoc> groupedArrivals() {
        return referenceData.listContraintesAdHoc().stream()
                .filter(contrainte -> contrainte.getType() == TypeContrainteAdHoc.ARRIVEE_GROUPEE
                        && contrainte.getAnimateursConcernes().stream()
                                .anyMatch(animateur -> ids.containsValue(animateur.getId())))
                .toList();
    }

    private String idsOf(String... labels) {
        return java.util.Arrays.stream(labels)
                .map(label -> "\"" + ids.get(label) + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    /** The GPath selecting one animateur's demand. */
    private String of(String label) {
        return "find { it.animateurId == '" + ids.get(label) + "' }";
    }

    private static String email(String label) {
        return label.toLowerCase() + "@example.org";
    }

    private static void setWindow(boolean ouverte) {
        given().contentType(ContentType.JSON)
                .body("{\"collecteOuverte\":" + ouverte + ",\"prevenirAnimateurs\":false}")
                .when()
                .put("/api/disponibilites/configuration")
                .then()
                .statusCode(200);
    }

    private Animateur animateur(String label) {
        return referenceData.listAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals(ids.get(label)))
                .findFirst()
                .orElseThrow();
    }

    private String tokenOf(String label) {
        return animateur(label).getAccessToken();
    }

    private void removeFixture() {
        List<String> nos = referenceData.listAnimateurs().stream()
                .filter(candidat ->
                        candidat.getEmail() != null && candidat.getEmail().startsWith("cov-"))
                .map(Animateur::getId)
                .toList();
        List<String> contraintes = referenceData.listContraintesAdHoc().stream()
                .filter(contrainte -> contrainte.getAnimateursConcernes().stream()
                        .anyMatch(animateur -> nos.contains(animateur.getId())))
                .map(ContrainteAdHoc::getId)
                .toList();
        // The animateurs first: their demands go with them by cascade, and a
        // grouped arrival a validated demand stands behind refuses a delete.
        nos.forEach(referenceData::deleteAnimateur);
        contraintes.forEach(referenceData::deleteContrainteAdHoc);
        creneauxCrees.forEach(referenceData::deleteCreneau);
        creneauxCrees.clear();
        sessions.clear();
        ids.clear();
    }
}
