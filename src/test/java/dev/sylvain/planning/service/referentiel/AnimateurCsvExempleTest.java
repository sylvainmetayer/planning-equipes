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
import dev.sylvain.planning.scenario.dto.AnimateurDto;
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
 *   <li><b>It still describes the scenario it claims to.</b> Identity,
 *       birth date, address and competences are compared, animateur by
 *       animateur, with {@code festival-realiste.yaml} — so regenerating the
 *       anonymised fixture without regenerating the CSV is caught here rather
 *       than by a reader.</li>
 *   <li><b>Its referential holds.</b> Every typologie it names is declared by
 *       that scenario, and every off day falls on one of its créneau dates —
 *       the two things the import refuses a row for.</li>
 * </ul>
 */
@QuarkusTest
class AnimateurCsvExempleTest {

    private static final String EDITION = "CSV-EXEMPLE-TEST";

    private static final String SCENARIO = "scenarios/festival-realiste.yaml";

    private static final String EXEMPLE = "scenarios/festival-realiste-animateurs.csv";

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

    @BeforeEach
    void creerLEditionDuScenario() throws IOException {
        scenario = readScenario();
        editions.create(new Edition(EDITION, "Exemple CSV", false, null));
        editionContext.executeIn(EDITION, () -> {
            for (TypologieDto typologie : scenario.typologies()) {
                referenceData.createTypologie(
                        new TypologieItem(typologie.id(), typologie.label(), Boolean.TRUE.equals(typologie.ninja())));
            }
            for (CreneauDto creneau : scenario.creneaux()) {
                referenceData.createCreneau(
                        new Creneau(null, creneau.jour(), creneau.date(), creneau.heureDebut(), creneau.heureFin()));
            }
            return null;
        });
    }

    @AfterEach
    void supprimerLEdition() {
        editions.delete(EDITION);
    }

    /* ------------------------------ The claim ------------------------------ */

    /**
     * The whole point of shipping the file: an organiser downloads it, drops
     * it in, and nothing is refused. One rejected row and the example is
     * teaching the wrong format.
     */
    @Test
    void lExempleSImporteSansUneSeuleLigneRejetee() {
        AnimateurCsvImportReport rapport = inEdition(() -> csvImport.apply(demandeExemple()));

        assertThat(rapport.rejected()).isZero();
        assertThat(rapport.rows())
                .allSatisfy(ligne -> assertThat(ligne.reasons()).isEmpty());
        assertThat(rapport.total()).isEqualTo(scenario.animateurs().size());
        assertThat(rapport.accepted()).isEqualTo(scenario.animateurs().size());
        assertThat(rapport.created()).isEqualTo(scenario.animateurs().size());
        assertThat(rapport.deleted()).isZero();
        assertThat(inEdition(() -> referenceData.listAnimateurs()))
                .hasSize(scenario.animateurs().size());
    }

