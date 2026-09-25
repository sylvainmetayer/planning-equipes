package dev.sylvain.planning.service.export;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Cutting a dump into statements, with no database: the splitter is pure and static. */
class DatabaseDumpSplitTest {

    @Test
    void aNullOrBlankScriptHoldsNoStatement() {
        assertThat(DatabaseDumpService.splitStatements(null)).isEmpty();
        assertThat(DatabaseDumpService.splitStatements("  ;\n ; ")).isEmpty();
    }

    @Test
    void statementsAreCutOnSemicolonsAndTrimmed() {
        assertThat(DatabaseDumpService.splitStatements("DELETE FROM stand;\nINSERT INTO stand (id) VALUES ('S1')"))
                .containsExactly("DELETE FROM stand", "INSERT INTO stand (id) VALUES ('S1')");
    }

    @Test
    void aSemicolonInsideALiteralDoesNotEndTheStatement() {
        assertThat(DatabaseDumpService.splitStatements("INSERT INTO stand (nom) VALUES ('a;b');"))
                .containsExactly("INSERT INTO stand (nom) VALUES ('a;b')");
    }

    @Test
    void aDoubledQuoteStaysInsideTheLiteral() {
        assertThat(DatabaseDumpService.splitStatements("INSERT INTO stand (nom) VALUES ('l''aire;x');"))
                .containsExactly("INSERT INTO stand (nom) VALUES ('l''aire;x')");
    }

    @Test
    void aCommentRunsToTheEndOfItsLineAndBecomesABlank() {
        assertThat(DatabaseDumpService.splitStatements(
                        "-- header; with a semicolon\nDELETE FROM stand; -- trailing\nDELETE FROM animateur -- end"))
                .containsExactly("DELETE FROM stand", "DELETE FROM animateur");
        assertThat(DatabaseDumpService.splitStatements("DELETE -- why\nFROM stand"))
                .containsExactly("DELETE  FROM stand");
    }

    @Test
    void doubleDashesInsideALiteralAreNotAComment() {
        assertThat(DatabaseDumpService.splitStatements("INSERT INTO stand (nom) VALUES ('a--b;c')"))
                .containsExactly("INSERT INTO stand (nom) VALUES ('a--b;c')");
    }
}
