package dev.sylvain.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The wire form of a stand's schedule: {@code plages} published, the 3.x lists kept as deprecated aliases. */
class StandPlagesJsonTest {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final String STORED = opening("2026-07-10");
    private static final String EDITED = opening("2026-07-14");
    private static final String OTHER = opening("2026-07-15");

    private static String opening(String date) {
        return "{\"mode\":\"OUVERTURE\",\"date\":\"" + date
                + "\",\"heureDebut\":\"10:00:00\",\"heureFin\":\"12:00:00\"}";
    }

    /** The key order of a body is the client's: both are tried. */
    private static List<String> bodies(String plages, String ouvertures) {
        String p = "\"plages\":[" + plages + "]";
        String o = "\"ouvertures\":[" + ouvertures + "]";
        return List.of("{" + p + "," + o + "}", "{" + o + "," + p + "}");
    }

    private static List<PlageStand> stored() throws Exception {
        return JSON.readValue("{\"plages\":[" + STORED + "]}", Stand.class).getPlages();
    }

    private static List<String> days(Stand stand) {
        return stand.getPlages().stream()
                .map(plage -> plage.getDate().toString())
                .toList();
    }

    @Test
    void theScheduleIsPublishedAsPlagesNextToTheDeprecatedLists() throws Exception {
        Stand stand = JSON.readValue("{\"plages\":[" + STORED + "]}", Stand.class);

        String json = JSON.writeValueAsString(stand);

        assertThat(json).contains("\"plages\":[").contains("\"ouvertures\":[").contains("\"indisponibilites\":[]");
        assertThat(stand.getPlages()).extracting(PlageStand::isDepliee).containsExactly(false);
        assertThat(stand.carriesBothScheduleForms()).isFalse();
    }

    @Test
    void aDeprecatedListAloneIsTheScheduleAsIs() throws Exception {
        Stand stand = JSON.readValue("{\"ouvertures\":[" + EDITED + "]}", Stand.class);

        assertThat(stand.carriesBothScheduleForms()).isFalse();
        assertThat(days(stand)).containsExactly("2026-07-14");
    }

    /** A client of the 3.x edits {@code ouvertures} and echoes the {@code plages} it never knew. */
    @Test
    void theDeprecatedListThatDiffersFromTheStoredStandIsTheChange() throws Exception {
        for (String body : bodies(STORED, EDITED)) {
            Stand stand = JSON.readValue(body, Stand.class);

            assertThat(stand.carriesBothScheduleForms()).isTrue();
            assertThat(stand.settleDeprecatedLists(stored())).isEmpty();
            assertThat(days(stand)).containsExactly("2026-07-14");
        }
    }

    /** A new client edits {@code plages} and echoes the deprecated list it read. */
    @Test
    void thePlagesThatDifferFromTheStoredStandAreTheChange() throws Exception {
        for (String body : bodies(EDITED, STORED)) {
            Stand stand = JSON.readValue(body, Stand.class);

            assertThat(stand.settleDeprecatedLists(stored())).isEmpty();
            assertThat(days(stand)).containsExactly("2026-07-14");
        }
    }

    @Test
    void bothFormsSayingTheSameThingNeedNoArbitration() throws Exception {
        for (String body : bodies(EDITED, EDITED)) {
            Stand stand = JSON.readValue(body, Stand.class);

            assertThat(stand.settleDeprecatedLists(stored())).isEmpty();
            assertThat(days(stand)).containsExactly("2026-07-14");
        }
    }

    @Test
    void bothFormsChangedAndDisagreeingAreContradictory() throws Exception {
        for (String body : bodies(EDITED, OTHER)) {
            Stand stand = JSON.readValue(body, Stand.class);

            assertThat(stand.settleDeprecatedLists(stored())).containsExactly(ModeHoraire.OUVERTURE);
        }
    }

    @Test
    void aDeprecatedListLeavesTheOtherModeOfPlagesAlone() throws Exception {
        Stand stand =
                JSON.readValue("{\"plages\":[" + STORED + "],\"indisponibilites\":[" + EDITED + "]}", Stand.class);

        assertThat(stand.getPlages())
                .extracting(PlageStand::getMode)
                .containsExactlyInAnyOrder(ModeHoraire.OUVERTURE, ModeHoraire.FERMETURE);
    }
}
