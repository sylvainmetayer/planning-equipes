package dev.sylvain.planning.api;

import static dev.sylvain.planning.OidcTests.token;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sylvain.planning.OidcTests;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.compte.Compte;
import dev.sylvain.planning.service.compte.CompteService;
import dev.sylvain.planning.service.compte.RoleHabilitation;
import dev.sylvain.planning.service.edition.EditionService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The canary net of the lecteur right (ADR 0099): a lecteur of {@code E1} calls
 * every route the published contract lists. No write gets through (403 on every
 * non-{@code GET}, but the PDF and ICS renders), no raw export either, and the
 * instance's routes stay closed — whoever wrote the route and however it
 * answers, which is what no per-route test promises for the next one.
 */
@QuarkusTest
@TestProfile(OidcTests.Profil.class)
class LecteurCanariTest {

    /** Routes answered to anybody (a token is their credential) or to another role's prefix: not this net's. */
    private static final List<String> HORS_PERIMETRE = List.of(
            "/api/auth/",
            "/api/espace-animateur/",
            "/api/abonnements/",
            "/api/mural/",
            "/api/config",
            "/api/branding",
            "/api/mentions-legales",
            "/api/responsable/",
            "/api/rh/",
            "/api/jobs/stream");

    /** The renders the lecteur is allowed to ask for (the only open non-GET). */
    private static final String RENDUS = "/api/planning/export/";

    @Inject
    CompteService comptes;

    @Inject
    EditionService editions;

    @Inject
    EditionContext editionContext;

    @Inject
    ReferenceDataService referenceData;

    /** An animateur of E1 holding an espace token, which no response read by a lecteur may carry. */
    private record Tokened(String id, String token) {}

    private Tokened tokenedAnimateur() {
        return editionContext.executeIn("E1", () -> {
            Animateur animateur = referenceData.createAnimateur(
                    new Animateur(null, "Jeton", "Lecteur", LocalDate.of(1990, 1, 1), false));
            return new Tokened(animateur.getId(), referenceData.regenerateAnimateurToken(animateur.getId()));
        });
    }

    private String bearerOf(String email) {
        return token("s-" + UUID.randomUUID(), email, true);
    }

    private String bearer(RoleHabilitation role, String editionId) {
        String email = "lecteur-" + UUID.randomUUID() + "@example.org";
        Compte compte = comptes.create(email, null);
        comptes.grant(compte.id(), role, editionId, null);
        return bearerOf(email);
    }

    @Test
    void aLecteurReadsButNothingElseGetsThrough() throws Exception {
        String lecteur = bearer(RoleHabilitation.LECTEUR, "E1");
        Tokened tokened = tokenedAnimateur();
        try {
            sweep(lecteur, tokened);
        } finally {
            editionContext.executeIn("E1", () -> referenceData.deleteAnimateur(tokened.id()));
        }
    }

    private void sweep(String lecteur, Tokened tokened) throws Exception {
        List<String> fuites = new ArrayList<>();
        JsonNode paths = new ObjectMapper()
                .readTree(Files.readString(Path.of("docs/schema/openapi.json")))
                .get("paths");
        List<String> ecritures = new ArrayList<>();
        int refusees = 0;
        int lues = 0;
        for (var it = paths.fields(); it.hasNext(); ) {
            var route = it.next();
            String chemin = route.getKey();
            if (HORS_PERIMETRE.stream().anyMatch(chemin::startsWith)) {
                continue;
            }
            String url = chemin.replaceAll("\\{[^}]+}", "1");
            for (var verbs = route.getValue().fieldNames(); verbs.hasNext(); ) {
                String verb = verbs.next();
                if (!Set.of("post", "put", "patch", "delete").contains(verb)) {
                    continue;
                }
                Response reponse = given().header("X-Edition-Id", "E1")
                        .header("Authorization", lecteur)
                        .contentType("application/json")
                        .body("{}")
                        .request(verb.toUpperCase(), url);
                int code = reponse.statusCode();
                // 415 / 404: the router refused before the route was reached (a multipart body, an id
                // that is no number) — nothing ran either way.
                if (code == 403) {
                    refusees++;
                } else if (!chemin.startsWith(RENDUS) && code != 415 && code != 404) {
                    ecritures.add(verb + " " + chemin + " -> " + code);
                }
            }
            if (route.getValue().has("get") && !chemin.equals("/api/jobs/stream")) {
                String lu = given().header("X-Edition-Id", "E1")
                        .header("Authorization", lecteur)
                        .get(chemin.replace("{id}", tokened.id()).replaceAll("\\{[^}]+}", "1"))
                        .asString();
                if (lu.contains(tokened.token())) {
                    fuites.add(chemin);
                }
                lues++;
            }
        }
        assertThat(fuites)
                .as("réponses lues par un lecteur qui portent un jeton d'espace")
                .isEmpty();
        // The same read by an organiser still carries it: the mask is the lecteur's, not everyone's.
        assertThat(given().header("X-Edition-Id", "E1")
                        .header("Authorization", bearer(RoleHabilitation.ORGANISATEUR, "E1"))
                        .get("/api/animateurs")
                        .asString())
                .contains(tokened.token());
        assertThat(ecritures).as("routes d'écriture que le lecteur atteint").isEmpty();
        assertThat(refusees).as("écritures refusées en 403").isGreaterThan(100);
        assertThat(lues).as("routes lues").isGreaterThan(50);
    }