    /**
     * The example is also what an operator sees the mapping editor prefilled
     * from. Headers that no longer match any alias would still import — after
     * nine manual corrections nobody should have to make.
     */
    @Test
    void lEnTeteDeLExempleSeMappeToutSeulSurLesNeufChamps() {
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
     * The file says it carries the animateurs of {@code festival-realiste}.
     * Compared fiche by fiche, so regenerating the anonymised scenario without
     * regenerating this CSV is a red test and not a silent lie.
     */
    @Test
    void lExempleDecritExactementLesAnimateursDuScenario() {
        inEdition(() -> csvImport.apply(demandeExemple()));

        Map<String, Animateur> importes = inEdition(() -> referenceData.listAnimateurs()).stream()
                .collect(Collectors.toMap(Animateur::getId, animateur -> animateur));
        assertThat(importes.keySet())
                .containsExactlyInAnyOrderElementsOf(
                        scenario.animateurs().stream().map(AnimateurDto::id).toList());
        for (AnimateurDto attendu : scenario.animateurs()) {
            Animateur obtenu = importes.get(attendu.id());
            assertThat(obtenu.getPrenom()).isEqualTo(attendu.prenom());
            assertThat(obtenu.getNom()).isEqualTo(attendu.nom());
            assertThat(obtenu.getDateNaissance()).isEqualTo(attendu.dateNaissance());
            assertThat(obtenu.getEmail()).isEqualTo(attendu.email());
            assertThat(obtenu.isManager()).isEqualTo(Boolean.TRUE.equals(attendu.manager()));
            assertThat(obtenu.getCompetences()).isEqualTo(attendu.competences());
        }
    }

    /**
     * The two referential checks a row is refused on, asserted on the file
     * itself rather than only through the count above: an unknown typologie or
     * a day outside the créneaux would otherwise be caught by
     * {@code lExempleSImporteSansUneSeuleLigneRejetee} without saying which of
     * the two broke.
     *
     * <p>Also pinned: the example really exercises the multi-valued cells the
     * screen's help text describes — several competences, several wishes and
     * several off days separated by {@code |} — otherwise it would illustrate
     * a format it never uses.</p>
     */
    @Test
    void lesTypologiesEtLesJoursDeLExempleAppartiennentAuScenario() {
        Set<String> typologies =
                scenario.typologies().stream().map(TypologieDto::id).collect(Collectors.toSet());
        Set<LocalDate> joursEvenement =
                scenario.creneaux().stream().map(CreneauDto::date).collect(Collectors.toSet());

        inEdition(() -> csvImport.apply(demandeExemple()));
        List<Animateur> importes = inEdition(() -> referenceData.listAnimateurs());

        assertThat(importes).allSatisfy(animateur -> {
            assertThat(typologies).containsAll(animateur.getCompetences().keySet());
            assertThat(typologies).containsAll(animateur.getSouhaits());
            assertThat(joursEvenement).containsAll(animateur.getJoursIndisponibles());
        });
        assertThat(importes)
                .anySatisfy(animateur -> assertThat(animateur.getCompetences())
                        .hasSizeGreaterThan(1)
                        .containsValue(NiveauCompetence.REFERENT));
        assertThat(importes)
                .anySatisfy(animateur -> assertThat(animateur.getSouhaits()).hasSizeGreaterThan(1));
        assertThat(importes)
                .anySatisfy(animateur ->
                        assertThat(animateur.getJoursIndisponibles()).hasSizeGreaterThan(1));
    }

    /**
     * Its dates are ISO, and that is not a matter of taste: opened in a
     * spreadsheet and saved again, {@code 12/09/2026} comes back
     * {@code 12/09/26}, a year the import cannot read — the row is then
     * rejected for a reason its author never wrote. {@code 2026-09-12} is
     * given back unchanged by Excel as by LibreOffice.
     */
    @Test
    void lesDatesDeLExempleSontEnIsoPourSurvivreAUnTableur() {
        String exemple = exemple();

        assertThat(exemple).doesNotContainPattern("\\d{2}/\\d{2}/\\d{4}");
        assertThat(exemple).containsPattern("\\d{4}-\\d{2}-\\d{2}");
    }

    /**
     * The screen's « télécharger un exemple » serves this very resource, with
     * one addition: the byte order mark. Excel ignores the response's
     * {@code charset=utf-8} once the file is on disk and falls back to its
     * legacy code page, so an accented name would arrive mangled; the mark is
     * what tells it otherwise. It costs nothing on the way back —
     * {@code CsvParser} strips it before reading the header, which
     * {@link #leFichierServiSeReimporteAvecSaMarque()} proves.
     */
    @Test
    void lEndpointDeTelechargementSertLaMemeRessource() {
        String servi = given().when()
                .get("/api/animateurs/import-csv/exemple")
                .then()
                .statusCode(200)
                .header("Content-Disposition", "attachment; filename=\"festival-realiste-animateurs.csv\"")
                .extract()
                .asString();

        assertThat(servi).isEqualTo("\uFEFF" + exemple());
    }

    /** Downloaded and sent straight back, untouched: still not one rejected row. */
    @Test
    void leFichierServiSeReimporteAvecSaMarque() {
        String servi = given().when()
                .get("/api/animateurs/import-csv/exemple")
                .then()
                .statusCode(200)
                .extract()
                .asString();

        AnimateurCsvImportReport rapport = inEdition(() -> csvImport.preview(
                new AnimateurCsvImportRequest("festival-realiste-animateurs.csv", servi, null, false, false)));

        assertThat(servi).startsWith("\uFEFF");
        assertThat(rapport.rejected()).isZero();
        assertThat(rapport.total()).isEqualTo(scenario.animateurs().size());
    }

    /* ------------------------------- Helpers ------------------------------- */

    private <T> T inEdition(java.util.concurrent.Callable<T> travail) {
        return editionContext.executeIn(EDITION, travail);
    }

    private static AnimateurCsvImportRequest demandeExemple() {
        return new AnimateurCsvImportRequest("festival-realiste-animateurs.csv", exemple(), null, false, false);
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
