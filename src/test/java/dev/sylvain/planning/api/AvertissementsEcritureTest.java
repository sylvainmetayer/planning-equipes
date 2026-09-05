package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * The cross-field warnings a write reports, end to end: they travel in the
 * <b>success</b> body next to the entity, and the entity is really there
 * afterwards.
 *
 * <p>That second half is the point of the feature and of this class. A warning
 * that refused the write would be a plain refusal wearing another name, and the
 * doctrine of the repository is that only what is <em>certainly</em>
 * unsatisfiable is refused. Recording an off day outside the event, a birth date
 * that makes somebody a jeune travailleur, or a timeslot wider than any stand
 * opens are all legitimate things to type.</p>
 *
 * <p>The class starts from an empty database and rebuilds a three-row
 * referential of its own: the warnings are read against the edition's whole
 * grid, so a leftover créneau from another class would move the bounds under
 * the assertions.</p>
 */
@QuarkusTest
class AvertissementsEcritureTest {

    private static final String JOUR = "2027-06-10";

    @BeforeEach
    void resetReferentiel() {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given()
                .contentType("application/json")
                .body("""
                        {
                          "id":"AVERT-STAND",
                          "nom":"Stand de l'après-midi",
                          "typologiesProposees":[],
                          "effectifMin":1,
                          "effectifMax":2,
                          "reserveMajeurs":false,
                          "horaires":[
                            {"mode":"OUVERTURE","jours":"TOUS","fenetres":[{"heureDebut":"14:00:00"}]}
                          ]
                        }
                        """)
                .when().post("/api/stands")
                .then().statusCode(200);
    }

    /**
     * Leaves the sample scenario behind, as the other classes of the suite
     * expect it. Per test rather than per class: a static {@code @AfterAll}
     * runs after Quarkus has stopped when this class happens to be the last
     * one, and the restore then never reaches the server.
     */
    @AfterEach
    void restoreScenario() {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given().when().post("/api/reference-data/import-scenario?name=scenario.yml").then().statusCode(200);
    }

    private static Object postCreneau(String heureDebut, String heureFin, String... attentes) {
        var reponse = given()
                .contentType("application/json")
                .body("""
                        {
                          "date":"%s",
                          "heureDebut":"%s",
                          "heureFin":"%s"
                        }
                        """.formatted(JOUR, heureDebut, heureFin))
                .when().post("/api/creneaux")
                .then().statusCode(200)
                .body("creneau.id", notNullValue());
        if (attentes.length == 0) {
            reponse.body("avertissements", empty());
        } else {
            reponse.body("avertissements.type", contains(attentes));
        }
        return reponse.extract().path("creneau.id");
    }

    /**
     * The stand opens 14:00 by a <b>recurring</b> rule and carries no dated
     * window at all: a check reading the dated lists alone would call it shut
     * and shout on both timeslots below.
     */
    @Test
    void unCreneauDebordantLOuvertureDesStandsEstEcritEtSignale() {
        Object id = postCreneau("10:00:00", "18:00:00", "CRENEAU_DEBORDE_OUVERTURE_STANDS");

        // Written despite the warning, and readable as typed.
        given().when().get("/api/creneaux")
                .then().statusCode(200)
                .body("find { it.id == " + id + " }.heureDebut", containsString("10:00"));
    }

    @Test
    void unCreneauDansLAmplitudeDesStandsNeProduitAucunAvertissement() {
        postCreneau("14:00:00", "18:00:00");
    }

    /**
     * The stand side of the same coupling: a window that overlaps no créneau
     * of its day is written, and said in the success body — the grid only
     * ever said it on the review screen before.
     */
    @Test
    void unStandDontLaFenetreNeRecoupeAucunCreneauEstEcritEtSignale() {
        postCreneau("14:00:00", "18:00:00");

        given()
                .contentType("application/json")
                .body("""
                        {
                          "id":"AVERT-MATIN",
                          "nom":"Stand du matin",
                          "typologiesProposees":[],
                          "effectifMin":1,
                          "effectifMax":1,
                          "reserveMajeurs":false,
                          "horaires":[
                            {"mode":"OUVERTURE","jours":"TOUS","fenetres":[{"heureDebut":"08:00:00","heureFin":"10:00:00"}]}
                          ]
                        }
                        """)
                .when().post("/api/stands")
                .then().statusCode(200)
                .body("stand.id", equalTo("AVERT-MATIN"))
                .body("avertissements.type", contains("STAND_FENETRE_SANS_EFFET", "STAND_JAMAIS_OUVERT"))
                .body("avertissements[0].message", containsString("08:00"));

        // Written despite the warnings, rules included.
        given().when().get("/api/stands")
                .then().statusCode(200)
                .body("find { it.id == 'AVERT-MATIN' }.horaires.size()", equalTo(1));
    }