    @Test
    void theReadsAndTheRendersStayOpenAndTheRawExportsAreClosed() {
        String lecteur = bearer(RoleHabilitation.LECTEUR, "E1");
        for (String ouverte : List.of("/api/stands", "/api/animateurs", "/api/planning/persisted", "/api/historique")) {
            assertThat(given().header("X-Edition-Id", "E1")
                            .header("Authorization", lecteur)
                            .get(ouverte)
                            .statusCode())
                    .as(ouverte)
                    .isLessThan(300);
        }
        for (String fermee : List.of(
                "/api/planning/equite/export",
                "/api/exports/archive-evenement",
                "/api/planning/export-scenario",
                "/api/reference-data/export-csv",
                "/api/planning/hours/export",
                "/api/comptes")) {
            assertThat(given().header("X-Edition-Id", "E1")
                            .header("Authorization", lecteur)
                            .get(fermee)
                            .statusCode())
                    .as(fermee)
                    .isEqualTo(403);
        }
        // A render passes the filter (it answers 400: no body to render, not 403).
        assertThat(given().header("X-Edition-Id", "E1")
                        .header("Authorization", lecteur)
                        .contentType("application/json")
                        .post("/api/planning/export/ics/all")
                        .statusCode())
                .isNotEqualTo(403);
    }

    /** One account, two rights: the lecteur's refusal stops at the edition it reads. */
    @Test
    void aLecteurOfOneEditionIsAnOrganiserOfAnotherAndOnlyThereWrites() {
        String autre = editions.create(new Edition(null, "LECTEUR-" + UUID.randomUUID(), false, null))
                .getId();
        try {
            String email = "mixte-" + UUID.randomUUID() + "@example.org";
            Compte compte = comptes.create(email, null);
            comptes.grant(compte.id(), RoleHabilitation.LECTEUR, "E1", null);
            comptes.grant(compte.id(), RoleHabilitation.ORGANISATEUR, autre, null);
            String bearer = bearerOf(email);

            assertThat(given().header("X-Edition-Id", "E1")
                            .header("Authorization", bearer)
                            .delete("/api/stands/inconnu")
                            .statusCode())
                    .isEqualTo(403);
            assertThat(given().header("X-Edition-Id", autre)
                            .header("Authorization", bearer)
                            .delete("/api/stands/inconnu")
                            .statusCode())
                    .isNotEqualTo(403);
            // Both are read; a third one is neither read nor written.
            assertThat(given().header("X-Edition-Id", "E1")
                            .header("Authorization", bearer)
                            .get("/api/stands")
                            .statusCode())
                    .isEqualTo(200);
            // No edition designated: judged on the rights that cover every edition, and this one has none.
            assertThat(given().header("Authorization", bearer)
                            .delete("/api/stands/inconnu")
                            .statusCode())
                    .isEqualTo(403);
        } finally {
            editions.delete(autre);
        }
    }

    /** The other doors keep their own rules: a lecteur logs out, and its RH right still writes the contacts. */
    @Test
    void theLecteurRefusalStaysOnTheOrganisersDoor() {
        String email = "lecteur-rh-" + UUID.randomUUID() + "@example.org";
        Compte compte = comptes.create(email, null);
        comptes.grant(compte.id(), RoleHabilitation.LECTEUR, "E1", null);
        comptes.grant(compte.id(), RoleHabilitation.RH, "E1", null);
        String bearer = bearerOf(email);

        assertThat(given().header("X-Edition-Id", "E1")
                        .header("Authorization", bearer)
                        .contentType("application/json")
                        .body("{}")
                        .put("/api/rh/editions/E1/contacts/inconnu")
                        .statusCode())
                .as("contacts written by the RH right")
                .isNotEqualTo(403);
        assertThat(given().header("Authorization", bearer)
                        .post("/api/auth/logout")
                        .statusCode())
                .as("logout")
                .isLessThan(300);
    }

    /** A lecteur right names an edition: « all of them » is refused when granted. */
    @Test
    void aLecteurRightNamesAnEdition() {
        String email = "sans-" + UUID.randomUUID() + "@example.org";
        Compte compte = comptes.create(email, null);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> comptes.grant(compte.id(), RoleHabilitation.LECTEUR, null, null))
                .hasMessageContaining("porte une édition");
    }
}
