package dev.sylvain.planning.service.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.service.BusinessError;
import org.junit.jupiter.api.Test;

/** The schema a dump names in its header, and the refusal of one taken at another schema — no database. */
class DatabaseDumpSchemaVersionTest {

    private static final String DUMP_V121 = """
            -- Planning Équipes database dump
            -- Generated at 2026-10-01T08:00:00Z
            -- Schema version: 121
            -- Replay with the "Import SQL" admin action.

            DELETE FROM animateur;
            """;

    @Test
    void theHeaderNamesTheSchemaTheDumpWasTakenAt() {
        assertThat(DatabaseDumpService.schemaVersionOf(DUMP_V121)).contains("121");
        assertThat(DatabaseDumpService.schemaVersionOf("--schema VERSION: V7.1\nDELETE FROM stand;"))
                .contains("7.1");
    }

    @Test
    void onlyTheLeadingCommentsAreRead() {
        assertThat(DatabaseDumpService.schemaVersionOf("DELETE FROM stand;\n-- Schema version: 121\n"))
                .isEmpty();
        assertThat(DatabaseDumpService.schemaVersionOf("-- written by hand\nDELETE FROM stand;"))
                .isEmpty();
    }

    @Test
    void aDumpOfAnotherSchemaIsRefusedWithBothVersions() {
        assertThatThrownBy(() -> DatabaseDumpService.checkSchemaVersion(DUMP_V121, "122"))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("V121")
                .hasMessageContaining("V122");
    }

    @Test
    void aDumpOfTheSameSchemaOrNamingNoneIsReplayed() {
        assertThatCode(() -> DatabaseDumpService.checkSchemaVersion(DUMP_V121, "121"))
                .doesNotThrowAnyException();
        assertThatCode(() -> DatabaseDumpService.checkSchemaVersion(DUMP_V121, "121.0"))
                .doesNotThrowAnyException();
        assertThatCode(() -> DatabaseDumpService.checkSchemaVersion("DELETE FROM stand;", "122"))
                .doesNotThrowAnyException();
    }
}
