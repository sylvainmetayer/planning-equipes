package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.PlanningPersistenceService;
import dev.sylvain.planning.service.ReferenceDataService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Importing a scenario is a DIFF, not a blind replacement: the animateurs of
 * the file are updated IN PLACE (access token and e-mail kept — the espace
 * links already printed survive), only those absent from the file disappear,
 * and the solved planning is cleared properly (assignments AND the trace of the
 * solve). The impact endpoint provides the figures of the confirmation
 * dialog.
 */
@QuarkusTest
class ImportScenarioPreservationTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 15);
    private static final long CRENEAU_ID = 9601L;
    private static final String EMAIL_ALICE = "alice-import@example.org";

    private static final String SCENARIO = """
            festival:
              dateDebut: 2026-07-15

            creneaux:
              - id: IMP-J1
                jour: 1
                date: 2026-07-15
                heureDebut: "10:00"
                heureFin: "12:00"

            stands:
              - id: IMP-S1
                nom: Stand importé
                typologiesProposees:
                  - STRATEGIE
                effectifMin: 1
                effectifMax: 1
                reserveMajeurs: false

            animateurs:
              - id: IMP-A
                prenom: Alicia
                nom: Martin
                dateNaissance: 1990-01-01
                manager: false
                competences:
                  STRATEGIE: DEBUTANT
              - id: IMP-C
                prenom: Chloé
                nom: Nouvelle
                dateNaissance: 1995-03-03
                manager: false
                competences:
                  STRATEGIE: DEBUTANT
            """;

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    ReferenceDataService referenceData;

    @BeforeEach
    void seed() {
        Animateur alice = new Animateur("IMP-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur bruno = new Animateur("IMP-B", "Bruno", "Petit", LocalDate.of(1992, 2, 2), false);
        Stand standUn = new Stand("IMP-S1", "Stand un", Set.of(), 1, 1, false);
        Stand standDeux = new Stand("IMP-S2", "Stand deux", Set.of(), 1, 1, false);
        Creneau creneau = new Creneau(CRENEAU_ID, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(12, 0));
        PosteAffectation posteUn = new PosteAffectation("IMP-P1", standUn, creneau);
        posteUn.setAnimateur(alice);
        PosteAffectation posteDeux = new PosteAffectation("IMP-P2", standDeux, creneau);
        posteDeux.setAnimateur(bruno);
        persistence.persist(new PlanningFestival(JOUR, List.of(alice, bruno), List.of(posteUn, posteDeux)));
        donnerEmail("IMP-A", EMAIL_ALICE);
    }

    @Test
    void lImpactChiffreLeReferentielEtLePlanningAvantImport() {
        given().when().get("/api/reference-data/impact-import")
                .then()
                .statusCode(200)
                .body("animateurs", greaterThanOrEqualTo(2))
                .body("stands", greaterThanOrEqualTo(2))
                .body("postes", greaterThanOrEqualTo(2))
                .body("planningResolu", equalTo(true));
    }

    @Test
    void lImportConserveJetonEtEmailDesAnimateursDuFichierEtSupprimeLesAbsents() {
        String tokenBefore = tokenOf("IMP-A");

        // Bytes, not String: RestAssured has no encoder for x-yaml text.
        given().contentType("application/x-yaml")
                .body(SCENARIO.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .when().post("/api/reference-data/import-scenario-fichier")
                .then()
                .statusCode(200);

        // IMP-A came in the file: updated in place, token AND e-mail intact
        // (the file carries no email — it must not wipe the stored one).
        Animateur alice = animateur("IMP-A");
        assertThat(alice.getPrenom()).isEqualTo("Alicia");
        assertThat(alice.getJetonAcces()).isEqualTo(tokenBefore);
        assertThat(alice.getEmail()).isEqualTo(EMAIL_ALICE);

        // IMP-C is new and gets a fresh token; IMP-B was absent: gone.
        assertThat(animateur("IMP-C").getJetonAcces()).isNotBlank();
        assertThat(referenceData.listAnimateurs()).noneMatch(a -> a.getId().equals("IMP-B"));

        // The resolved planning is erased coherently: no seat left, and no
        // stale "résolu le …" claim either.
        assertThat(persistence.countPersistedAssignments()).isZero();
        assertThat(persistence.loadResolution()).isNull();
    }

    private Animateur animateur(String id) {
        return referenceData.listAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Animateur absent : " + id));
    }

    private void donnerEmail(String animateurId, String email) {
        Animateur animateur = animateur(animateurId);
        animateur.setEmail(email);
        referenceData.updateAnimateur(animateurId, animateur);
    }

    private String tokenOf(String animateurId) {
        return animateur(animateurId).getJetonAcces();
    }
}
