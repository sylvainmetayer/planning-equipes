package dev.sylvain.planning.service.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sylvain.planning.domain.DemandeEchange;
import dev.sylvain.planning.service.espace.ApplicationLinks;
import dev.sylvain.planning.service.notification.Notification;
import dev.sylvain.planning.service.publication.PlanningPublished;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * What a receiver reads, pinned byte for byte: the bodies are a contract with
 * whoever filters on them in an n8n flow or reads them in a channel.
 */
class WebhookFormatsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ApplicationLinks LINKS = new ApplicationLinks(Optional.of("https://planning.example.org"));

    private static WebhookMessage publication() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("instantaneId", 42);
        data.put("destinataires", 12);
        data.put("envoyes", 10);
        data.put("sansEmail", 1);
        data.put("echecs", 1);
        data.put("differes", 2);
        data.put("planningsChanges", 7);
        return new WebhookMessage(
                "planning.publie",
                Instant.parse("2026-07-12T18:03:00Z"),
                new WebhookMessage.EditionRef("2026", "Année 2026"),
                data,
                "https://planning.example.org/publication");
    }

    private static String render(WebhookFormat format, WebhookMessage message) {
        return WebhookFormats.render(MAPPER, format, "d-1", message, "-100123").text();
    }

    @Test
    void theGenericBodyIsTheEnvelopeWithItsDeliveryId() {
        assertThat(render(WebhookFormat.GENERIC, publication()))
                .isEqualTo("{\"id\":\"d-1\",\"evenement\":\"planning.publie\",\"survenuLe\":\"2026-07-12T18:03:00Z\","
                        + "\"edition\":{\"id\":\"2026\",\"nom\":\"Année 2026\"},"
                        + "\"donnees\":{\"instantaneId\":42,\"destinataires\":12,\"envoyes\":10,\"sansEmail\":1,"
                        + "\"echecs\":1,\"differes\":2,\"planningsChanges\":7},"
                        + "\"lien\":\"https://planning.example.org/publication\"}");
    }

    /** pg_dump's own words name the database host, its user and paths: a chat gets a kind of failure. */
    @Test
    void aFailedBackupLeavesAsAKindOfFailureNeverItsMessage() {
        assertThat(WebhookEmitter.backupFailureKind("pg_dump failed (exit 1): connection to db.interne:5432 refused"))
                .isEqualTo("pg_dump a échoué");
        assertThat(WebhookEmitter.backupFailureKind("pg_dump did not finish within PT30M"))
                .isEqualTo("pg_dump n'a pas terminé dans le délai imparti");
        assertThat(WebhookEmitter.backupFailureKind("Could not run /usr/bin/pg_dump: error=2"))
                .isEqualTo("pg_dump n'a pas pu être lancé");
        assertThat(WebhookEmitter.backupFailureKind("/srv/sauvegardes: Permission denied"))
                .isEqualTo("la sauvegarde n'a pas abouti");
        assertThat(WebhookEmitter.backupFailureKind(null)).isEqualTo("la sauvegarde n'a pas abouti");
    }

    @Test
    void slackReadsOneLineOfMrkdwn() {
        assertThat(render(WebhookFormat.SLACK, publication()))
                .isEqualTo("{\"text\":\"*Planning publié* · « Année 2026 »\\n"
                        + "10 personnes prévenues sur 12 ; 7 plannings modifiés, 2 envois différés.\\n"
                        + "<https://planning.example.org/publication|Ouvrir dans l'application>\"}");
    }

    @Test
    void discordPingsNobody() {
        assertThat(render(WebhookFormat.DISCORD, publication()))
                .isEqualTo("{\"content\":\"Planning publié · « Année 2026 »\",\"allowed_mentions\":{\"parse\":[]},"
                        + "\"embeds\":[{\"title\":\"Planning publié\","
                        + "\"description\":\"10 personnes prévenues sur 12 ; 7 plannings modifiés, 2 envois différés.\","
                        + "\"url\":\"https://planning.example.org/publication\","
                        + "\"timestamp\":\"2026-07-12T18:03:00Z\"}]}");
    }

    @Test
    void matrixCarriesTextAndHtml() {
        assertThat(render(WebhookFormat.MATRIX, publication()))
                .isEqualTo("{\"text\":\"Planning publié · « Année 2026 »\\n"
                        + "10 personnes prévenues sur 12 ; 7 plannings modifiés, 2 envois différés.\\n"
                        + "https://planning.example.org/publication\","
                        + "\"html\":\"<strong>Planning publié</strong> · « Année 2026 »<br>"
                        + "10 personnes prévenues sur 12 ; 7 plannings modifiés, 2 envois différés.<br>"
                        + "<a href=\\\"https://planning.example.org/publication\\\">Ouvrir dans l'application</a>\"}");
    }

    @Test
    void telegramNamesItsChatAndSpeaksHtml() {
        assertThat(render(WebhookFormat.TELEGRAM, publication()))
                .isEqualTo("{\"chat_id\":\"-100123\",\"text\":\"<b>Planning publié</b> · « Année 2026 »\\n"
                        + "10 personnes prévenues sur 12 ; 7 plannings modifiés, 2 envois différés.\\n"
                        + "<a href=\\\"https://planning.example.org/publication\\\">Ouvrir dans l'application</a>\","
                        + "\"parse_mode\":\"HTML\",\"disable_web_page_preview\":true}");
    }

    @Test
    void anInstanceEventCarriesNoEdition() {
        WebhookMessage test = new WebhookMessage("test", Instant.parse("2026-07-12T18:03:00Z"), null, Map.of(), null);

        assertThat(render(WebhookFormat.GENERIC, test))
                .isEqualTo("{\"id\":\"d-1\",\"evenement\":\"test\",\"survenuLe\":\"2026-07-12T18:03:00Z\","
                        + "\"edition\":null,\"donnees\":{},\"lien\":null}");
        assertThat(render(WebhookFormat.SLACK, test))
                .isEqualTo("{\"text\":\"*Test*\\nCe webhook est bien relié à l'application.\"}");
    }

    /** An organiser's edition name is text, never markup a channel interprets. */
    @Test
    void anEditionNameIsEscapedForEveryMarkup() {
        WebhookMessage message = new WebhookMessage(
                "test",
                Instant.parse("2026-07-12T18:03:00Z"),
                new WebhookMessage.EditionRef("x", "<b>@everyone</b> & co"),
                Map.of(),
                null);

        assertThat(render(WebhookFormat.SLACK, message)).contains("&lt;b&gt;@everyone&lt;/b&gt; &amp; co");
        assertThat(render(WebhookFormat.TELEGRAM, message)).contains("&lt;b&gt;@everyone&lt;/b&gt; &amp; co");
        assertThat(render(WebhookFormat.MATRIX, message)).contains("&lt;b&gt;@everyone&lt;/b&gt; &amp; co");
        assertThat(render(WebhookFormat.DISCORD, message)).contains("\"allowed_mentions\":{\"parse\":[]}");
    }

    /** The signature of a known body, as a receiver recomputes it (docs/api.md gives the recipe). */
    @Test
    void theSignatureIsTheHmacOfTimestampDotBody() {
        assertThat(WebhookFormats.signature(
                        "s3cr3t", "1752343380", "{\"evenement\":\"test\"}".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("sha256=2c6d5dd00fea5415e9bd03be765be37d813d0e92ea24a73c5c93bfcbb8b10ad7");
    }

    /**
     * Nothing nominative leaves: every notification that maps to an event is
     * built with a name, an address and a birth date it carries, and no format
     * of its payload may quote any of them.
     */
    @Test
    void noPayloadOfAnyEventCarriesAPerson() {
        DemandeEchange demande = new DemandeEchange();
        demande.setId("D1");
        demande.setDemandeurId("A1");
        List<Notification> notifications = List.of(
                new Notification.TargetSolicited("A1", "jeanne.dupont@example.org", "Jeanne Dupont", 2),
                new Notification.DemandeDeclinee("A1", "jeanne.dupont@example.org", "Jeanne Dupont", "Stand 1"),
                new Notification.DemandesSoumises("Jeanne Dupont", List.of(demande)),
                new Notification.DeclarationSoumise("Jeanne Dupont", 3, 2),
                new Notification.EmpechementSignale("Jeanne Dupont", LocalDate.of(2026, 7, 12), "Stand 1", null),
                new Notification.AbsenceReportFiled(
                        "A1",
                        "jeanne.dupont@example.org",
                        "Jeanne",
                        LocalDate.of(2026, 7, 12),
                        null,
                        "https://x/animateur/TOKEN-ABC"),
                new Notification.AbsenceReportAccepted(
                        "A1",
                        "jeanne.dupont@example.org",
                        "Jeanne",
                        LocalDate.of(2026, 7, 12),
                        null,
                        "https://x/animateur/TOKEN-ABC"),
                new Notification.CarpoolValidated(
                        "A1",
                        "jeanne.dupont@example.org",
                        "Jeanne",
                        List.of("Paul Durand"),
                        "https://x/animateur/TOKEN-ABC"),
                new Notification.CarpoolSetAside("A1", "jeanne.dupont@example.org", "Jeanne", null, null),
                new Notification.CarpoolCancelled(
                        "A1", "jeanne.dupont@example.org", "Jeanne", List.of("Paul Durand"), null, true, null),
                new Notification.ResolutionTerminee("Année 2026", "0hard/-3medium/-12soft", true),
                new Notification.RappelVeille(
                        "A1",
                        "jeanne.dupont@example.org",
                        "Jeanne",
                        LocalDate.of(2026, 7, 12),
                        List.of("Stand 1"),
                        null),
                new Notification.RelanceConfirmation(
                        "A1", "jeanne.dupont@example.org", "Jeanne", "https://x/animateur/TOKEN-ABC"),
                new Notification.PendingEchanges(3, 4),
                new Notification.BackupFailed(
                        ZonedDateTime.of(2026, 7, 12, 4, 0, 0, 0, ZoneId.of("Europe/Paris")),
                        "pg_dump : délai dépassé",
                        null,
                        2),
                new Notification.BackupRecovered(
                        ZonedDateTime.of(2026, 7, 12, 4, 0, 0, 0, ZoneId.of("Europe/Paris")), "dump.sql", 2));

        List<WebhookEmitter.Occurrence> occurrences = new ArrayList<>();
        for (Notification notification : notifications) {
            WebhookEmitter.occurrence(notification, LINKS).ifPresent(occurrences::add);
        }
        occurrences.add(WebhookEmitter.occurrence(new PlanningPublished(1, 2, 2, 0, 0, 0, 1), LINKS));
        assertThat(occurrences)
                .extracting(WebhookEmitter.Occurrence::event)
                .containsExactlyInAnyOrder(
                        WebhookEvent.SWAP_SUBMITTED,
                        WebhookEvent.AVAILABILITY_DECLARED,
                        WebhookEvent.SOLVE_FINISHED,
                        WebhookEvent.SWAPS_PENDING,
                        WebhookEvent.BACKUP_FAILED,
                        WebhookEvent.PLANNING_PUBLISHED);

        for (WebhookEmitter.Occurrence occurrence : occurrences) {
            WebhookMessage message = new WebhookMessage(
                    occurrence.event().code(),
                    Instant.parse("2026-07-12T18:03:00Z"),
                    new WebhookMessage.EditionRef("2026", "Année 2026"),
                    occurrence.data(),
                    occurrence.link());
            for (WebhookFormat format : WebhookFormat.values()) {
                assertThat(render(format, message))
                        .as("%s en %s", occurrence.event(), format)
                        .doesNotContain("Jeanne", "Dupont", "Paul", "Durand", "@example.org", "TOKEN", "1990");
            }
        }
    }

    /** A delivery that outlives an upgrade dropping its event still says something. */
    @Test
    void anUnknownEventIsAnnouncedByItsCode() {
        WebhookMessage message =
                new WebhookMessage("pointage.absent", Instant.parse("2026-07-12T18:03:00Z"), null, Map.of(), null);

        assertThat(render(WebhookFormat.SLACK, message)).isEqualTo("{\"text\":\"*pointage.absent*\"}");
    }
}
