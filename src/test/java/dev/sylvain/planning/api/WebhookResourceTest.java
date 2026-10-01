package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.notification.Notification;
import dev.sylvain.planning.service.publication.PlanPublicationService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import dev.sylvain.planning.service.webhook.DeliveryStatus;
import dev.sylvain.planning.service.webhook.WebhookDeliverer;
import dev.sylvain.planning.service.webhook.WebhookFormats;
import dev.sylvain.planning.service.webhook.WebhookRepository;
import dev.sylvain.planning.testing.FakeHttpReceiver;
import dev.sylvain.planning.testing.FakeHttpReceiver.Script;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The outgoing webhooks end to end, against a receiver on the loopback — which
 * the test profile declares as an allowed network, so plain {@code http} and
 * any port reach it.
 */
@QuarkusTest
class WebhookResourceTest {

    /** The test database's edition, the active one: only it emits (ADR 0072). */
    private static final String EDITION = "E1";

    /** The lease of the deliverer: two minutes. */
    private static final Duration LEASE = Duration.ofMinutes(2);

    private static FakeHttpReceiver receiver;

    @Inject
    Event<Notification> notifications;

    @Inject
    WebhookDeliverer deliverer;

    @Inject
    WebhookRepository repository;

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    PlanPublicationService publication;

    @Inject
    DataSource dataSource;

    @Inject
    EditionContext editionContext;

    @BeforeAll
    static void startReceiver() throws IOException {
        receiver = new FakeHttpReceiver();
    }

    @AfterAll
    static void stopReceiver() {
        receiver.close();
    }

    @BeforeEach
    void cleanSlate() {
        execute("DELETE FROM webhook");
        receiver.reset();
    }

    @AfterEach
    void handBack() {
        execute("DELETE FROM webhook");
    }

    /* ------------------------------ Configuration ----------------------------- */

    @Test
    void aGenericWebhookShowsItsSecretOnceAndNeverAgain() {
        JsonPath created = create("generic", "GENERIC", receiver.url("/n8n"), List.of("planning.publie"));
        String secret = created.getString("secret");

        assertThat(secret).isNotBlank();
        assertThat(created.getString("webhook.destination")).isEqualTo(receiver.url("/n8n"));
        String liste = given().header(EditionContext.HEADER, EDITION)
                .when()
                .get("/api/webhooks")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        assertThat(liste).doesNotContain(secret).contains("planning.publie");
        // Nor in the database in clear: what a nightly pg_dump would carry is ciphertext.
        assertThat(column("SELECT secret_chiffre FROM webhook"))
                .startsWith("v1:")
                .doesNotContain(secret);
    }

    @Test
    void aChatAddressIsTheSecretAndIsMaskedOnRead() {
        JsonPath created = create("slack", "SLACK", receiver.url("/services/T0/B0/SECRET"), List.of());

        assertThat(created.getString("secret")).isNull();
        assertThat(created.getString("webhook.destination"))
                .isEqualTo(receiver.url("/…"))
                .doesNotContain("SECRET");
        assertThat(column("SELECT secret_chiffre FROM webhook")).doesNotContain("SECRET");
        assertThat(column("SELECT coalesce(url, '') FROM webhook")).isEmpty();
    }

    @Test
    void anInternalAddressIsRefusedWhenSaved() {
        for (String url : List.of("https://169.254.169.254/latest", "http://10.0.0.1/hook", "https://[::1]/hook")) {
            given().header(EditionContext.HEADER, EDITION)
                    .contentType(ContentType.JSON)
                    .body(Map.of("name", "interne", "format", "GENERIC", "url", url, "events", List.of()))
                    .when()
                    .post("/api/webhooks")
                    .then()
                    .statusCode(400);
        }
        assertThat(given().header(EditionContext.HEADER, EDITION)
                        .when()
                        .get("/api/webhooks")
                        .jsonPath()
                        .getList("$"))
                .isEmpty();
    }

