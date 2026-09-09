package dev.sylvain.planning.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** What the shared parser resolves, and what it deliberately no longer does. */
class ScenarioYamlTest {

    /**
     * The defect this parser exists to close: YAML 1.1 reads {@code 9:30} as
     * the sexagesimal number 570, which the readers then took for a
     * second-of-day and turned into 00:09:30 — a créneau nine minutes after
     * midnight, from a file that said half past nine.
     */
    @Test
    void uneHeureSurDeuxPartiesResteDuTexte() {
        assertThat(read("h: 9:30")).isEqualTo("9:30");
        assertThat(read("h: 8:00")).isEqualTo("8:00");
        assertThat(read("h: 12:00:00")).isEqualTo("12:00:00");
        assertThat(read("h: 08:00")).as("déjà du texte avant, par accident du zéro initial").isEqualTo("08:00");
    }

    @Test
    void lesNombresOrdinairesRestentDesNombres() {
        assertThat(read("h: 42")).isEqualTo(42);
        assertThat(read("h: -7")).isEqualTo(-7);
        assertThat(read("h: 3.5")).isEqualTo(3.5);
        assertThat(read("h: 0x1f")).isEqualTo(31);
        assertThat(read("h: true")).isEqualTo(true);
        assertThat(read("h: ~")).isNull();
    }

    /** A date stays a date all the way to the binder, a bare number stays a number. */
    @Test
    void uneDateDevientDuTexteIsoEtUnEntierResteUnEntier() {
        assertThat(ScenarioYaml.normaliseDates(read("d: 2026-08-17"))).isEqualTo("2026-08-17");
        assertThat(ScenarioYaml.normaliseDates(read("d: 1990"))).isEqualTo(1990);
    }

    private static Object read(String document) {
        Map<String, Object> map = ScenarioYaml.parser().load(document);
        return map.values().iterator().next();
    }
}
