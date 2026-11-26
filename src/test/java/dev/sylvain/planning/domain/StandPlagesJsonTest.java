package dev.sylvain.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

/** The wire form of a stand's schedule: {@code plages} published, the 3.x lists kept as deprecated aliases. */
class StandPlagesJsonTest {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final String OPENING = """
            {"mode":"OUVERTURE","date":"2026-07-10","heureDebut":"10:00:00","heureFin":"12:00:00"}""";

    private static final String LEGACY_OPENING = """
            {"date":"2026-07-14","heureDebut":"09:00:00","heureFin":"11:00:00"}""";

    @Test
    void theScheduleIsPublishedAsPlagesNextToTheDeprecatedLists() throws Exception {
        Stand stand = JSON.readValue("{\"plages\":[" + OPENING + "]}", Stand.class);

        String json = JSON.writeValueAsString(stand);

        assertThat(json).contains("\"plages\":[").contains("\"ouvertures\":[").contains("\"indisponibilites\":[]");
        assertThat(stand.getPlages()).extracting(PlageStand::isDepliee).containsExactly(false);
    }

    @Test
    void aDeprecatedListWinsOverTheStalePlagesWhicheverKeyComesFirst() throws Exception {
        for (String body : new String[] {
            "{\"ouvertures\":[" + LEGACY_OPENING + "],\"plages\":[" + OPENING + "]}",
            "{\"plages\":[" + OPENING + "],\"ouvertures\":[" + LEGACY_OPENING + "]}"
        }) {
            Stand stand = JSON.readValue(body, Stand.class);

            assertThat(stand.getPlages())
                    .extracting(plage -> plage.getDate().toString())
                    .containsExactly("2026-07-14");
        }
    }

    @Test
    void aDeprecatedListLeavesTheOtherModeOfPlagesAlone() throws Exception {
        Stand stand = JSON.readValue(
                "{\"plages\":[" + OPENING + "],\"indisponibilites\":[" + LEGACY_OPENING + "]}", Stand.class);

        assertThat(stand.getPlages())
                .extracting(PlageStand::getMode)
                .containsExactlyInAnyOrder(ModeHoraire.OUVERTURE, ModeHoraire.FERMETURE);
    }
}
