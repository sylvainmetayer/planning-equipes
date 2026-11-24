package dev.sylvain.planning.service.export;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FormatPlanningTest {

    @Test
    void feuilleFlagAsksForTheSheet() {
        assertThat(FormatPlanning.fromParameters(null, true)).isEqualTo(FormatPlanning.FEUILLE);
        assertThat(FormatPlanning.fromParameters("livret", true)).isEqualTo(FormatPlanning.FEUILLE);
    }

    /** Links already printed, mailed or stored by an MCP client carry {@code format=feuille}. */
    @Test
    void legacyFormatValuesStayAnAlias() {
        assertThat(FormatPlanning.fromParameters("feuille", false)).isEqualTo(FormatPlanning.FEUILLE);
        assertThat(FormatPlanning.fromParameters("Sheet", false)).isEqualTo(FormatPlanning.FEUILLE);
        assertThat(FormatPlanning.fromParameters("livret", false)).isEqualTo(FormatPlanning.LIVRET);
        assertThat(FormatPlanning.fromParameters("papyrus", false)).isEqualTo(FormatPlanning.LIVRET);
        assertThat(FormatPlanning.fromParameters(null, false)).isEqualTo(FormatPlanning.LIVRET);
    }
}