    /**
     * The wiring the pure analyzer cannot prove: the edit path reads the stand
     * as it stands in the database and compares. Renaming a stand whose window
     * has always been useless must stay silent — otherwise every bulk edit,
     * which issues one PUT per row, re-shouts about data nobody touched.
     */
    @Test
    void renommerUnStandDontLaFenetreEstDejaSansEffetNeRedItRien() {
        postCreneau("14:00:00", "18:00:00");
        given()
                .contentType("application/json")
                .body("""
                        {
                          "id":"AVERT-MATIN","nom":"Stand du matin","typologiesProposees":[],
                          "effectifMin":1,"effectifMax":1,"reserveMajeurs":false,
                          "horaires":[
                            {"mode":"OUVERTURE","jours":"TOUS","fenetres":[{"heureDebut":"08:00:00","heureFin":"10:00:00"}]}
                          ]
                        }
                        """)
                .when().post("/api/stands")
                .then().statusCode(200)
                .body("avertissements.type", hasItem("STAND_FENETRE_SANS_EFFET"));

        given()
                .contentType("application/json")
                .body("""
                        {
                          "id":"AVERT-MATIN","nom":"Renommé","typologiesProposees":[],
                          "effectifMin":1,"effectifMax":1,"reserveMajeurs":false,
                          "horaires":[
                            {"mode":"OUVERTURE","jours":"TOUS","fenetres":[{"heureDebut":"08:00:00","heureFin":"10:00:00"}]}
                          ]
                        }
                        """)
                .when().put("/api/stands/AVERT-MATIN")
                .then().statusCode(200)
                .body("stand.nom", equalTo("Renommé"))
                .body("avertissements", empty());
    }

    @Test
    void unStandCoherentNeProduitAucunAvertissementEtRenommerNeRedItRien() {
        postCreneau("14:00:00", "18:00:00");

        given()
                .contentType("application/json")
                .body("""
                        {
                          "id":"AVERT-STAND",
                          "nom":"Renommé",
                          "typologiesProposees":[],
                          "effectifMin":1,
                          "effectifMax":2,
                          "reserveMajeurs":false,
                          "horaires":[
                            {"mode":"OUVERTURE","jours":"TOUS","fenetres":[{"heureDebut":"14:00:00"}]}
                          ]
                        }
                        """)
                .when().put("/api/stands/AVERT-STAND")
                .then().statusCode(200)
                .body("stand.nom", equalTo("Renommé"))
                .body("avertissements", empty());
    }

    /**
     * Both animateur warnings at once, and the fiche written all the same —
     * the off day included, which is what proves nothing was rolled back.
     */
    @Test
    void unAnimateurMineurEtIndisponibleHorsBornesEstEcritEtSignale() {
        postCreneau("14:00:00", "18:00:00");

        given()
                .contentType("application/json")
                .body("""
                        {
                          "id":"AVERT-A1",
                          "prenom":"Camille",
                          "nom":"Durand",
                          "dateNaissance":"2010-06-11",
                          "joursIndisponibles":["2027-08-15"]
                        }
                        """)
                .when().post("/api/animateurs")
                .then().statusCode(200)
                .body("animateur.id", equalTo("AVERT-A1"))
                .body("avertissements.type", hasItem("MINEUR_PENDANT_EVENEMENT"))
                .body("avertissements.type", hasItem("INDISPONIBILITE_HORS_EVENEMENT"))
                .body("avertissements.find { it.type == 'MINEUR_PENDANT_EVENEMENT' }.message",
                        containsString("2027-06-10"));

        given().when().get("/api/animateurs")
                .then().statusCode(200)
                .body("find { it.id == 'AVERT-A1' }.joursIndisponibles", contains("2027-08-15"));
    }

    /** Same two rules on the edit path, and both of them silent this time. */
    @Test
    void unAnimateurCoherentModifieNeProduitAucunAvertissement() {
        postCreneau("14:00:00", "18:00:00");
        given()
                .contentType("application/json")
                .body("{\"id\":\"AVERT-A2\",\"prenom\":\"Alix\",\"nom\":\"Martin\","
                        + "\"dateNaissance\":\"1990-01-01\"}")
                .when().post("/api/animateurs")
                .then().statusCode(200)
                .body("avertissements", empty());

        given()
                .contentType("application/json")
                .body("{\"id\":\"AVERT-A2\",\"prenom\":\"Alix\",\"nom\":\"Martin\","
                        + "\"dateNaissance\":\"1990-01-01\",\"joursIndisponibles\":[\"" + JOUR + "\"]}")
                .when().put("/api/animateurs/AVERT-A2")
                .then().statusCode(200)
                .body("avertissements", empty());
    }

