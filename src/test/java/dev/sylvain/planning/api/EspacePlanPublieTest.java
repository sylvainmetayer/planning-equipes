package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import dev.sylvain.planning.config.DevMode;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.espace.DemandeEchangeService;
import dev.sylvain.planning.service.espace.DemandeEchangeService.NouvelleDemande;
import dev.sylvain.planning.service.espace.JourJClock;
import dev.sylvain.planning.service.publication.PlanPublicationService;
import dev.sylvain.planning.service.referentiel.ParametresService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
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

/**
 * The promise of issue #245, seen from the espace animateur: what you see is
 * what somebody sent you. Nothing before the first publication, and nothing
 * new until the next one — however much the working plan moved in between.
 */
@QuarkusTest
class EspacePlanPublieTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 11);
    private static final long CRENEAU_ID = 9401L;
    private static final long CRENEAU_ID_LENDEMAIN = 9402L;
    private static final String EMAIL_ALICE = "espace-alice@example.org";
    private static final String EMAIL_BRUNO = "espace-bruno@example.org";

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    PlanPublicationService publication;

    @Inject
    DemandeEchangeService demandes;

    @Inject
    ReferenceDataService referenceData;

    @Inject
    MockMailbox mailbox;

    @Inject
    DataSource dataSource;

    @Inject
    JourJClock clock;

    @Inject
    ParametresService parametres;

    /** A server launched with {@code quarkus:dev}, as far as the guard can tell. */
    private static final class DevModeActif extends DevMode {
        @Override
        public boolean isActive() {
            return true;
        }
    }

    @BeforeEach
    void seed() {
        mailbox.clear();
        forgetPublications();
        persistence.clearDatabase();
        oublierEmplacements();
        persistPlan("PUBESP-A");
        donnerEmail("PUBESP-A", EMAIL_ALICE);

        // The espace session (e-mail code flow) rides on every request.
        RestAssured.requestSpecification = null;
        String session = EspaceSessions.open(EMAIL_ALICE);
        RestAssured.requestSpecification = new RequestSpecBuilder()
                .addHeader(EspaceSessions.EN_TETE, session)
                .build();
        mailbox.clear();
    }

    @AfterEach
    void nettoyer() {
        RestAssured.requestSpecification = null;
        forgetPublications();
        QuarkusMock.installMockForType(new DevModeActif(), DevMode.class);
        clock.setMocked(null, null);
    }

    @Test
    void tantQueRienNEstPublieLEspaceNeMontreAucunPlanning() {
        given().when()
                .get("/api/espace-animateur/" + tokenOf("PUBESP-A"))
                .then()
                .statusCode(200)
                .body("publieLe", nullValue())
                .body("postes.size()", equalTo(0));
    }

    @Test
    void aPresPublicationLEspaceMontreLePlanningEtSaDate() {
        publication.publier();

        given().when()
                .get("/api/espace-animateur/" + tokenOf("PUBESP-A"))
                .then()
                .statusCode(200)
                .body("publieLe", containsString("20"))
                .body("postes.size()", equalTo(1))
                .body("postes[0].standNom", equalTo("Stand espace un"));
    }

    @Test
    void unPlanDeTravailModifieNeBougePasLEspaceAvantLaProchainePublication() {
        publication.publier();

        // The working plan moves — repair assistant, validated échange,
        // incremental solve: the espace must not follow on its own.
        persistPlan("PUBESP-B");

        given().when()
                .get("/api/espace-animateur/" + tokenOf("PUBESP-A"))
                .then()
                .statusCode(200)
                .body("postes.size()", equalTo(1))
                .body("postes[0].standNom", equalTo("Stand espace un"));

        publication.publier();

        given().when()
                .get("/api/espace-animateur/" + tokenOf("PUBESP-A"))
                .then()
                .statusCode(200)
                .body("postes.size()", equalTo(0));
    }

    /**
     * Issue #531: an acceptation moves the working plan, so between the
     * decision and the publication the espace shows « Acceptée » above the seat
     * from before it. The contradiction is not a display detail — the aide used
     * to tell the animateur their planning had changed, and the planning it
     * sent them to check had not. {@code communiqueeLe} is what lets both
     * screens name that in-between state.
     */
    @Test
    void uneAcceptationNonPublieeEstDiteNonCommuniquee() {
        publication.publier();

        String demandeId = demandes.submit(
                        "PUBESP-A",
                        List.of(new NouvelleDemande(CRENEAU_ID, "PUBESP-S1", "PUBESP-B", "empêchement", null, null)))
                .get(0)
                .getId();
        demandes.acceptByTarget("PUBESP-B", demandeId);
        demandes.accept(demandeId, null);

        // Decided, and the espace still serves the plan published before it.
        given().when()
                .get("/api/espace-animateur/" + tokenOf("PUBESP-A") + "/demandes")
                .then()
                .statusCode(200)
                .body("[0].statut", equalTo("ACCEPTEE"))
                .body("[0].decideLe", notNullValue())
                .body("[0].communiqueeLe", nullValue());
        given().when()
                .get("/api/espace-animateur/" + tokenOf("PUBESP-A"))
                .then()
                .statusCode(200)
                .body("postes.size()", equalTo(1));

        publication.publier();

        // The publication carries the decision: the seat is gone, and the
        // demande no longer claims anything the planning contradicts.
        given().when()
                .get("/api/espace-animateur/" + tokenOf("PUBESP-A") + "/demandes")
                .then()
                .statusCode(200)
                .body("[0].communiqueeLe", notNullValue());
        given().when()
                .get("/api/espace-animateur/" + tokenOf("PUBESP-A"))
                .then()
                .statusCode(200)
                .body("postes.size()", equalTo(0));
    }

    /**
     * Issue #532: the espace replays the sentences of the publication that
     * concerned this animateur — the very ones their mail carried, read from
     * the trace and never recomputed.
     */
    @Test
    void lEspaceRepeteLesPhrasesDeLaDernierePublicationQuiLeConcerne() {
        // A first delivery announces a planning, not a list of corrections.
        publication.publier();
        given().when()
                .get("/api/espace-animateur/" + tokenOf("PUBESP-A"))
                .then()
                .statusCode(200)
                .body("changements.size()", equalTo(0))
                .body("changementsLe", nullValue());

        // Their seat goes to somebody else: the next publication says so.
        persistPlan("PUBESP-B");
        publication.publier();

        given().when()
                .get("/api/espace-animateur/" + tokenOf("PUBESP-A"))
                .then()
                .statusCode(200)
                .body("changements.size()", equalTo(1))
                .body("changements[0]", containsString("Stand espace un"))
                .body("changements[0]", containsString("retir"))
                .body("changementsLe", notNullValue());
    }

    /**
     * Somebody else's diff is somebody else's: the route answers the bearer of
     * the token and nobody else. The same publication tells Alice she lost the
     * seat and Bruno he gained it.
     */
    @Test
    void chacunNeLitQueSesPropresPhrases() {
        publication.publier();
        persistPlan("PUBESP-B");
        publication.publier();

        given().when()
                .get("/api/espace-animateur/" + tokenOf("PUBESP-A"))
                .then()
                .statusCode(200)
                .body("changements.size()", equalTo(1))
                .body("changements[0]", containsString("retir"));

        RestAssured.requestSpecification = null;
        donnerEmail("PUBESP-B", EMAIL_BRUNO);
        String session = EspaceSessions.open(EMAIL_BRUNO);
        RestAssured.requestSpecification = new RequestSpecBuilder()
                .addHeader(EspaceSessions.EN_TETE, session)
                .build();

        given().when()
                .get("/api/espace-animateur/" + tokenOf("PUBESP-B"))
                .then()
                .statusCode(200)
                // Bruno had nothing published before: for him this one is a
                // first delivery, so no list of corrections either.
                .body("changements.size()", equalTo(0));
    }

    /**
     * The trace's own invariant, seen from the espace: it carries the sentences
     * that were read, not identifiers to resolve again. A stand renamed since
     * does not rewrite what somebody was told.
     */
    @Test
    void unStandRenommeDepuisNeRecritPasCeQuiAEteAnnonce() {
        publication.publier();
        persistPlan("PUBESP-B");
        publication.publier();
        renommerStand("PUBESP-S1", "Stand rebaptisé");

        given().when()
                .get("/api/espace-animateur/" + tokenOf("PUBESP-A"))
                .then()
                .statusCode(200)
                .body("changements[0]", containsString("Stand espace un"))
                .body("changements[0]", not(containsString("rebaptisé")));
    }

    /**
     * The other half of the message stays out (issue #532, and the
     * contradiction issue #531 removes): a publication that only announces an
     * échange decision moves nobody's schedule, so the espace shows no « Ce
     * qui a changé pour vous » banner — and « votre demande est en attente de
     * décision » is never replayed there, since it stops being true the moment
     * the organisation decides, with no publication in between. The sentence
     * is kept in the trace, under its own heading.
     */
    @Test
    void uneDecisionDEchangeNeFaitPasUnBandeauDeChangements() {
        publication.publier();
        String demandeId = demandes.submit(
                        "PUBESP-A",
                        List.of(new NouvelleDemande(CRENEAU_ID, "PUBESP-S1", "PUBESP-B", "empêchement", null, null)))
                .get(0)
                .getId();
        demandes.acceptByTarget("PUBESP-B", demandeId);
        // Refused: nothing moves in anybody's planning, and Alice is a
        // recipient of the next publication for the decision alone.
        demandes.refuse(demandeId, "Bruno doit rester sur ce stand");
        publication.publier();

        given().when()
                .get("/api/espace-animateur/" + tokenOf("PUBESP-A"))
                .then()
                .statusCode(200)
                .body("changements.size()", equalTo(0))
                .body("changementsLe", nullValue());

        // Not lost, though: the trace carries it where it belongs, which is
        // what the organisation reads to know who was told what.
        given().when()
                .get("/api/planning/publication/destinataires")
                .then()
                .statusCode(200)
                .body("find { it.animateurId == 'PUBESP-A' }.changements.size()", equalTo(0))
                .body("find { it.animateurId == 'PUBESP-A' }.demandes[0]", containsString("refus"));
    }

    /**
     * A later publication that only announces a decision must not hide the
     * changes still waiting for a confirmation: it does not reset that
     * confirmation, so emptying the banner asked somebody to confirm changes
     * the page no longer showed.
     */
    @Test
    void uneDecisionPublieeEnsuiteNEffacePasLesChangementsAConfirmer() {
        publication.publier();
        // Submitted while Alice still holds the seat, decided only later.
        String demandeId = demandes.submit(
                        "PUBESP-A",
                        List.of(new NouvelleDemande(CRENEAU_ID, "PUBESP-S1", "PUBESP-B", "empêchement", null, null)))
                .get(0)
                .getId();
        demandes.acceptByTarget("PUBESP-B", demandeId);

        // Her seat moves: this publication tells her, and asks her to confirm.
        persistPlan("PUBESP-B");
        publication.publier();

        // Then the organisation decides, and the next publication carries the
        // decision alone — nothing moved in her days.
        demandes.refuse(demandeId, "Déjà réglé par le nouveau planning");
        publication.publier();
        given().when()
                .get("/api/planning/publication/destinataires")
                .then()
                .statusCode(200)
                .body("find { it.animateurId == 'PUBESP-A' }.changements.size()", equalTo(0))
                .body("find { it.animateurId == 'PUBESP-A' }.demandes[0]", containsString("refus"));

        given().when()
                .get("/api/espace-animateur/" + tokenOf("PUBESP-A"))
                .then()
                .statusCode(200)
                .body("statutConfirmation", equalTo("NON_VU"))
                .body("changements.size()", equalTo(1))
                .body("changements[0]", containsString("retir"))
                .body("changementsLe", notNullValue());
    }

    /** Never written to: nothing to replay, and no date to show it under. */
    @Test
    void sansPublicationLEspaceNAnnonceAucunChangement() {
        given().when()
                .get("/api/espace-animateur/" + tokenOf("PUBESP-A"))
                .then()
                .statusCode(200)
                .body("changements.size()", equalTo(0))
                .body("changementsLe", nullValue());
    }

    /**
     * A fiche without an address is precisely the case this feature exists
     * for: the mail never left, so the espace is the only place those
     * sentences can still be read — once the address is added and the person
     * signs in (the espace itself needs the address since ADR 0049).
     */
    @Test
    void unePublicationQuiNAPasPuPartirResteLisibleDansLEspace() {
        publication.publier();
        donnerEmail("PUBESP-A", null);
        persistPlan("PUBESP-B");
        publication.publier();
        donnerEmail("PUBESP-A", EMAIL_ALICE);

        given().when()
                .get("/api/espace-animateur/" + tokenOf("PUBESP-A"))
                .then()
                .statusCode(200)
                .body("changements.size()", equalTo(1))
                .body("changements[0]", containsString("retir"));
    }

    /**
     * Issue #534: the espace says where. The label comes from the referential
     * at read time — a renamed hall reads renamed — and the coordinates travel
     * only when both are set.
     */
    @Test
    void eachSeatNamesItsStandsLocation() {
        // The location's id is drawn by the application (ADR 0050).
        String hallId = referenceData
                .createEmplacement(new Emplacement(null, "Hall B", 47.2184, -1.5536))
                .getId();
        rattacherEmplacement("PUBESP-S1", hallId);
        publication.publier();

        given().when()
                .get("/api/espace-animateur/" + tokenOf("PUBESP-A"))
                .then()
                .statusCode(200)
                .body("postes[0].emplacementNom", equalTo("Hall B"))
                .body("postes[0].emplacementLatitude", equalTo(47.2184f))
                .body("postes[0].emplacementLongitude", equalTo(-1.5536f));

        Emplacement hall = referenceData.listEmplacements().stream()
                .filter(candidat -> candidat.getId().equals(hallId))
                .findFirst()
                .orElseThrow();
        hall.setNom("Hall C");
        referenceData.updateEmplacement(hallId, hall);

        // The published plan is resolved against today's referential: what is
        // read is the label of now, not a copy frozen at publication time.
        given().when()
                .get("/api/espace-animateur/" + tokenOf("PUBESP-A"))
                .then()
                .statusCode(200)
                .body("postes[0].emplacementNom", equalTo("Hall C"));
    }

    /**
     * Geocoded halfway is not geocoded: <b>neither</b> coordinate travels
     * without the other. A lone latitude is not a position — it is a line
     * across the globe — and the espace would have to decide what to do with
     * it, which is a decision nobody can take from a half-filled fiche.
     */
    @Test
    void aLocationWithoutCompleteCoordinatesSendsNone() {
        String chapiteauId = referenceData
                .createEmplacement(new Emplacement(null, "Chapiteau", 47.2184, null))
                .getId();
        rattacherEmplacement("PUBESP-S1", chapiteauId);
        publication.publier();

        given().when()
                .get("/api/espace-animateur/" + tokenOf("PUBESP-A"))
                .then()
                .statusCode(200)
                .body("postes[0].emplacementNom", equalTo("Chapiteau"))
                .body("postes[0].emplacementLatitude", nullValue())
                .body("postes[0].emplacementLongitude", nullValue());
    }

    /** A stand attached to nothing says nothing more than it did before. */
    @Test
    void unStandSansEmplacementNAjouteRien() {
        publication.publier();

        given().when()
                .get("/api/espace-animateur/" + tokenOf("PUBESP-A"))
                .then()
                .statusCode(200)
                .body("postes[0].emplacementNom", nullValue())
                .body("postes[0].emplacementLatitude", nullValue())
                .body("postes[0].emplacementLongitude", nullValue());
    }

    /** Stepping out for a break supposes knowing where to come back to. */
    @Test
    void theBreakAlsoNamesTheLocationOfTheStandHeld() {
        Animateur alice = new Animateur("PUBESP-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur bruno = new Animateur("PUBESP-B", "Bruno", "Petit", LocalDate.of(1992, 2, 2), false);
        Stand stand = new Stand("PUBESP-S1", "Stand espace un", Set.of(), 2, 2, false);
        Creneau longue = new Creneau(CRENEAU_ID, 1, JOUR, LocalTime.of(13, 0), LocalTime.of(20, 0));
        PosteAffectation posteAlice = new PosteAffectation("PUBESP-P1", stand, longue);
        posteAlice.setAnimateur(alice);
        PosteAffectation posteBruno = new PosteAffectation("PUBESP-P2", stand, longue);
        posteBruno.setAnimateur(bruno);
        persistence.persist(new PlanningEvenement(JOUR, List.of(alice, bruno), List.of(posteAlice, posteBruno)));
        String hallId = referenceData
                .createEmplacement(new Emplacement(null, "Hall B", 47.2184, -1.5536))
                .getId();
        rattacherEmplacement("PUBESP-S1", hallId);
        publication.publier();

        given().when()
                .get("/api/espace-animateur/" + tokenOf("PUBESP-A"))
                .then()
                .statusCode(200)
                .body("pauses.size()", equalTo(1))
                .body("pauses[0].emplacementNom", equalTo("Hall B"))
                .body("pauses[0].emplacementLatitude", equalTo(47.2184f));
    }

    @Test
    void leRenvoiIndividuelRefuseTantQueRienNAEtePublie() {
        given().contentType(ContentType.JSON)
                .when()
                .post("/api/planning/envoi/animateur/PUBESP-A")
                .then()
                .statusCode(400)
                .body("message", containsString("pas encore été publié"));
    }

    /* ---------------------- A vacation that was deleted --------------------- */

    /**
     * Issue #576: deleting a créneau used to take the seat out of the published
     * plan at the very instant it left the working one. The comparison then saw
     * the same nothing on both sides — no écart, no recipient, and the person
     * who had just lost their Tuesday afternoon was precisely the one the
     * publication skipped.
     *
     * <p>The published side now carries its own day and hours, so a deletion
     * reads as what it is: a retrait, with a name on it and a sentence to
     * send.</p>
     */
    @Test
    void unCreneauSupprimeApresPublicationFaitUnRetraitEtUnDestinataire() {
        persistPlanSurDeuxJours();
        publication.publier();

        referenceData.deleteCreneau(CRENEAU_ID_LENDEMAIN);

        given().when()
                .get("/api/planning/publication")
                .then()
                .statusCode(200)
                .body("nombreConcernes", equalTo(1))
                .body("destinataires[0].animateurId", equalTo("PUBESP-A"))
                .body("destinataires[0].changements.size()", equalTo(1))
                .body("destinataires[0].changements[0]", containsString("Stand espace un"))
                .body("destinataires[0].changements[0]", containsString("retir"));
    }

    /**
     * And what the espace shows meanwhile: the vacation, still. The published
     * plan is a promise, and a promise does not stop having been made because
     * the grid moved — it is the publication that withdraws it, and the banner
     * then says so in the words that were mailed.
     */
    @Test
    void laVacationSupprimeeResteAfficheeJusquAuRetraitAnnonce() {
        persistPlanSurDeuxJours();
        publication.publier();

        referenceData.deleteCreneau(CRENEAU_ID_LENDEMAIN);

        given().when()
                .get("/api/espace-animateur/" + tokenOf("PUBESP-A"))
                .then()
                .statusCode(200)
                .body("postes.size()", equalTo(2));

        publication.publier();

        given().when()
                .get("/api/espace-animateur/" + tokenOf("PUBESP-A"))
                .then()
                .statusCode(200)
                .body("postes.size()", equalTo(1))
                .body("changements.size()", equalTo(1))
                .body("changements[0]", containsString("retir"));
    }

    /**
     * Rétrocompatibilité: a snapshot captured before issue #576 carries no day
     * of its own. It must keep resolving exactly as it used to — against
     * today's référentiel — and a seat it cannot resolve there is dropped, as
     * before. Pinned rather than assumed: the fallback is the whole reason the
     * new fields could be added without a migration.
     */
    @Test
    void unInstantanePublieSansDateRetombeSurLaResolutionDuJour() {
        persistPlanSurDeuxJours();
        publication.publier();
        stripVacationSnapshots();

        // Nothing moved in the référentiel: the plan reads exactly as it did.
        given().when()
                .get("/api/espace-animateur/" + tokenOf("PUBESP-A"))
                .then()
                .statusCode(200)
                .body("postes.size()", equalTo(2));

        referenceData.deleteCreneau(CRENEAU_ID_LENDEMAIN);

        given().when()
                .get("/api/espace-animateur/" + tokenOf("PUBESP-A"))
                .then()
                .statusCode(200)
                .body("postes.size()", equalTo(1));
    }

    /* ------------------- The frozen date of development ------------------ */

    /**
     * The espace computes its day marker in the browser: without the server's
     * frozen date in the view, jour J moves to that day and the espace of the
     * very people it reassigns stays on the phone's.
     */
    @Test
    void lEspaceRelaieLaDateFigeeEnDeveloppement() {
        QuarkusMock.installMockForType(new DevModeActif(), DevMode.class);
        clock.setMocked(JOUR, null);

        given().when()
                .get("/api/espace-animateur/" + tokenOf("PUBESP-A"))
                .then()
                .statusCode(200)
                .body("dateDuJourFigee", equalTo(JOUR.toString()))
                .body("heureDuJourFigee", nullValue());

        clock.setMocked(JOUR, LocalTime.of(14, 30));

        given().when()
                .get("/api/espace-animateur/" + tokenOf("PUBESP-A"))
                .then()
                .statusCode(200)
                .body("heureDuJourFigee", equalTo("14:30:00"));
    }

    /** The same rule as jour J: a row that reached a deployed instance is inert. */
    @Test
    void horsDeveloppementLEspaceNeRelaieAucuneDateFigee() {
        QuarkusMock.installMockForType(new DevModeActif(), DevMode.class);
        clock.setMocked(JOUR, LocalTime.of(14, 30));
        QuarkusMock.installMockForType(new DevMode(), DevMode.class);

        given().when()
                .get("/api/espace-animateur/" + tokenOf("PUBESP-A"))
                .then()
                .statusCode(200)
                .body("dateDuJourFigee", nullValue())
                .body("heureDuJourFigee", nullValue());
    }

    /* -------------------------------- Helpers ------------------------------ */

    @Test
    void lEspaceAnnonceLaPauseQueLaJourneePubliseDoit() {
        // Pinned rather than assumed: parametres_legaux survives
        // clearDatabase(), so a sibling class that wrote another break length
        // would otherwise decide what this test reads.
        ParametresLegaux legaux = parametres.getLegaux();
        legaux.setDureePauseMinutes(30);
        parametres.updateLegaux(legaux);

        Animateur alice = new Animateur("PUBESP-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur bruno = new Animateur("PUBESP-B", "Bruno", "Petit", LocalDate.of(1992, 2, 2), false);
        Stand stand = new Stand("PUBESP-S1", "Stand espace un", Set.of(), 2, 2, false);
        Creneau longue = new Creneau(CRENEAU_ID, 1, JOUR, LocalTime.of(13, 0), LocalTime.of(20, 0));
        PosteAffectation posteAlice = new PosteAffectation("PUBESP-P1", stand, longue);
        posteAlice.setAnimateur(alice);
        PosteAffectation posteBruno = new PosteAffectation("PUBESP-P2", stand, longue);
        posteBruno.setAnimateur(bruno);
        persistence.persist(new PlanningEvenement(JOUR, List.of(alice, bruno), List.of(posteAlice, posteBruno)));
        publication.publier();

        given().when()
                .get("/api/espace-animateur/" + tokenOf("PUBESP-A"))
                .then()
                .statusCode(200)
                .body("postes.size()", equalTo(1))
                .body("pauses.size()", equalTo(1))
                .body("pauses[0].date", equalTo(JOUR.toString()))
                .body("pauses[0].heureLimite", equalTo("19:00:00"))
                .body("pauses[0].debut", equalTo("19:00:00"))
                .body("pauses[0].fin", equalTo("19:30:00"))
                .body("pauses[0].dureeMinutes", equalTo(30))
                .body("pauses[0].standNom", equalTo("Stand espace un"))
                .body("pauses[0].relaisDisponible", equalTo(true));
    }

    /**
     * Alice on two days rather than one: deleting the second créneau then
     * leaves something to publish, which a one-seat plan would not — an empty
     * persisted plan is refused, and rightly so.
     */
    private void persistPlanSurDeuxJours() {
        Animateur alice = new Animateur("PUBESP-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur bruno = new Animateur("PUBESP-B", "Bruno", "Petit", LocalDate.of(1992, 2, 2), false);
        Stand stand = new Stand("PUBESP-S1", "Stand espace un", Set.of(), 1, 1, false);
        Creneau premier = new Creneau(CRENEAU_ID, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(12, 0));
        Creneau lendemain =
                new Creneau(CRENEAU_ID_LENDEMAIN, 2, JOUR.plusDays(1), LocalTime.of(14, 0), LocalTime.of(18, 0));
        PosteAffectation matin = new PosteAffectation("PUBESP-P1", stand, premier);
        matin.setAnimateur(alice);
        PosteAffectation apresMidi = new PosteAffectation("PUBESP-P2", stand, lendemain);
        apresMidi.setAnimateur(alice);
        persistence.persist(new PlanningEvenement(JOUR, List.of(alice, bruno), List.of(matin, apresMidi)));
    }

    /**
     * Rewrites the last published snapshot into the shape it had before issue
     * #576: seats with ids and nothing else. Done in SQL because no code path
     * writes that shape any more — which is exactly why the fallback needs a
     * test that does.
     */
    private void stripVacationSnapshots() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("""
                        UPDATE plan_snapshot
                        SET contenu = (
                            SELECT jsonb_agg(affectation - 'date' - 'heureDebut' - 'heureFin')
                            FROM jsonb_array_elements(contenu) AS affectation)
                        WHERE publie_le IS NOT NULL""")) {
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to strip the snapshot dates", e);
        }
    }

    private void persistPlan(String titulaireId) {
        Animateur alice = new Animateur("PUBESP-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur bruno = new Animateur("PUBESP-B", "Bruno", "Petit", LocalDate.of(1992, 2, 2), false);
        Stand stand = new Stand("PUBESP-S1", "Stand espace un", Set.of(), 1, 1, false);
        Creneau creneau = new Creneau(CRENEAU_ID, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(12, 0));
        PosteAffectation poste = new PosteAffectation("PUBESP-P1", stand, creneau);
        poste.setAnimateur("PUBESP-A".equals(titulaireId) ? alice : bruno);
        persistence.persist(new PlanningEvenement(JOUR, List.of(alice, bruno), List.of(poste)));
    }

    /**
     * Attaches an emplacement to a stand in SQL rather than through
     * {@code updateStand}: that one runs the referential's own validation, and
     * the stands persisted here carry no typologie — a rule this test has no
     * business satisfying to check what an espace displays.
     */
    private void rattacherEmplacement(String standId, String emplacementId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement("UPDATE stand SET emplacement_id = ? WHERE id = ?")) {
            ps.setString(1, emplacementId);
            ps.setString(2, standId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to attach the emplacement to stand " + standId, e);
        }
    }

    /** Renamed in SQL, for the same reason {@link #rattacherEmplacement} writes there. */
    private void renommerStand(String standId, String nom) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("UPDATE stand SET nom = ? WHERE id = ?")) {
            ps.setString(1, nom);
            ps.setString(2, standId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to rename stand " + standId, e);
        }
    }

    /** {@code clearDatabase()} keeps the emplacements — each test starts without any. */
    private void oublierEmplacements() {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE stand SET emplacement_id = NULL");
            statement.executeUpdate("DELETE FROM emplacement");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clear the emplacements", e);
        }
    }

    private void donnerEmail(String animateurId, String email) {
        Animateur animateur = referenceData.listAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals(animateurId))
                .findFirst()
                .orElseThrow();
        animateur.setEmail(email);
        referenceData.updateAnimateur(animateurId, animateur);
    }

    private String tokenOf(String animateurId) {
        return referenceData.listAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals(animateurId))
                .findFirst()
                .orElseThrow()
                .getAccessToken();
    }

    /** A published snapshot survives {@code clearDatabase()} — see PublicationResourceTest. */
    private void forgetPublications() {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM plan_snapshot");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clear the plan snapshots", e);
        }
    }
}
