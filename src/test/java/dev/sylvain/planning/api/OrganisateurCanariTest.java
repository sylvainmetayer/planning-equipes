package dev.sylvain.planning.api;

import static dev.sylvain.planning.OidcTests.token;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sylvain.planning.OidcTests;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.analyse.KpiHistoriqueService;
import dev.sylvain.planning.service.compte.Compte;
import dev.sylvain.planning.service.compte.CompteService;
import dev.sylvain.planning.service.compte.RoleHabilitation;
import dev.sylvain.planning.service.edition.EditionService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The canary net. An administrator seeds an edition the organiser has no right
 * on — named {@code CANARI-…}, with a snapshot and a KPI row — then an
 * organiser limited to {@code E1} reads every parameterless {@code GET} the
 * published contract lists: no body may carry the canary. A route that hands
 * out another edition's data fails here whoever wrote it and however it got
 * the data, which is what no per-route test can promise for the next route.
 */
@QuarkusTest
@TestProfile(OidcTests.Profil.class)
class OrganisateurCanariTest {

    /** Streams never end; their content is covered by the solver queue tests. */
    private static final List<String> FLUX = List.of("/api/jobs/stream");

    @Inject
    CompteService comptes;

    @Inject
    EditionService editions;

    @Inject
    EditionContext editionContext;

    @Inject
    KpiHistoriqueService kpi;

    @Inject
    javax.sql.DataSource dataSource;

    private String bearerOf(RoleHabilitation role, String editionId) {
        String email = "canari-" + UUID.randomUUID() + "@example.org";
        Compte compte = comptes.create(email, null);
        comptes.grant(compte.id(), role, editionId, null);
        return token("s-" + UUID.randomUUID(), email, true);
    }

    @Test
    void aucuneRouteDeLectureNeRendLesDonneesDUneEditionHorsDroits() throws Exception {
        String canari = "CANARI-" + UUID.randomUUID();
        String editionId =
                editions.create(new Edition(null, canari, false, null)).getId();
        try {
            try (var connection = dataSource.getConnection();
                    var ps = connection.prepareStatement("""
                            INSERT INTO plan_snapshot (edition_id, libelle, automatique, nombre_affectations,
                                cree_le, contenu, consignes)
                            VALUES (?, ?, false, 0, now(), '[]'::jsonb, '[]'::jsonb)""")) {
                ps.setString(1, editionId);
                ps.setString(2, canari);
                ps.executeUpdate();
            }
            editionContext.executeIn(editionId, () -> kpi.recordAfterSolve(1L));

            // Positive control: an administrator does see what was seeded, so the
            // organiser's silence below means something.
            String admin = bearerOf(RoleHabilitation.ADMIN, null);
            for (String chemin : List.of("/api/planning/snapshots/comparables", "/api/kpi/historique")) {
                assertThat(given().header("X-Edition-Id", "E1")
                                .header("Authorization", admin)
                                .get(chemin)
                                .asString())
                        .as(chemin)
                        .contains(canari);
            }
            String organisateur = bearerOf(RoleHabilitation.ORGANISATEUR, "E1");
            JsonNode paths = new ObjectMapper()
                    .readTree(Files.readString(Path.of("docs/schema/openapi.json")))
                    .get("paths");
            List<String> fuites = new ArrayList<>();
            int lues = 0;
            for (var it = paths.fields(); it.hasNext(); ) {
                var route = it.next();
                String chemin = route.getKey();
                if (!route.getValue().has("get") || chemin.contains("{") || FLUX.contains(chemin)) {
                    continue;
                }
                var reponse = given().header("X-Edition-Id", "E1")
                        .header("Authorization", organisateur)
                        .get(chemin);
                lues++;
                if (reponse.asString().contains("CANARI-")) {
                    fuites.add(chemin + " -> " + reponse.statusCode());
                }
            }
            assertThat(lues).as("routes lues").isGreaterThan(50);
            assertThat(fuites).as("routes qui rendent l'édition hors droits").isEmpty();
        } finally {
            editions.delete(editionId);
        }
    }
}