    /** The edit path warns too: the same fiche, moved to a birth date that makes it a minor. */
    @Test
    void laModificationDUnAnimateurSignaleAussi() {
        postCreneau("14:00:00", "18:00:00");
        given()
                .contentType("application/json")
                .body("{\"id\":\"AVERT-A3\",\"prenom\":\"Sacha\",\"nom\":\"Roux\","
                        + "\"dateNaissance\":\"1990-01-01\"}")
                .when().post("/api/animateurs")
                .then().statusCode(200);

        given()
                .contentType("application/json")
                .body("{\"id\":\"AVERT-A3\",\"prenom\":\"Sacha\",\"nom\":\"Roux\","
                        + "\"dateNaissance\":\"2012-01-01\"}")
                .when().put("/api/animateurs/AVERT-A3")
                .then().statusCode(200)
                .body("avertissements.type", contains("MINEUR_PENDANT_EVENEMENT"));

        given().when().get("/api/animateurs")
                .then().statusCode(200)
                .body("find { it.id == 'AVERT-A3' }.dateNaissance", equalTo("2012-01-01"));
    }

    /**
     * The wiring the pure analyzer cannot prove: the edit path really does read
     * the fiche as it stood before. A bulk edit sends the whole merged fiche —
     * date de naissance included — one {@code PUT} per row, so a minor whose
     * birth date nobody touched must come back silent.
     */
    @Test
    void modifierUnMineurSansToucherSaDateDeNaissanceNeSignaleRien() {
        postCreneau("14:00:00", "18:00:00");
        given()
                .contentType("application/json")
                .body("{\"id\":\"AVERT-A5\",\"prenom\":\"Noa\",\"nom\":\"Blanc\","
                        + "\"dateNaissance\":\"2012-01-01\"}")
                .when().post("/api/animateurs")
                .then().statusCode(200)
                .body("avertissements.type", contains("MINEUR_PENDANT_EVENEMENT"));

        // The very shape ReferenceDataStore.saveMany sends: the whole fiche,
        // one field of it changed.
        given()
                .contentType("application/json")
                .body("{\"id\":\"AVERT-A5\",\"prenom\":\"Noa\",\"nom\":\"Blanc\","
                        + "\"dateNaissance\":\"2012-01-01\",\"email\":\"noa@example.org\"}")
                .when().put("/api/animateurs/AVERT-A5")
                .then().statusCode(200)
                .body("avertissements", empty());
    }

    /**
     * A warning shown by the browser is written to a log that survives the
     * session (docs/rgpd.md §7): the sentence names the animateur by their id
     * and never by their identity nor their date de naissance.
     */
    @Test
    void leMessageDeMinoriteNeNommeNiLIdentiteNiLaDateDeNaissance() {
        postCreneau("14:00:00", "18:00:00");

        given()
                .contentType("application/json")
                .body("""
                        {
                          "id":"AVERT-A6",
                          "prenom":"Camille",
                          "nom":"Durand",
                          "dateNaissance":"2010-06-11"
                        }
                        """)
                .when().post("/api/animateurs")
                .then().statusCode(200)
                .body("avertissements[0].message", containsString("AVERT-A6"))
                .body("avertissements[0].message", not(containsString("Camille")))
                .body("avertissements[0].message", not(containsString("Durand")))
                .body("avertissements[0].message", not(containsString("2010-06-11")));
    }

    /**
     * A gap day is inside the span, so it is not "outside the event" — but the
     * availability circuit only ever keeps the dates of the créneaux, so the
     * day is doomed all the same. Accepting data known to be condemned without
     * saying so is the worse of the two.
     */
    @Test
    void uneIndisponibiliteSurUnJourSansCreneauEstSignaleeAPart() {
        postCreneau("14:00:00", "18:00:00");
        given()
                .contentType("application/json")
                .body("""
                        {
                          "date":"2027-06-14",
                          "heureDebut":"14:00:00",
                          "heureFin":"18:00:00"
                        }
                        """)
                .when().post("/api/creneaux")
                .then().statusCode(200);

        given()
                .contentType("application/json")
                .body("""
                        {
                          "id":"AVERT-A7",
                          "prenom":"Sacha",
                          "nom":"Roux",
                          "dateNaissance":"1990-01-01",
                          "joursIndisponibles":["2027-06-12"]
                        }
                        """)
                .when().post("/api/animateurs")
                .then().statusCode(200)
                .body("avertissements.type", contains("INDISPONIBILITE_JOUR_SANS_CRENEAU"));
    }

    /**
     * An edition with no créneau has no bounds, and a warning invented there
     * would be a false one on the very screen a new edition starts from.
     */
    @Test
    void sansAucunCreneauAucuneBorneNExisteEtRienNEstSignale() {
        given()
                .contentType("application/json")
                .body("""
                        {
                          "id":"AVERT-A4",
                          "prenom":"Dominique",
                          "nom":"Petit",
                          "dateNaissance":"2015-01-01",
                          "joursIndisponibles":["1999-01-01"]
                        }
                        """)
                .when().post("/api/animateurs")
                .then().statusCode(200)
                .body("avertissements", empty());
    }
}