    @Test
    void anEditKeepsTheSecretWhenTheAddressIsLeftBlankAndRecordsFieldNamesOnly() {
        String id = create("slack", "SLACK", receiver.url("/services/PRIVE-42"), List.of())
                .getString("webhook.id");

        given().header(EditionContext.HEADER, EDITION)
                .contentType(ContentType.JSON)
                .body(Map.of("name", "slack renommé", "format", "SLACK", "url", "", "events", List.of("test")))
                .when()
                .put("/api/webhooks/" + id)
                .then()
                .statusCode(400);
        given().header(EditionContext.HEADER, EDITION)
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "name", "slack renommé", "format", "SLACK", "url", "", "events", List.of("echange.soumis")))
                .when()
                .put("/api/webhooks/" + id)
                .then()
                .statusCode(200);

        given().header(EditionContext.HEADER, EDITION)
                .when()
                .post("/api/webhooks/" + id + "/test")
                .then()
                .statusCode(200);
        assertThat(receiver.receivedOn("/services/PRIVE-42")).hasSize(1);
        String historique = given().header(EditionContext.HEADER, EDITION)
                .when()
                .get("/api/historique")
                .asString();
        assertThat(historique).contains("WEBHOOK_MODIFIE").doesNotContain("PRIVE-42");
    }

    /* --------------------------------- Delivery ------------------------------- */

    @Test
    void theTestIsSignedAndItsSignatureChecksOut() {
        JsonPath created = create("generic", "GENERIC", receiver.url("/n8n"), List.of());
        String id = created.getString("webhook.id");

        JsonPath result = given().header(EditionContext.HEADER, EDITION)
                .when()
                .post("/api/webhooks/" + id + "/test")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();

        assertThat(result.getString("status")).isEqualTo("DELIVERED");
        assertThat(result.getInt("httpStatus")).isEqualTo(200);
        FakeHttpReceiver.Received request = receiver.receivedOn("/n8n").get(0);
        assertThat(request.header("X-Planning-Evenement")).isEqualTo("test");
        assertThat(request.header("X-Planning-Livraison")).isNotBlank();
        String timestamp = request.header("X-Planning-Horodatage");
        assertThat(request.header("X-Planning-Signature"))
                .isEqualTo(WebhookFormats.signature(
                        created.getString("secret"), timestamp, request.body().getBytes(StandardCharsets.UTF_8)));
        assertThat(request.body()).contains("\"evenement\":\"test\"").contains("\"edition\":null");
    }

    @Test
    void aRegeneratedSecretSignsFromThenOn() {
        String id =
                create("generic", "GENERIC", receiver.url("/n8n"), List.of()).getString("webhook.id");

        String nouveau = given().header(EditionContext.HEADER, EDITION)
                .when()
                .post("/api/webhooks/" + id + "/secret")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("secret");
        given().header(EditionContext.HEADER, EDITION)
                .when()
                .post("/api/webhooks/" + id + "/test")
                .then()
                .statusCode(200);

        FakeHttpReceiver.Received request = receiver.receivedOn("/n8n").get(0);
        assertThat(request.header("X-Planning-Signature"))
                .isEqualTo(WebhookFormats.signature(
                        nouveau,
                        request.header("X-Planning-Horodatage"),
                        request.body().getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aFailingReceiverIsRetriedOnTheScheduleAndItsBodyNeverShown() {
        receiver.answer("/n8n", Script.json(500, "{\"stack\":\"CORPS-INTERNE\"}"));
        String id = create("generic", "GENERIC", receiver.url("/n8n"), List.of("echanges.en_attente"))
                .getString("webhook.id");

        notifications.fire(new Notification.PendingEchanges(2, 5));

        JsonPath first = awaitDelivery(id, delivery -> delivery.getInt("[0].attempts") == 1);
        assertThat(first.getString("[0].status")).isEqualTo("PENDING");
        assertThat(first.getInt("[0].httpStatus")).isEqualTo(500);
        assertThat(first.getInt("[0].maxAttempts")).isEqualTo(6);
        assertThat(Duration.between(Instant.now(), Instant.parse(first.getString("[0].nextAttemptAt"))))
                .isBetween(Duration.ofSeconds(30), Duration.ofSeconds(70));

        execute("UPDATE webhook_livraison SET prochain_essai = now() - interval '1 second'");
        assertThat(deliverer.deliverDue()).isEqualTo(1);

        JsonPath second = deliveries(id);
        assertThat(second.getInt("[0].attempts")).isEqualTo(2);
        assertThat(Duration.between(Instant.now(), Instant.parse(second.getString("[0].nextAttemptAt"))))
                .isBetween(Duration.ofMinutes(4), Duration.ofMinutes(6));
        assertThat(second.prettify()).doesNotContain("CORPS-INTERNE");
        assertThat(receiver.receivedOn("/n8n")).hasSize(2);
        assertThat(receiver.receivedOn("/n8n").get(0).body()).contains("\"nombre\":2");
    }

    @Test
    void aNotFoundIsGivenUpAtOnceAndCanBeResent() {
        receiver.answer("/n8n", Script.status(404));
        String id = create("generic", "GENERIC", receiver.url("/n8n"), List.of("echanges.en_attente"))
                .getString("webhook.id");

        notifications.fire(new Notification.PendingEchanges(1, 3));

        JsonPath abandonnee = awaitDelivery(id, delivery -> "ABANDONED".equals(delivery.getString("[0].status")));
        assertThat(abandonnee.getInt("[0].attempts")).isEqualTo(1);
        execute("UPDATE webhook_livraison SET prochain_essai = now() - interval '1 second'");
        assertThat(deliverer.deliverDue()).isZero();

        receiver.answer("/n8n", Script.status(204));
        given().header(EditionContext.HEADER, EDITION)
                .when()
                .post("/api/webhooks/livraisons/" + abandonnee.getString("[0].id") + "/renvoi")
                .then()
                .statusCode(200);
        awaitDelivery(id, delivery -> "DELIVERED".equals(delivery.getString("[0].status")));
        // The same delivery id travels again: a receiver deduplicates on it.
        List<FakeHttpReceiver.Received> requests = receiver.receivedOn("/n8n");
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).header("X-Planning-Livraison"))
                .isEqualTo(requests.get(0).header("X-Planning-Livraison"));
    }

    @Test
    void aRedirectIsNeverFollowed() {
        receiver.answer("/n8n", new Script(302, "", Map.of("Location", receiver.url("/ailleurs")), 0));
        String id =
                create("generic", "GENERIC", receiver.url("/n8n"), List.of()).getString("webhook.id");

        JsonPath result = given().header(EditionContext.HEADER, EDITION)
                .when()
                .post("/api/webhooks/" + id + "/test")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();

        assertThat(result.getString("status")).isEqualTo("ABANDONED");
        assertThat(result.getString("error")).contains("Redirection non suivie");
        assertThat(receiver.receivedOn("/ailleurs")).isEmpty();
    }

    /**
     * Only the active edition speaks outward (ADR 0072): the same fact fired
     * from an edition being prepared queues nothing, while the backup failure,
     * an instance event, leaves whatever the edition.
     */
    @Test
    void anInactiveEditionSendsNothingButTheBackupFailureAlwaysLeaves() {
        String id = create(
                        "generic", "GENERIC", receiver.url("/n8n"), List.of("echanges.en_attente", "sauvegarde.echec"))
                .getString("webhook.id");

        editionContext.executeIn("WH-PREPAREE", () -> {
            notifications.fire(new Notification.PendingEchanges(2, 5));
            notifications.fire(new Notification.BackupFailed(
                    ZonedDateTime.of(2026, 7, 12, 4, 0, 0, 0, ZoneId.of("Europe/Paris")),
                    "Could not run pg_dump: Cannot run program \"/srv/db-interne/pg_dump\"",
                    null,
                    1));
        });

        JsonPath livraisons = awaitDelivery(id, delivery -> "DELIVERED".equals(delivery.getString("[0].status")));
        assertThat(livraisons.getList("event")).containsExactly("sauvegarde.echec");
        // A kind of failure, never pg_dump's own words: they name hosts and paths.
        assertThat(receiver.receivedOn("/n8n").get(0).body())
                .contains("\"edition\":null")
                .contains("pg_dump n'a pas pu être lancé")
                .doesNotContain("db-interne");
    }

    /**
     * A retry asks again whether its edition may emit: one queued while the
     * edition was active, retried after it handed over, is given up.
     */
    @Test
    void aRetryAboutAnEditionNoLongerActiveIsGivenUp() {
        receiver.answer("/n8n", Script.status(503));
        String id = create("generic", "GENERIC", receiver.url("/n8n"), List.of("echanges.en_attente"))
                .getString("webhook.id");
        notifications.fire(new Notification.PendingEchanges(1, 3));
        awaitDelivery(id, delivery -> delivery.getInt("[0].attempts") == 1);

        // The edition the payload names is not the active one any more.
        execute("UPDATE webhook_livraison SET prochain_essai = now() - interval '1 second', "
                + "payload = CAST(replace(CAST(payload AS text), '\"id\":\"" + EDITION
                + "\"', '\"id\":\"WH-PREPAREE\"') AS json)");
        assertThat(deliverer.deliverDue()).isEqualTo(1);

        JsonPath journal = deliveries(id);
        assertThat(journal.getString("[0].status")).isEqualTo("ABANDONED");
        assertThat(journal.getString("[0].error")).contains("Édition inactive");
        assertThat(receiver.receivedOn("/n8n")).hasSize(1);
    }

    /** A name that does not resolve is a DNS failure that may pass: retried, not given up. */
    @Test
    void anUnresolvedNameIsRetried() {
        String id = create("generic", "GENERIC", receiver.url("/n8n"), List.of("echanges.en_attente"))
                .getString("webhook.id");
        execute("UPDATE webhook SET url = 'https://introuvable.invalid/hook'");

        notifications.fire(new Notification.PendingEchanges(1, 3));

        JsonPath journal = awaitDelivery(id, delivery -> delivery.getInt("[0].attempts") == 1);
        assertThat(journal.getString("[0].status")).isEqualTo("PENDING");
        assertThat(journal.getString("[0].error")).contains("DNS");
    }

    /**
     * A secret the key does not open — another instance's key — is a final
     * refusal recorded like any other, never an exception leaving the row
     * leased and the test button in error.
     */
    @Test
    void aSecretTheKeyDoesNotOpenIsGivenUpAndShown() {
        String id =
                create("generic", "GENERIC", receiver.url("/n8n"), List.of()).getString("webhook.id");
        execute("UPDATE webhook SET secret_chiffre = 'v1:' || encode(sha256('autre clé'::bytea) || "
                + "sha256('autre'::bytea), 'base64')");

        JsonPath result = given().header(EditionContext.HEADER, EDITION)
                .when()
                .post("/api/webhooks/" + id + "/test")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();

        assertThat(result.getString("status")).isEqualTo("ABANDONED");
        assertThat(result.getString("error")).contains("Secret illisible");
        assertThat(receiver.receivedOn("/n8n")).isEmpty();
        assertThat(column("SELECT count(*) FROM webhook_livraison WHERE en_cours_depuis IS NOT NULL"))
                .isEqualTo("0");
    }

    /** The test is one attempt, and so is its « Renvoyer »; the sweep never takes it while it is sent. */
    @Test
    void aTestDeliveryIsNeverRetriedNorSweptWhileSent() {
        receiver.answer("/n8n", Script.status(503));
        String id =
                create("generic", "GENERIC", receiver.url("/n8n"), List.of()).getString("webhook.id");

        JsonPath result = given().header(EditionContext.HEADER, EDITION)
                .when()
                .post("/api/webhooks/" + id + "/test")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(result.getString("status")).isEqualTo("FAILED");
        String delivery = deliveries(id).getString("[0].id");
        assertThat(deliveries(id).getInt("[0].maxAttempts")).isEqualTo(1);

        given().header(EditionContext.HEADER, EDITION)
                .when()
                .post("/api/webhooks/livraisons/" + delivery + "/renvoi")
                .then()
                .statusCode(200);
        JsonPath renvoyee = awaitDelivery(
                id, journal -> journal.getInt("[0].attempts") == 1 && "FAILED".equals(journal.getString("[0].status")));
        assertThat(renvoyee.getString("[0].nextAttemptAt")).isNull();
        assertThat(receiver.receivedOn("/n8n")).hasSize(2);

        // Inserted already leased: a sweep running meanwhile leaves it alone.
        UUID leased = UUID.randomUUID();
        repository.enqueueLeased(leased, id, "test", "{}");
        assertThat(repository.dueIds(50, LEASE)).doesNotContain(leased);
        assertThat(repository.claim(leased, LEASE)).isEmpty();
    }

    /** The lease: taken once, only when due, and an attempt is recorded under its own lease only. */
    @Test
    void aDeliveryIsLeasedOnceWhenDueAndRecordedUnderItsOwnLease() {
        String webhook =
                create("generic", "GENERIC", receiver.url("/n8n"), List.of()).getString("webhook.id");
        UUID id = UUID.randomUUID();
        repository.enqueue(id, webhook, "echanges.en_attente", "{}");

        Optional<Instant> lease = repository.claim(id, LEASE);
        assertThat(lease).isPresent();
        assertThat(repository.claim(id, LEASE)).isEmpty();
        assertThat(repository.dueIds(50, LEASE)).doesNotContain(id);

        // Another attempt's lease records nothing; its own does, and releases it.
        assertThat(repository.recordAttempt(
                        id, lease.get().minusMillis(1), 1, DeliveryStatus.PENDING, 503, 5, "x", Instant.now()))
                .isFalse();
        assertThat(repository.recordAttempt(
                        id,
                        lease.get(),
                        1,
                        DeliveryStatus.PENDING,
                        503,
                        5,
                        "x",
                        Instant.now().plus(Duration.ofHours(1))))
                .isTrue();

        // Released but not due yet: neither a candidate nor claimable.
        assertThat(repository.dueIds(50, LEASE)).doesNotContain(id);
        assertThat(repository.claim(id, LEASE)).isEmpty();

        // Due, leased by an attempt a restart abandoned: taken over, and the old lease records nothing.
        Instant stale = Instant.now().minus(Duration.ofMinutes(3)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        execute("UPDATE webhook_livraison SET prochain_essai = now() - interval '1 second', "
                + "en_cours_depuis = TIMESTAMPTZ '" + stale + "'");
        assertThat(repository.recordAttempt(id, stale, 2, DeliveryStatus.DELIVERED, 200, 5, null, null))
                .as("its own lease, before it is taken over")
                .isTrue();
        execute("UPDATE webhook_livraison SET statut = 'PENDING', prochain_essai = now() - interval '1 second', "
                + "en_cours_depuis = TIMESTAMPTZ '" + stale + "'");
        assertThat(repository.dueIds(50, LEASE)).contains(id);
        assertThat(repository.claim(id, LEASE)).isPresent();
        assertThat(repository.recordAttempt(id, stale, 2, DeliveryStatus.DELIVERED, 200, 5, null, null))
                .isFalse();
        assertThat(repository.findDelivery(id).orElseThrow().status()).isEqualTo(DeliveryStatus.PENDING);
    }

    @Test
    void aDeletedWebhookTakesItsDeliveriesWithIt() {
        receiver.answer("/n8n", Script.status(503));
        String id = create("generic", "GENERIC", receiver.url("/n8n"), List.of("echanges.en_attente"))
                .getString("webhook.id");
        notifications.fire(new Notification.PendingEchanges(1, 3));
        awaitDelivery(id, delivery -> delivery.getInt("[0].attempts") == 1);

        given().header(EditionContext.HEADER, EDITION)
                .when()
                .delete("/api/webhooks/" + id)
                .then()
                .statusCode(204);

        assertThat(column("SELECT count(*) FROM webhook_livraison")).isEqualTo("0");
        given().header(EditionContext.HEADER, EDITION)
                .when()
                .get("/api/webhooks/" + id + "/livraisons")
                .then()
                .statusCode(404);
    }

    /**
     * The publication never waits on the receiver: a receiver that takes four
     * seconds to answer costs it nothing, and its delivery still lands.
     */
    @Test
    void aSlowReceiverNeverDelaysThePublication() {
        receiver.answer("/lent", new Script(200, "", Map.of(), 4_000));
        String id = create("lent", "MATRIX", receiver.url("/lent"), List.of("planning.publie"))
                .getString("webhook.id");
        execute("DELETE FROM plan_snapshot");
        persistence.clearDatabase();
        persistPlan();

        long start = System.nanoTime();
        publication.publier();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).isLessThan(Duration.ofSeconds(3));
        awaitDelivery(id, delivery -> "DELIVERED".equals(delivery.getString("[0].status")));
        String body = receiver.receivedOn("/lent").get(0).body();
        assertThat(body)
                .contains("Planning publié")
                .contains("sur 2")
                .doesNotContain("Alice", "Martin", "Bruno", "Petit");
    }

    /* --------------------------------- Helpers -------------------------------- */

    private static JsonPath create(String name, String format, String url, List<String> events) {
        return given().header(EditionContext.HEADER, EDITION)
                .contentType(ContentType.JSON)
                .body(Map.of("name", name, "format", format, "url", url, "events", events))
                .when()
                .post("/api/webhooks")
                .then()
                .statusCode(201)
                .extract()
                .jsonPath();
    }

    private static JsonPath deliveries(String webhookId) {
        return given().header(EditionContext.HEADER, EDITION)
                .when()
                .get("/api/webhooks/" + webhookId + "/livraisons")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    private static JsonPath awaitDelivery(String webhookId, java.util.function.Predicate<JsonPath> until) {
        return await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(100))
                .until(
                        () -> {
                            JsonPath deliveries = deliveries(webhookId);
                            return !deliveries.getList("$").isEmpty() && until.test(deliveries) ? deliveries : null;
                        },
                        java.util.Objects::nonNull);
    }

    private void persistPlan() {
        LocalDate jour = LocalDate.of(2026, 7, 11);
        Animateur alice = new Animateur("WH-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur bruno = new Animateur("WH-B", "Bruno", "Petit", LocalDate.of(1992, 2, 2), false);
        alice.setEmail("wh-alice@example.org");
        bruno.setEmail("wh-bruno@example.org");
        Stand stand = new Stand("WH-S1", "Stand webhook", Set.of(), 1, 2, false);
        Creneau creneau = new Creneau(970_300_001L, 1, jour, LocalTime.of(10, 0), LocalTime.of(12, 0));
        PosteAffectation posteAlice = new PosteAffectation("WH-P1", stand, creneau);
        posteAlice.setAnimateur(alice);
        PosteAffectation posteBruno = new PosteAffectation("WH-P2", stand, creneau);
        posteBruno.setAnimateur(bruno);
        persistence.persist(new PlanningEvenement(jour, List.of(alice, bruno), List.of(posteAlice, posteBruno)));
    }

    private String column(String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                var rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void execute(String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to run " + sql, e);
        }
    }
}
