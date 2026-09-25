package dev.sylvain.planning.service.referentiel;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.scenario.dto.CreneauDto;
import dev.sylvain.planning.scenario.dto.ScenarioDto;
import dev.sylvain.planning.scenario.dto.TypologieDto;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.edition.EditionService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The example CSV shipped in {@code src/main/resources/scenarios} is re-read
 * here by the <b>real</b> parser and the real import service.
 *
 * <p>An example file is documentation that the compiler never checks. Left to
 * a comment asking to keep it up to date, it drifts: a separator changes, a
 * column is renamed, a level is spelt differently — and the one file an
 * organiser is told to copy becomes the one file that no longer imports. So
 * the guard is executable, and it asserts three separate things.</p>
 *
 * <ul>
 *   <li><b>It imports clean.</b> Not "mostly": <i>zero</i> rejected rows. A
 *       single rejection means the header, a separator, a level or a date in
 *       the file has stopped matching what the code accepts.</li>
 *   <li><b>It teaches what it claims to.</b> A minor on the event's dates, an
 *       adult, a manager, the three competence levels, wishes and off days —
 *       the file is a dozen rows precisely so that each rule of the format
 *       has a row showing it, and a row dropped in a hurry would silently
 *       take a lesson away.</li>
 *   <li><b>Its referential holds.</b> Every typologie it names is declared by
 *       the {@code festival-realiste-canicule} scenario, and every off day falls on one
 *       of its créneau dates — the two things the import refuses a row for.</li>
 * </ul>
 */
@QuarkusTest
class AnimateurCsvExempleTest {

    /** Drawn by the application when the edition is created (ADR 0050). */
    private static String edition;

    private static final String SCENARIO = "scenarios/festival-realiste-canicule.yaml";

    private static final String EXEMPLE = AnimateurCsvImportService.EXEMPLE_RESSOURCE;

    private static final String FICHIER = AnimateurCsvImportService.EXEMPLE_FICHIER;

    /** The nine columns the example is expected to hand to the import, in file order. */
    private static final List<String> ENTETE = List.of(
            "identifiant",
            "prénom",
            "nom",
            "date de naissance",
            "email",
            "manager",
            "compétences",
            "souhaits",
            "jours indisponibles");

    @Inject
    AnimateurCsvImportService csvImport;

    @Inject
    ReferenceDataService referenceData;

    @Inject
    EditionService editions;

    @Inject
    EditionContext editionContext;

    private ScenarioDto scenario;

    /**
     * The id each typologie of the scenario was created under, by the file's
     * reference — which is also the row's {@code code}, the key the CSV cites
     * (ADR 0050).
     */
    private final Map<String, String> typologieIdParCode = new HashMap<>();

    @BeforeEach
    void createTheScenarioEdition() throws IOException {
        scenario = readScenario();
        edition = editions.create(new Edition(null, "Exemple CSV", false, null)).getId();
        editionContext.executeIn(edition, () -> {
            for (TypologieDto typologie : scenario.typologies()) {
                TypologieItem creee = referenceData.createTypologie(new TypologieItem(
                        null,
                        typologie.id(),
                        typologie.label(),
                        Boolean.TRUE.equals(typologie.ninja()),
                        null,
                        null,
                        null));
                typologieIdParCode.put(typologie.id(), creee.id());
            }
            for (CreneauDto creneau : scenario.creneaux()) {
                referenceData.createCreneau(
                        new Creneau(null, creneau.jour(), creneau.date(), creneau.heureDebut(), creneau.heureFin()));
            }
            return null;
        });
    }

    @AfterEach
    void deleteTheEdition() {
        editions.delete(edition);
    }

    /* ------------------------------ The claim ------------------------------ */

    /**
     * The whole point of shipping the file: an organiser downloads it, drops
     * it in, and nothing is refused. One rejected row and the example is
     * teaching the wrong format.
     */
    @Test
    void exampleImportsWithoutASingleRejectedRow() {
        AnimateurCsvImportReport rapport = inEdition(() -> csvImport.apply(exampleRequest()));

        assertThat(rapport.rejected()).isZero();
        assertThat(rapport.rows()).allSatisfy(ligne -> {
            assertThat(ligne.reasons()).isEmpty();
            assertThat(ligne.warnings()).isEmpty();
        });
        assertThat(rapport.total()).isEqualTo(rapport.accepted()).isEqualTo(rapport.created());
        assertThat(rapport.deleted()).isZero();
        assertThat(inEdition(() -> referenceData.listAnimateurs())).hasSize(rapport.total());
    }

