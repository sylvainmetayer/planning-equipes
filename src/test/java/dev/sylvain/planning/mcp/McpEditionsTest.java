package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.service.EditionService;

/**
 * How the {@code edition} argument of an MCP tool is turned into an edition id
 * (issue #181), without a container: the rules are pure decisions on a list.
 */
class McpEditionsTest {

    private final McpEditions editions = editions(
            new Edition("2026", "Année 2026", true, null),
            new Edition("2026-canicule", "Plan canicule", false, null),
            new Edition("2025", "Année 2025", false, null));

    @Test
    void aucuneEditionDemandeeLaisseLappelDansLeditionCourante() {
        assertThat(editions.solve(null)).isNull();
        assertThat(editions.solve("   ")).isNull();
    }

    @Test
    void unIdEstReconnuQuelleQueSoitLaCasseEtLesEspaces() {
        assertThat(editions.solve("2026-canicule")).isEqualTo("2026-canicule");
        assertThat(editions.solve("  2026-CANICULE ")).isEqualTo("2026-canicule");
    }

    @Test
    void unNomEstAccepteAussi() {
        assertThat(editions.solve("Plan canicule")).isEqualTo("2026-canicule");
    }

    @Test
    void uneEditionInconnueEchoueEtEnumereCeQuiExiste() {
        assertThatThrownBy(() -> editions.solve("Année 2042"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Édition inconnue")
                .hasMessageContaining("2026 (Année 2026)")
                .hasMessageContaining("lister_editions");
    }

    @Test
    void unNomPorteParPlusieursEditionsEstRefuseAuLieuDetreArbitre() {
        McpEditions homonymes = editions(
                new Edition("a", "Année 2026", true, null),
                new Edition("b", "Année 2026", false, null));

        assertThatThrownBy(() -> homonymes.solve("Année 2026"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Plusieurs éditions")
                .hasMessageContaining("a, b");
    }

    @Test
    void unIdPrimeSurUnNomHomonyme() {
        McpEditions ambigu = editions(
                new Edition("2026", "Plan canicule", false, null),
                new Edition("canicule", "2026", true, null));

        assertThat(ambigu.solve("2026")).isEqualTo("2026");
    }

    private static McpEditions editions(Edition... connues) {
        McpEditions resolveur = new McpEditions();
        resolveur.editionService = new EditionService() {
            @Override
            public List<Edition> listEditions() {
                return List.of(connues);
            }
        };
        return resolveur;
    }
}
