package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.service.BusinessError;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * The three calls of issue #529, through the real CDI chain: what the assistant
 * gets back is the sentence the domain wrote, not « Internal error ».
 *
 * <p>Three tools of three different families on purpose — the defect was in
 * the MCP floor, not in any one tool, and the interceptor is bound to the tool
 * classes rather than written into them. The fourth case is the one an
 * assistant hits before the tool body ever runs: an edition that does not
 * exist is refused by {@code EditionCibleeInterceptor}, and that refusal —
 * the one listing the editions there are — has to travel too, which is what
 * places {@code RefusMetierInterceptor} outside it.</p>
 */
@QuarkusTest
class RefusMetierMcpToolsTest {

    @Inject
    CreneauMcpTools creneauTools;

    @Inject
    JourneeTypeMcpTools journeeTypeTools;

    @Test
    void uneDateAuMauvaisFormatDitLeFormatAttendu() {
        assertThatThrownBy(() -> creneauTools.creer_creneau("18/07/2026", "09:00", "12:00", null, null))
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("18/07/2026")
                .hasMessageContaining("AAAA-MM-JJ");
    }

    @Test
    void uneRecurrenceSansFenetreDonneUnExemple() {
        assertThatThrownBy(() ->
                        creneauTools.creer_creneaux_recurrents(null, "", null, null, null, null, null, null, null))
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("fenetres")
                .hasMessageContaining("10:00-12:00,14:00-18:00");
    }

    @Test
    void uneVacationIllisibleEstCitee() {
        assertThatThrownBy(() -> journeeTypeTools.definir_journee_type("Jour normal", "neuf heures", null))
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("neuf heures");
    }

    @Test
    void uneEditionInconnueEnumereLesEditions() {
        assertThatThrownBy(() -> creneauTools.lister_creneaux("edition-qui-nexiste-pas"))
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("lister_editions");
    }
}