    /**
     * The example is also what an operator sees the mapping editor prefilled
     * from. Headers that no longer match any alias would still import — after
     * nine manual corrections nobody should have to make.
     */
    @Test
    void exampleHeaderMapsOnItsOwnOntoTheNineFields() {
        CsvParser.Table table = CsvParser.parse(exemple());

        assertThat(table.separator()).isEqualTo(';');
        assertThat(table.columns()).isEqualTo(ENTETE);
        AnimateurCsvMapping mapping = AnimateurCsvMapping.propose(table.columns());
        assertThat(List.of(
                        mapping.id(),
                        mapping.prenom(),
                        mapping.nom(),
                        mapping.dateNaissance(),
                        mapping.email(),
                        mapping.manager(),
                        mapping.competences(),
                        mapping.souhaits(),
                        mapping.joursIndisponibles()))
                .containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8);
    }

    /**
     * What the file is for: each rule of the format has a row showing it.
     * The minor is asserted through {@link Animateur#isMineurOn} on the
     * event's own dates — the regime is derived there, never stored — and
     * aged 16 or 17, the bracket whose legal rules the product carries; a
     * child would show a rule the roster never hits.
     */
    @Test
    void exampleTeachesAMinorAnAdultAManagerLevelsWishesAndOffDays() {
        Set<LocalDate> joursEvenement =
                scenario.creneaux().stream().map(CreneauDto::date).collect(Collectors.toSet());

        inEdition(() -> csvImport.apply(exampleRequest()));
        List<Animateur> importes = inEdition(() -> referenceData.listAnimateurs());

        assertThat(importes)
                .hasSizeBetween(10, 15)
                .anySatisfy(animateur -> assertThat(joursEvenement)
                        .allSatisfy(jour -> assertThat(animateur.isMineurOn(jour) && !animateur.isUnder16On(jour))
                                .as("%s is 16 or 17 on %s", animateur.getId(), jour)
                                .isTrue()))
                .anySatisfy(animateur -> assertThat(joursEvenement)
                        .allSatisfy(
                                jour -> assertThat(animateur.isMajeurOn(jour)).isTrue()))
                .anySatisfy(animateur -> assertThat(animateur.isManager()).isTrue())
                .anySatisfy(animateur -> assertThat(animateur.isManager()).isFalse());
        assertThat(importes.stream()
                        .flatMap(animateur -> animateur.getCompetences().values().stream())
                        .collect(Collectors.toSet()))
                .containsExactlyInAnyOrder(NiveauCompetence.values());
        assertThat(importes)
                .anySatisfy(animateur -> assertThat(animateur.getCompetences())
                        .hasSizeGreaterThan(1)
                        .containsValue(NiveauCompetence.REFERENT))
                .anySatisfy(animateur -> assertThat(animateur.getSouhaits()).hasSizeGreaterThan(1))
                .anySatisfy(animateur ->
                        assertThat(animateur.getJoursIndisponibles()).hasSizeGreaterThan(1))
                .anySatisfy(animateur -> assertThat(animateur.getEmail()).contains("@"))
                .anySatisfy(animateur -> assertThat(animateur.getEmail()).isNull());
    }

    /**
     * The two referential checks a row is refused on, asserted on the file
     * itself rather than only through the count above: an unknown typologie or
     * a day outside the créneaux would otherwise be caught by
     * {@link #exampleImportsWithoutASingleRejectedRow()} without saying which
     * of the two broke.
     */
    @Test
    void exampleTypologiesAndDaysBelongToTheScenario() {
        // What is stored is the id each cited code resolved to.
        Set<String> typologies = Set.copyOf(typologieIdParCode.values());
        Set<LocalDate> joursEvenement =
                scenario.creneaux().stream().map(CreneauDto::date).collect(Collectors.toSet());

        inEdition(() -> csvImport.apply(exampleRequest()));
        List<Animateur> importes = inEdition(() -> referenceData.listAnimateurs());

        assertThat(importes).isNotEmpty().allSatisfy(animateur -> {
            assertThat(typologies).containsAll(animateur.getCompetences().keySet());
            assertThat(typologies).containsAll(animateur.getSouhaits());
            assertThat(joursEvenement).containsAll(animateur.getJoursIndisponibles());
        });
    }

    /**
     * The example is fictional and says so: none of its rows is one of the
     * anonymised fixture's, whose birth dates are all identical and whose
     * names are « Animateur A1 » — the wrong thing to teach a roster with.
     */
    @Test
    void exampleIsNotAnExtractOfTheAnonymisedFixture() {
        inEdition(() -> csvImport.apply(exampleRequest()));
        List<Animateur> importes = inEdition(() -> referenceData.listAnimateurs());

        // Compared by identity, not by id: the ids are drawn by the edition
        // (ADR 0050), so a fresh one hands out A1… whatever the file says.
        Set<String> fixtureNoms = scenario.animateurs().stream()
                .map(animateur -> animateur.prenom() + " " + animateur.nom())
                .collect(Collectors.toSet());
        assertThat(importes)
                .noneSatisfy(animateur ->
                        assertThat(fixtureNoms).contains(animateur.getPrenom() + " " + animateur.getNom()));
        assertThat(importes.stream().map(Animateur::getDateNaissance).distinct())
                .hasSizeGreaterThan(5);
    }

    /**
     * Its dates are ISO, and that is not a matter of taste: opened in a
     * spreadsheet and saved again, {@code 12/09/2026} comes back
     * {@code 12/09/26} — read since, but with a warning per row asking the
     * operator to check, which a file that ships clean should never trigger.
     * {@code 2026-09-12} is given back unchanged by Excel as by LibreOffice.
     */
    @Test
    void exampleDatesAreIsoToSurviveASpreadsheet() {
        String exemple = exemple();

        assertThat(exemple)
                .doesNotContainPattern("(?<!\\d)\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4}(?!\\d)")
                .containsPattern("\\d{4}-\\d{2}-\\d{2}");
    }

    /**
     * The screen's « télécharger un exemple » serves this very resource, with
     * one addition: the byte order mark. Excel ignores the response's
     * {@code charset=utf-8} once the file is on disk and falls back to its
     * legacy code page, so an accented name would arrive mangled; the mark is
     * what tells it otherwise. It costs nothing on the way back —
     * {@code CsvParser} strips it before reading the header, which
     * {@link #servedFileReimportsWithItsMark()} proves.
     */
    @Test
    void downloadEndpointServesTheSameResource() {
        String servi = given().when()
                .get("/api/animateurs/import-csv/exemple")
                .then()
                .statusCode(200)
                .header("Content-Disposition", "attachment; filename=\"" + FICHIER + "\"")
                .extract()
                .asString();

        assertThat(FICHIER).isEqualTo("exemple-animateurs.csv");
        assertThat(servi).isEqualTo("\uFEFF" + exemple());
    }

    /** Downloaded and sent straight back, untouched: still not one rejected row. */
    @Test
    void servedFileReimportsWithItsMark() {
        String servi = given().when()
                .get("/api/animateurs/import-csv/exemple")
                .then()
                .statusCode(200)
                .extract()
                .asString();

        AnimateurCsvImportReport rapport =
                inEdition(() -> csvImport.preview(new AnimateurCsvImportRequest(FICHIER, servi, null, false, false)));

        assertThat(servi).startsWith("\uFEFF");
        assertThat(rapport.rejected()).isZero();
        assertThat(rapport.total()).isEqualTo(CsvParser.parse(exemple()).rows().size());
    }

    /* ------------------------------- Helpers ------------------------------- */

    private <T> T inEdition(java.util.concurrent.Callable<T> travail) {
        return editionContext.executeIn(edition, travail);
    }

    private static AnimateurCsvImportRequest exampleRequest() {
        return new AnimateurCsvImportRequest(FICHIER, exemple(), null, false, false);
    }

    /** Read from the classpath, exactly as the endpoint reads it. */
    private static String exemple() {
        return read(EXEMPLE);
    }

    private static ScenarioDto readScenario() throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
                .registerModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper.readValue(read(SCENARIO), ScenarioDto.class);
    }

    private static String read(String ressource) {
        try (InputStream flux = Thread.currentThread().getContextClassLoader().getResourceAsStream(ressource)) {
            if (flux == null) {
                throw new IllegalStateException("Ressource absente du classpath : " + ressource);
            }
            return new String(flux.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Lecture impossible de " + ressource, e);
        }
    }
}
