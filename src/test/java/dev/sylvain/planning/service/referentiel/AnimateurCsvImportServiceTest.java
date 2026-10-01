package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.edition.EditionService;
import dev.sylvain.planning.service.espace.DeclarationDisponibiliteRepository;
import dev.sylvain.planning.service.espace.DeclarationDisponibiliteService;
import dev.sylvain.planning.service.espace.EspaceAnimateurService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The tabular import, end to end and in its own edition.
 *
 * <p>Two things are pinned here beyond the row-by-row report. First, the
 * preview writes strictly nothing — the whole safety of the screen rests on
 * it. Second, an animateur imported with off days finds those days
 * <b>pre-checked</b> in their espace, which is the only thing that makes the
 * feature worth anything: an import nobody sees the effect of is a file that
 * went nowhere.</p>
 */
@QuarkusTest
class AnimateurCsvImportServiceTest {

    /** Drawn by the application when the edition is created (ADR 0050). */
    private static String edition;

    private static final LocalDate JOUR1 = LocalDate.of(2030, 7, 18);
    private static final LocalDate JOUR2 = LocalDate.of(2030, 7, 19);

    @Inject
    AnimateurCsvImportService csvImport;

    @Inject
    ReferenceDataService referenceData;

    @Inject
    EspaceAnimateurService espace;

    @Inject
    DeclarationDisponibiliteService declarationService;

    @Inject
    EditionService editions;

    @Inject
    EditionContext editionContext;

    @Inject
    ObjectMapper objectMapper;

    /**
     * The ids the edition drew for the two typologies. The files below cite
     * them by their codes, {@code jeux} and {@code ateliers}; what is stored
     * is the id (ADR 0050).
     */
    private String jeuxId;

    private String ateliersId;

    @BeforeEach
    void createEdition() {
        edition = editions.create(new Edition(null, "Import CSV", false, null)).getId();
        editionContext.executeIn(edition, () -> {
            jeuxId = referenceData
                    .createTypologie(new TypologieItem(null, "jeux", "Jeux de société", false, null, null, null))
                    .id();
            ateliersId = referenceData
                    .createTypologie(new TypologieItem(null, "ateliers", "Ateliers", false, null, null, null))
                    .id();
            referenceData.createCreneau(new Creneau(null, 1, JOUR1, LocalTime.of(10, 0), LocalTime.of(12, 0)));
            referenceData.createCreneau(new Creneau(null, 2, JOUR2, LocalTime.of(10, 0), LocalTime.of(12, 0)));
        });
    }

    @AfterEach
    void supprimerEdition() {
        editions.delete(edition);
    }

    private <T> T inEdition(java.util.concurrent.Callable<T> travail) {
        return editionContext.executeIn(edition, travail);
    }

    private static AnimateurCsvImportRequest demande(String contenu) {
        return new AnimateurCsvImportRequest("animateurs.csv", contenu, null, false, false);
    }

    /* ------------------------------- Nominal ------------------------------- */

    @Test
    void previewNeCreeAucunAnimateur() {
        String csv = """
                prenom;nom;date de naissance;email
                Amélie;Durand;12/03/1990;amelie@example.org
                Bruno;Lefèvre;1988-06-04;bruno@example.org
                """;

        AnimateurCsvImportReport rapport = inEdition(() -> csvImport.preview(demande(csv)));

        assertThat(rapport.applied()).isFalse();
        assertThat(rapport.accepted()).isEqualTo(2);
        assertThat(rapport.created()).isEqualTo(2);
        assertThat(inEdition(() -> referenceData.listAnimateurs())).isEmpty();
    }

    @Test
    void theImportWritesExactlyWhatTheReportAnnounces() {
        String csv = """
                prenom;nom;date de naissance;competences;jours indisponibles
                Amélie;Durand;12/03/1990;jeux:REFERENT|ateliers;18/07/2030
                Bruno;Lefèvre;1988-06-04;jeux;
                """;

        AnimateurCsvImportReport rapport = inEdition(() -> csvImport.apply(demande(csv)));

        assertThat(rapport.applied()).isTrue();
        assertThat(rapport.accepted()).isEqualTo(2);
        assertThat(rapport.rejected()).isZero();
        List<Animateur> ecrits = inEdition(() -> referenceData.listAnimateurs());
        assertThat(ecrits).hasSize(rapport.accepted());
        Animateur amelie = ecrits.stream()
                .filter(a -> "Amélie".equals(a.getPrenom()))
                .findFirst()
                .orElseThrow();
        assertThat(amelie.getDateNaissance()).isEqualTo(LocalDate.of(1990, 3, 12));
        assertThat(amelie.getCompetences())
                .containsEntry(jeuxId, NiveauCompetence.REFERENT)
                .containsEntry(ateliersId, NiveauCompetence.AUTONOME);
        assertThat(amelie.getJoursIndisponibles()).containsExactly(JOUR1);
    }

    /**
     * The test the issue asks for: what an organiser types in a spreadsheet is
     * what the animateur finds ticked in their espace, and the report says so.
     */
    @Test
    void unAnimateurImporteRetrouveSesJoursPreCochesDansSonEspace() {
        String csv = """
                prenom;nom;date de naissance;jours indisponibles
                Amélie;Durand;12/03/1990;18/07/2030|2030-07-19
                """;

        AnimateurCsvImportReport rapport = inEdition(() -> csvImport.apply(demande(csv)));

        assertThat(rapport.rows()).singleElement().satisfies(ligne -> {
            assertThat(ligne.action()).isEqualTo(AnimateurCsvImportReport.ImportAction.CREATED);
            assertThat(ligne.joursIndisponibles()).containsExactly(JOUR1, JOUR2);
        });
        String id = rapport.rows().get(0).animateurId();
        EspaceAnimateurService.DeclarationEspaceView vue = inEdition(() -> espace.buildDeclarationView(id));
        assertThat(vue.joursActuels()).containsExactly(JOUR1, JOUR2);
        assertThat(vue.joursEvenement()).contains(JOUR1, JOUR2);
    }

    /* ------------------------------- Refusals ------------------------------ */

    @Test
    void unJourHorsDesDatesDeCreneauxRejetteLaLigne() {
        String csv = """
                prenom;nom;date de naissance;jours indisponibles
                Amélie;Durand;12/03/1990;18/07/2030
                Bruno;Lefèvre;04/06/1988;01/01/2031
                """;

        AnimateurCsvImportReport rapport = inEdition(() -> csvImport.apply(demande(csv)));

        assertThat(rapport.accepted()).isEqualTo(1);
        assertThat(rapport.rejected()).isEqualTo(1);
        assertThat(rapport.rows().get(1).line()).isEqualTo(3);
        assertThat(rapport.rows().get(1).reasons())
                .anySatisfy(motif -> assertThat(motif).contains("hors des dates de l'événement"));
        assertThat(inEdition(() -> referenceData.listAnimateurs())).hasSize(1);
    }

    @Test
    void sansAucunCreneauLImportEstRefuse() {
        inEdition(() -> {
            referenceData.listCreneaux().forEach(creneau -> referenceData.deleteCreneau(creneau.getId()));
            return null;
        });

        assertThatThrownBy(() -> inEdition(
                        () -> csvImport.preview(demande("prenom;nom;date de naissance\nAmélie;Durand;12/03/1990\n"))))
                .isInstanceOf(BusinessError.Conflict.class)
                .hasMessageContaining("Aucun créneau");
    }

    @Test
    void unFichierTableurEstRefuseAvecUnMessageExplicite() {
        AnimateurCsvImportRequest xlsx = new AnimateurCsvImportRequest(
                "roster.xlsx", "prenom;nom;date de naissance\nAmélie;Durand;12/03/1990\n", null, false, false);

        assertThatThrownBy(() -> inEdition(() -> csvImport.preview(xlsx)))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("seul le CSV est lu");
    }

    @Test
    void chaqueMotifDeRejetEstNommeAuRapport() {
        String csv = """
                prenom;nom;date de naissance;email;manager;competences;jours indisponibles
                ;;01/01/1990;;;;
                Carla;Moreau;32/13/1990;carla@example.org;oui;jeux;18/07/2030
                Diego;Santos;01/01/1990;pas-une-adresse;oui;jeux;18/07/2030
                Elena;Rossi;01/01/1990;elena@example.org;peut-être;jeux;18/07/2030
                Farid;Belkacem;01/01/1990;farid@example.org;non;inconnue;18/07/2030
                Gaia;Conti;01/01/1990;gaia@example.org;non;jeux;pas-une-date
                Hugo;Petit;;hugo@example.org;non;jeux;18/07/2030
                Inès;Blanc;01/01/1990;ines@example.org;non;jeux;01/01/2031
                """;

        AnimateurCsvImportReport rapport = inEdition(() -> csvImport.preview(demande(csv)));

        assertThat(rapport.rejected()).isEqualTo(8);
        assertThat(rapport.accepted()).isZero();
        assertThat(motif(rapport, 2)).contains("ne nomme personne");
        assertThat(motif(rapport, 3)).contains("Date de naissance illisible");
        assertThat(motif(rapport, 4)).contains("Adresse e-mail invalide");
        assertThat(motif(rapport, 5)).contains("manager");
        assertThat(motif(rapport, 6)).contains("Typologie");
        assertThat(motif(rapport, 7)).contains("Jour d'indisponibilité illisible");
        assertThat(motif(rapport, 8)).contains("Date de naissance absente");
        assertThat(motif(rapport, 9)).contains("hors des dates de l'événement");
    }

    /**
     * The phone column is read onto the fiche, trimmed like any cell, and a
     * number longer than its column rejects the row by name rather than
     * failing the whole import on the database.
     */
    @Test
    void thePhoneColumnIsWrittenAndATooLongNumberRejectsItsRow() {
        String csv = """
                prenom;nom;date de naissance;portable
                Amélie;Durand;12/03/1990; 06 12 34 56 78\s
                Bruno;Lefèvre;1988-06-04;+33 6 12 34 56 78 poste 1234567890
                """;

        AnimateurCsvImportReport rapport = inEdition(() -> csvImport.apply(demande(csv)));

        assertThat(rapport.accepted()).isEqualTo(1);
        assertThat(motif(rapport, 3)).contains("Numéro de téléphone trop long");
        assertThat(inEdition(() -> referenceData.listAnimateurs()))
                .singleElement()
                .satisfies(amelie -> assertThat(amelie.getTelephone()).isEqualTo("06 12 34 56 78"));
    }

    private static String motif(AnimateurCsvImportReport rapport, int ligne) {
        return rapport.rows().stream()
                .filter(row -> row.line() == ligne)
                .findFirst()
                .orElseThrow()
                .reasons()
                .toString();
    }

    private static AnimateurCsvImportReport.ImportedRow ligne(AnimateurCsvImportReport rapport, int ligne) {
        return rapport.rows().stream()
                .filter(row -> row.line() == ligne)
                .findFirst()
                .orElseThrow();
    }

    /**
     * The rule the fiche form and the MCP tool apply — no fiche without a
     * first name, a last name and a birth date — holds per row here, so the
     * CSV is not a way around it. Read on the fiche as it would stand, not on
     * the cell: a row matched by its address whose fiche already carries both
     * names keeps them, exactly like the birth date.
     */
    @Test
    void aRowLeavingAFicheWithoutAFirstOrLastNameIsRejectedUnlessTheFicheHasThem() {
        Animateur existant = new Animateur();
        existant.setPrenom("Amélie");
        existant.setNom("Durand");
        existant.setDateNaissance(LocalDate.of(1990, 3, 12));
        existant.setEmail("amelie@example.org");
        inEdition(() -> referenceData.createAnimateur(existant));
        // The row designates the fiche by its address; an id is never read.
        String csv = """
                prenom;nom;date de naissance;email
                ;;;amelie@example.org
                ;;04/06/1988;bruno@example.org
                Carla;;01/01/1990;
                ;Santos;01/01/1990;
                """;

        AnimateurCsvImportReport rapport = inEdition(() -> csvImport.preview(demande(csv)));

        assertThat(ligne(rapport, 2).action()).isEqualTo(AnimateurCsvImportReport.ImportAction.UPDATED);
        assertThat(ligne(rapport, 2).reasons()).isEmpty();
        assertThat(motif(rapport, 3)).contains("ne nomme personne");
        assertThat(motif(rapport, 4)).contains("Nom absent").doesNotContain("Prénom absent");
        assertThat(motif(rapport, 5)).contains("Prénom absent").doesNotContain("Nom absent");
        assertThat(rapport.rejected()).isEqualTo(3);
    }

    /**
     * The date a spreadsheet rewrote is read, and the preview says how: the
     * row is accepted with the birth date resolved on the nearest past year,
     * and its warning quotes both the cell and the reading — that sentence is
     * the only thing standing between a silent 1930 and the operator.
     */
    @Test
    void twoDigitBirthYearIsReadAndSaidBackInThePreview() {
        String csv = """
                prenom;nom;date de naissance
                Amélie;Durand;01-01-00
                Bruno;Lefèvre;5/3/95
                """;

        AnimateurCsvImportReport rapport = inEdition(() -> csvImport.preview(demande(csv)));

        assertThat(rapport.rejected()).isZero();
        assertThat(ligne(rapport, 2).warnings())
                .containsExactly("Date de naissance « 01-01-00 » lue comme le 2000-01-01 "
                        + "(année sur deux chiffres, réécrite par un tableur) : vérifiez-la avant d'importer.");
        assertThat(ligne(rapport, 3).warnings()).singleElement().asString().contains("lue comme le 1995-03-05");
        assertThat(inEdition(() -> referenceData.listAnimateurs())).isEmpty();
    }

    /**
     * An off day resolves under the event's last day, not today's: the test
     * edition runs in 2030, and {@code 18/07/30} must land on its first day
     * rather than in 1930 and be refused as outside the event.
     */
    @Test
    void twoDigitOffDayYearResolvesOnTheEventAndIsSaidBack() {
        String csv = """
                prenom;nom;date de naissance;jours indisponibles
                Amélie;Durand;12/03/1990;18/07/30|2030-07-19
                """;

        AnimateurCsvImportReport rapport = inEdition(() -> csvImport.preview(demande(csv)));

        assertThat(rapport.rejected()).isZero();
        AnimateurCsvImportReport.ImportedRow amelie = ligne(rapport, 2);
        assertThat(amelie.joursIndisponibles()).containsExactly(JOUR1, JOUR2);
        assertThat(amelie.warnings())
                .containsExactly("Jour d'indisponibilité « 18/07/30 » lu comme le 2030-07-18 "
                        + "(année sur deux chiffres, réécrite par un tableur) : vérifiez-le avant d'importer.");
    }

    /**
     * Read, then checked: a two-digit year resolving to a birth date in the
     * future is still refused, and the refusal quotes the reading — a cell
     * refused as implausible must say which date it was taken for. The hint
     * on an unreadable short date says what to do, not where to restart from.
     */
    @Test
    void implausibleOrUnreadableTwoDigitDatesAreRefusedWithTheReadingAndTheWayOut() {
        // Two digits never reach further back than 99 years, so the only
        // implausible reading is a day still to come in the current year —
        // which does not exist on New Year's Eve.
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        assumeTrue(tomorrow.getYear() == LocalDate.now().getYear());
        String csv = """
                prenom;nom;date de naissance
                Carla;Moreau;%s
                Diego;Santos;31/02/99
                """.formatted(tomorrow.format(DateTimeFormatter.ofPattern("d/M/uu")));

        AnimateurCsvImportReport rapport = inEdition(() -> csvImport.preview(demande(csv)));

        assertThat(rapport.rejected()).isEqualTo(2);
        assertThat(motif(rapport, 2))
                .contains("Date de naissance invraisemblable")
                .contains("lue comme le " + tomorrow);
        assertThat(motif(rapport, 3))
                .contains("Date de naissance illisible")
                .contains("Ne rouvrez pas le CSV dans un tableur")
                .doesNotContain("fichier d'exemple");
    }

    @Test
    void unDoublonInterneAuFichierEstRejeteEnNommantLaPremiereLigne() {
        String csv = """
                prenom;nom;date de naissance
                Amélie;Durand;12/03/1990
                Amelie;DURAND;12/03/1990
                """;

        AnimateurCsvImportReport rapport = inEdition(() -> csvImport.preview(demande(csv)));

        assertThat(rapport.accepted()).isEqualTo(1);
        assertThat(motif(rapport, 3)).contains("Doublon dans le fichier").contains("ligne 2");
    }

    @Test
    void aNamesakeAlreadyStoredPreventsDecidingAndRejectsTheRow() {
        String premier =
                inEdition(() -> referenceData.createAnimateur(homonyme())).getId();
        String second =
                inEdition(() -> referenceData.createAnimateur(homonyme())).getId();

        AnimateurCsvImportReport rapport =
                inEdition(() -> csvImport.preview(demande("prenom;nom;date de naissance\nJean;Martin;01/01/1990\n")));

        assertThat(rapport.rejected()).isEqualTo(1);
        assertThat(motif(rapport, 2))
                .contains("Plusieurs animateurs se nomment")
                .contains(premier)
                .contains(second);
    }

    private static Animateur homonyme() {
        return new Animateur(null, "Jean", "Martin", LocalDate.of(1990, 1, 1), false);
    }

    @Test
    void uneLignePlusLongueQueLEnTeteEstRejeteeCommeDecalee() {
        String csv = "prenom;nom\nAmélie;Durand;colonne en trop\n";

        AnimateurCsvImportReport rapport = inEdition(() -> csvImport.preview(demande(csv)));

        assertThat(rapport.rejected()).isEqualTo(1);
        assertThat(motif(rapport, 2)).contains("de plus que l'en-tête");
    }

    /* ------------------------- Existing animateurs ------------------------- */

    @Test
    void parDefautLesJoursDejaDeclaresSontConservesEtCompletes() {
        Animateur existant = new Animateur("A-FUSION", "Amélie", "Durand", LocalDate.of(1990, 3, 12), false);
        existant.setJoursIndisponibles(Set.of(JOUR1));
        inEdition(() -> referenceData.createAnimateur(existant));

        AnimateurCsvImportReport rapport =
                inEdition(() -> csvImport.apply(demande("prenom;nom;jours indisponibles\nAmélie;Durand;19/07/2030\n")));

        assertThat(rapport.updated()).isEqualTo(1);
        assertThat(rapport.rows().get(0).joursIndisponibles()).containsExactly(JOUR1, JOUR2);
        assertThat(inEdition(() -> referenceData.listAnimateurs()).get(0).getJoursIndisponibles())
                .containsExactlyInAnyOrder(JOUR1, JOUR2);
    }

    @Test
    void leRemplacementDesJoursEcraseCeQueLaFicheportait() {
        Animateur existant = new Animateur("A-REMPL", "Amélie", "Durand", LocalDate.of(1990, 3, 12), false);
        existant.setJoursIndisponibles(Set.of(JOUR1));
        inEdition(() -> referenceData.createAnimateur(existant));
        AnimateurCsvImportRequest remplacement = new AnimateurCsvImportRequest(
                "a.csv", "prenom;nom;jours indisponibles\nAmélie;Durand;19/07/2030\n", null, false, true);

        inEdition(() -> csvImport.apply(remplacement));

        assertThat(inEdition(() -> referenceData.listAnimateurs()).get(0).getJoursIndisponibles())
                .containsExactly(JOUR2);
    }

    /** A column the file does not carry never touches what the fiche already holds. */
    @Test
    void aColumnTheFileDoesNotCarryLeavesTheFicheAlone() {
        Animateur existant = new Animateur("A-INTACT", "Amélie", "Durand", LocalDate.of(1990, 3, 12), false);
        existant.setJoursIndisponibles(Set.of(JOUR1));
        existant.setEmail("amelie@example.org");
        existant.setCompetences(Map.of("jeux", NiveauCompetence.REFERENT));
        inEdition(() -> referenceData.createAnimateur(existant));
        AnimateurCsvImportRequest sansColonneJours =
                new AnimateurCsvImportRequest("a.csv", "prenom;nom\nAmélie;Durand\n", null, false, true);

        inEdition(() -> csvImport.apply(sansColonneJours));

        Animateur relu = inEdition(() -> referenceData.listAnimateurs()).get(0);
        assertThat(relu.getJoursIndisponibles()).containsExactly(JOUR1);
        assertThat(relu.getEmail()).isEqualTo("amelie@example.org");
        // Written by its code, stored and read back by its id.
        assertThat(relu.getCompetences()).containsEntry(jeuxId, NiveauCompetence.REFERENT);
    }

    @Test
    void aPendingDeclarationIsFlaggedInTheReport() {
        Animateur existant = new Animateur(null, "Amélie", "Durand", LocalDate.of(1990, 3, 12), false);
        String id = inEdition(() -> referenceData.createAnimateur(existant)).getId();
        inEdition(() -> {
            declarationService.configure(
                    new DeclarationDisponibiliteRepository.FenetreCollecte(true, null, null), false);
            return declarationService.submit(
                    id, new DeclarationDisponibiliteService.NouvelleDeclaration(List.of(JOUR1), List.of(), null));
        });

        AnimateurCsvImportReport rapport = inEdition(
                () -> csvImport.preview(demande("prenom;nom;jours indisponibles\nAmélie;Durand;19/07/2030\n")));

        assertThat(rapport.rows().get(0).warnings())
                .anySatisfy(avis -> assertThat(avis).contains("déclaration de disponibilités en attente"));
    }

    /* ------------------------------ Replacement ---------------------------- */

    @Test
    void leRemplacementCompletSupprimeLesAbsentsDuFichier() {
        inEdition(() -> referenceData.createAnimateur(
                new Animateur("A-PARTANT", "Zoé", "Absente", LocalDate.of(1990, 1, 1), false)));
        AnimateurCsvImportRequest remplacement = new AnimateurCsvImportRequest(
                "a.csv", "prenom;nom;date de naissance\nAmélie;Durand;12/03/1990\n", null, true, false);

        AnimateurCsvImportReport rapport = inEdition(() -> csvImport.apply(remplacement));

        assertThat(rapport.deleted()).isEqualTo(1);
        assertThat(inEdition(() -> referenceData.listAnimateurs()))
                .extracting(Animateur::getPrenom)
                .containsExactly("Amélie");
    }

    /**
     * A replacement over a file that still has rejected rows would delete the
     * very people those rows failed to describe. It is refused, whole.
     */
    @Test
    void leRemplacementCompletEstRefuseTantQuUneLigneEstRejetee() {
        inEdition(() -> referenceData.createAnimateur(
                new Animateur("A-GARDE", "Zoé", "Absente", LocalDate.of(1990, 1, 1), false)));
        AnimateurCsvImportRequest remplacement = new AnimateurCsvImportRequest(
                "a.csv",
                "prenom;nom;date de naissance;jours indisponibles\nAmélie;Durand;12/03/1990;01/01/2031\n",
                null,
                true,
                false);

        assertThatThrownBy(() -> inEdition(() -> csvImport.apply(remplacement)))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("Remplacement complet");
        assertThat(inEdition(() -> referenceData.listAnimateurs())).hasSize(1);
    }

    /** A row carrying only an address names nobody: it must not create a nameless fiche. */
    @Test
    void uneLigneSansNomNiIdentifiantEstRejeteeMemeAvecUneAdresse() {
        String csv = """
                prenom;nom;date de naissance;email
                ;;01/01/1990;fantome@example.org
                """;

        AnimateurCsvImportReport rapport = inEdition(() -> csvImport.preview(demande(csv)));

        assertThat(rapport.accepted()).isZero();
        assertThat(motif(rapport, 2)).contains("ne nomme personne");
    }

    /* -------------------------------- Mapping ------------------------------- */

    @Test
    void manualMappingOverridesHeaders() {
        String csv = "colonne A;colonne B;colonne C\nDurand;Amélie;12/03/1990\n";
        AnimateurCsvMapping mapping = new AnimateurCsvMapping(1, 0, 2, null, null, null, null, null, null);
        AnimateurCsvImportRequest demande = new AnimateurCsvImportRequest("a.csv", csv, mapping, false, false);

        AnimateurCsvImportReport rapport = inEdition(() -> csvImport.apply(demande));

        assertThat(rapport.accepted()).isEqualTo(1);
        Animateur ecrit = inEdition(() -> referenceData.listAnimateurs()).get(0);
        assertThat(ecrit.getPrenom()).isEqualTo("Amélie");
        assertThat(ecrit.getNom()).isEqualTo("Durand");
    }

    /**
     * An unusable mapping still gets a report — the screen needs the columns to
     * draw its mapping editor. Refusing the preview would leave the operator
     * with an error and no way to correct what caused it.
     */
    @Test
    void mappingNamingNobodyReportsAndRefusesToWrite() {
        AnimateurCsvMapping sansIdentite = new AnimateurCsvMapping(null, null, 0, null, null, null, null, null, null);
        AnimateurCsvImportRequest demande =
                new AnimateurCsvImportRequest("a.csv", "date de naissance\n01/01/1990\n", sansIdentite, false, false);

        AnimateurCsvImportReport rapport = inEdition(() -> csvImport.preview(demande));

        assertThat(rapport.accepted()).isZero();
        assertThat(rapport.warnings()).anySatisfy(avis -> assertThat(avis).contains("Aucune colonne"));
        assertThat(motif(rapport, 2)).contains("ne nomme personne");
        assertThatThrownBy(() -> inEdition(() -> csvImport.apply(demande)))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("au moins une colonne");
        assertThat(inEdition(() -> referenceData.listAnimateurs())).isEmpty();
    }

    @Test
    void unFichierTropVolumineuxEstRefuseAvantToutAnalyse() {
        String enorme = "prenom;nom\n" + "A;B\n".repeat(300_000);
        AnimateurCsvImportRequest demande = new AnimateurCsvImportRequest("a.csv", enorme, null, false, false);

        assertThatThrownBy(() -> inEdition(() -> csvImport.preview(demande)))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("trop volumineux")
                .hasMessageContaining("1 000 000 caractères");
    }

    /* ------------------------------- Lengths ------------------------------- */

    /**
     * The likeliest mistake of the whole screen: a free-text column dropped on
     * {@code nom}. The preview used to show it in green and the write then died
     * on the column width — a 500, and the whole file rolled back.
     */
    @Test
    void uneColonneMalMappeeSurLeNomEstRejeteeAvecSaLongueur() {
        String commentaire = "Disponible surtout le week-end et en soirée ".repeat(10);
        String csv = "prenom;nom;date de naissance\nAmélie;" + commentaire + ";12/03/1990\n";

        AnimateurCsvImportReport rapport = inEdition(() -> csvImport.preview(demande(csv)));

        assertThat(rapport.accepted()).isZero();
        assertThat(rapport.rejected()).isEqualTo(1);
        assertThat(motif(rapport, 2))
                .contains("Nom trop long")
                .contains(String.valueOf(commentaire.trim().length()))
                .contains("128 au maximum");
    }

    /** Same guard on the other two columns the database bounds. */
    @Test
    void anAddressOrAFirstNameTooLongIsRejected() {
        String csv = "prenom;nom;date de naissance;email\n"
                + "Bruno;Lefèvre;04/06/1988;" + "z".repeat(250) + "@example.org\n"
                + "y".repeat(129) + ";Petit;01/01/1990;\n";

        AnimateurCsvImportReport rapport = inEdition(() -> csvImport.preview(demande(csv)));

        assertThat(rapport.accepted()).isZero();
        assertThat(motif(rapport, 2)).contains("Adresse e-mail trop longue").contains("255 au maximum");
        assertThat(motif(rapport, 3)).contains("Prénom trop long").contains("128 au maximum");
    }

    /**
     * A long name is refused on its own length, never on the id's: the id of a
     * new fiche is drawn by the edition (ADR 0050), no longer derived from the
     * name, so a long but acceptable name goes through and gets a short one.
     */
    @Test
    void aLongNameIsWrittenUnderADrawnIdThatFitsTheColumn() {
        String prenom = "Marie".repeat(30);
        String csv = "prenom;nom;date de naissance\n" + prenom + ";Durand;12/03/1990\n";

        AnimateurCsvImportReport rapport = inEdition(() -> csvImport.preview(demande(csv)));

        assertThat(rapport.accepted()).isZero();
        assertThat(motif(rapport, 2)).contains("Prénom trop long");

        String csvCourt =
                "prenom;nom;date de naissance\n" + "Marie".repeat(12) + ";" + "Durand".repeat(10) + ";12/03/1990\n";

        AnimateurCsvImportReport ecrit = inEdition(() -> csvImport.apply(demande(csvCourt)));

        assertThat(ecrit.accepted()).isEqualTo(1);
        assertThat(inEdition(() -> referenceData.listAnimateurs()))
                .singleElement()
                .satisfies(anime -> assertThat(anime.getId()).matches("A\\d+"));
    }

    /* ------------------------------- Encoding ------------------------------ */

    /**
     * A Windows-1252 export — Excel's French default — read as UTF-8 by the
     * browser: the accents are already gone. It used to be answered « ce n'est
     * pas du CSV, enregistrez en CSV » to somebody who had just done that.
     */
    @Test
    void unCsvMalEncodeEstRefuseEnNommantLEncodage() {
        String csv = "\uFFFDquipe;prenom;nom;date de naissance\nA;Am\uFFFDlie;Durand;12/03/1990\n";

        assertThatThrownBy(() -> inEdition(() -> csvImport.preview(demande(csv))))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("n'est pas encodé en UTF-8")
                .hasMessageContaining("CSV UTF-8");
    }

    /** A real binary stays a format refusal: it is unreadable through and through. */
    @Test
    void unBinaireResteRefuseCommeFormat() {
        String binaire = "\uFFFD\0\uFFFD\uFFFD\0".repeat(50);

        assertThatThrownBy(() -> inEdition(() ->
                        csvImport.preview(new AnimateurCsvImportRequest("roster.dat", binaire, null, false, false))))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("seul le CSV est lu");
    }

    /* -------------------------- Duplicates, warnings ------------------------ */

    /**
     * A duplicate must point at the row that wrote the fiche. Pointing at a row
     * that was itself refused turns the good one away and sends the operator to
     * a line that imported nothing.
     */
    @Test
    void unDoublonRenvoieALaLigneRetenueEtNonAUneLigneRejetee() {
        String csv = """
                prenom;nom;date de naissance
                Amélie;Durand;32/13/1990
                Amélie;Durand;12/03/1990
                Amelie;DURAND;12/03/1990
                """;

        AnimateurCsvImportReport rapport = inEdition(() -> csvImport.preview(demande(csv)));

        assertThat(rapport.accepted()).isEqualTo(1);
        assertThat(motif(rapport, 2)).contains("Date de naissance illisible").doesNotContain("Doublon");
        assertThat(rapport.rows().get(1).action()).isEqualTo(AnimateurCsvImportReport.ImportAction.CREATED);
        assertThat(motif(rapport, 4)).contains("Doublon dans le fichier").contains("ligne 3");
    }

    /* -------------------------- Probable duplicates -------------------------- */

    private String store(String prenom, String nom, LocalDate naissance, String email) {
        Animateur animateur = new Animateur(null, prenom, nom, naissance, false);
        animateur.setEmail(email);
        return inEdition(() -> referenceData.createAnimateur(animateur)).getId();
    }

    private static AnimateurCsvImportReport.ProbableDuplicate onRow(int line) {
        return new AnimateurCsvImportReport.ProbableDuplicate(AnimateurCsvImportReport.DuplicateKind.ROW, line, null);
    }

    private static AnimateurCsvImportReport.ProbableDuplicate onFiche(
            AnimateurCsvImportReport.DuplicateKind kind, String id) {
        return new AnimateurCsvImportReport.ProbableDuplicate(kind, null, id);
    }

    /**
     * Two rows of one file, two addresses, one person: resolution keeps them
     * apart, so the birth date is what tells. Both rows are flagged and stay
     * accepted, each naming the other — and the write says exactly what the
     * preview said.
     */
    @Test
    void twoRowsWithTheSameIdentityAreBothFlaggedWithTheOtherLine() {
        String csv = """
                prenom;nom;date de naissance;email
                Amélie;Durand;12/03/1990;amelie@example.org
                Bruno;Lefèvre;04/06/1988;
                Amélie;Durand;12/03/1990;
                """;

        AnimateurCsvImportReport apercu = inEdition(() -> csvImport.preview(demande(csv)));

        assertThat(apercu.accepted()).isEqualTo(3);
        assertThat(apercu.doublonsProbables()).isEqualTo(2);
        assertThat(ligne(apercu, 2).doublonDe()).containsExactly(onRow(4));
        assertThat(ligne(apercu, 2).warnings())
                .singleElement()
                .asString()
                .startsWith("Probable doublon de la ligne 4 : mêmes nom, prénom et date de naissance");
        assertThat(ligne(apercu, 4).doublonDe()).containsExactly(onRow(2));
        assertThat(ligne(apercu, 3).doublonDe()).isEmpty();
        assertThat(inEdition(() -> referenceData.listAnimateurs())).isEmpty();

        AnimateurCsvImportReport ecrit = inEdition(() -> csvImport.apply(demande(csv)));

        assertThat(ecrit.doublonsProbables()).isEqualTo(2);
        assertThat(ecrit.rows())
                .extracting(AnimateurCsvImportReport.ImportedRow::warnings)
                .isEqualTo(apercu.rows().stream()
                        .map(AnimateurCsvImportReport.ImportedRow::warnings)
                        .toList());
        assertThat(ecrit.created()).isEqualTo(3);
    }

    /** « Marie-Hélène » is « marie helene »: case, accents and punctuation never tell two people apart. */
    @Test
    void caseAccentsAndPunctuationAreIgnored() {
        String csv = """
                prenom;nom;date de naissance;email
                Marie-Hélène;Dupont;12/03/1990;mh@example.org
                marie helene;DUPONT;1990-03-12;
                """;

        AnimateurCsvImportReport rapport = inEdition(() -> csvImport.preview(demande(csv)));

        assertThat(rapport.doublonsProbables()).isEqualTo(2);
        assertThat(ligne(rapport, 3).doublonDe()).containsExactly(onRow(2));
    }

    /** Twins share a surname and a birth date, not a first name: nothing to flag. */
    @Test
    void twinsWithDifferentFirstNamesAreNotFlagged() {
        String csv = """
                prenom;nom;date de naissance
                Léa;Martin;01/01/2010
                Zoé;Martin;01/01/2010
                """;

        AnimateurCsvImportReport rapport = inEdition(() -> csvImport.preview(demande(csv)));

        assertThat(rapport.accepted()).isEqualTo(2);
        assertThat(rapport.doublonsProbables()).isZero();
        assertThat(rapport.rows()).allSatisfy(row -> assertThat(row.warnings()).isEmpty());
    }

    /**
     * A row without an address lands on a fiche by its name alone. When that
     * fiche was born on another day, the import is about to rewrite a
     * namesake's birth date — and with it their minor / adult regime.
     */
    @Test
    void aRowMatchedByNameToAFicheBornAnotherDayIsANamesake() {
        String jean = store("Jean", "Martin", LocalDate.of(1990, 1, 1), null);
        String csv = """
                prenom;nom;date de naissance
                Jean;Martin;12/09/2008
                """;

        AnimateurCsvImportReport rapport = inEdition(() -> csvImport.preview(demande(csv)));

        AnimateurCsvImportReport.ImportedRow row = ligne(rapport, 2);
        assertThat(row.action()).isEqualTo(AnimateurCsvImportReport.ImportAction.UPDATED);
        assertThat(row.doublonDe()).containsExactly(onFiche(AnimateurCsvImportReport.DuplicateKind.NAMESAKE, jean));
        assertThat(row.warnings())
                .singleElement()
                .asString()
                .startsWith("Homonyme ?")
                .contains(jean)
                .contains("01/01/1990")
                .contains("12/09/2008");
        assertThat(rapport.doublonsProbables()).isEqualTo(1);
    }

    /**
     * The date cell left empty takes the fiche's — the date the write keeps —
     * so a row matched by its name is no namesake, and a key compared against
     * another fiche uses that date too.
     */
    @Test
    void anAbsentDateCellComparesOnTheFichesDate() {
        String jeanne = store("Jeanne", "Martin", LocalDate.of(1990, 1, 1), "jm@example.org");
        String jean = store("Jean", "Martin", LocalDate.of(1990, 1, 1), null);
        store("Paul", "Durand", LocalDate.of(1985, 5, 5), null);
        // Row 2 lands on Jeanne by her address and renames her Jean, born —
        // by her fiche — the same day as the Jean already stored.
        String csv = """
                prenom;nom;date de naissance;email
                Jean;Martin;;jm@example.org
                Paul;Durand;;
                """;

        AnimateurCsvImportReport rapport = inEdition(() -> csvImport.preview(demande(csv)));

        assertThat(ligne(rapport, 2).animateurId()).isEqualTo(jeanne);
        assertThat(ligne(rapport, 2).doublonDe())
                .containsExactly(onFiche(AnimateurCsvImportReport.DuplicateKind.FICHE, jean));
        assertThat(ligne(rapport, 2).warnings())
                .singleElement()
                .asString()
                .startsWith("Probable doublon de la fiche " + jean)
                .contains(jeanne);
        assertThat(ligne(rapport, 3).doublonDe()).isEmpty();
        assertThat(rapport.doublonsProbables()).isEqualTo(1);
    }

    /**
     * Against the fiches the import keeps: a row whose write makes its fiche
     * the twin of another is flagged with that fiche's id, while two fiches
     * that already shared their key and that the file merely updates are the
     * edition's business, not this file's.
     */
    @Test
    void aRowAgainstAKeptFicheIsFlaggedOnlyWhenTheWriteMakesThePair() {
        String premier = store("Amélie", "Durand", LocalDate.of(1990, 3, 12), "a1@example.org");
        String second = store("Amélie", "Durand", LocalDate.of(1990, 3, 12), "a2@example.org");
        String autre = store("Amélie", "Durand", LocalDate.of(1991, 3, 12), "a3@example.org");
        String csv = """
                prenom;nom;date de naissance;email
                Amélie;Durand;12/03/1990;a1@example.org
                Amélie;Durand;12/03/1990;a3@example.org
                """;

        AnimateurCsvImportReport rapport = inEdition(() -> csvImport.preview(demande(csv)));

        assertThat(rapport.accepted()).isEqualTo(2);
        // Row 2 changes nothing of who its fiche is: the pair it forms with the
        // second fiche predates the file. Row 3 moves the third fiche onto both.
        assertThat(ligne(rapport, 2).doublonDe()).containsExactly(onRow(3));
        assertThat(ligne(rapport, 3).animateurId()).isEqualTo(autre);
        assertThat(ligne(rapport, 3).doublonDe())
                .containsExactly(onRow(2), onFiche(AnimateurCsvImportReport.DuplicateKind.FICHE, second));
        assertThat(rapport.rows())
                .flatExtracting(AnimateurCsvImportReport.ImportedRow::doublonDe)
                .doesNotContain(onFiche(AnimateurCsvImportReport.DuplicateKind.FICHE, premier));
    }

    /**
     * In a full replacement, the fiche the file does not name is deleted, so it
     * is no duplicate after the write — but a row taking its very identity
     * announces a deletion followed by a new description of the same person.
     */
    @Test
    void aFullReplacementFlagsTheDeletedFicheOfTheSameIdentity() {
        String jeanne = store("Jeanne", "Martin", LocalDate.of(1990, 1, 1), "jm@example.org");
        String jean = store("Jean", "Martin", LocalDate.of(1990, 1, 1), null);
        String csv = "prenom;nom;date de naissance;email\nJean;Martin;01/01/1990;jm@example.org\n";

        AnimateurCsvImportReport remplacement =
                inEdition(() -> csvImport.preview(new AnimateurCsvImportRequest("a.csv", csv, null, true, false)));
        AnimateurCsvImportReport ajout = inEdition(() -> csvImport.preview(demande(csv)));

        assertThat(remplacement.deleted()).isEqualTo(1);
        assertThat(ligne(remplacement, 2).animateurId()).isEqualTo(jeanne);
        assertThat(ligne(remplacement, 2).doublonDe())
                .containsExactly(onFiche(AnimateurCsvImportReport.DuplicateKind.REPLACED, jean));
        assertThat(ligne(remplacement, 2).warnings())
                .singleElement()
                .asString()
                .contains("que le remplacement complet supprime");
        assertThat(ligne(ajout, 2).doublonDe())
                .containsExactly(onFiche(AnimateurCsvImportReport.DuplicateKind.FICHE, jean));
    }

    /**
     * Three thousand rows of one person, each under its own address: every row
     * is flagged, but names only the first rows of its group and counts the
     * rest. Listing all the others made each row k − 1 references long — a
     * report of hundreds of megabytes for a file of three thousand lines.
     */
    @Test
    void aLargeGroupNamesTheFirstRowsAndCountsTheRest() throws Exception {
        int size = 3_000;
        StringBuilder csv = new StringBuilder("prenom;nom;date de naissance;email\n");
        for (int i = 0; i < size; i++) {
            csv.append("Amélie;Durand;12/03/1990;amelie").append(i).append("@example.org\n");
        }

        AnimateurCsvImportReport rapport = inEdition(() -> csvImport.preview(demande(csv.toString())));

        assertThat(rapport.accepted()).isEqualTo(size);
        assertThat(rapport.doublonsProbables()).isEqualTo(size);
        assertThat(ligne(rapport, 2).doublonDe()).containsExactly(onRow(3), onRow(4), onRow(5));
        assertThat(ligne(rapport, 2).warnings())
                .singleElement()
                .asString()
                .startsWith("Probable doublon des lignes 3, 4, 5 et de 2 996 autres : ");
        assertThat(ligne(rapport, size + 1).doublonDe()).containsExactly(onRow(2), onRow(3), onRow(4));
        Set<Integer> flagged = rapport.rows().stream()
                .filter(row -> !row.doublonDe().isEmpty())
                .map(AnimateurCsvImportReport.ImportedRow::line)
                .collect(java.util.stream.Collectors.toSet());
        for (AnimateurCsvImportReport.ImportedRow row : rapport.rows()) {
            assertThat(row.doublonDe()).hasSizeLessThanOrEqualTo(AnimateurCsvImportService.LISTED_DUPLICATES);
            // Every link leads to a row the screen shows as flagged.
            assertThat(row.doublonDe())
                    .allSatisfy(doublon -> assertThat(flagged).contains(doublon.line()));
        }
        // Bounded per row, whatever the size of its group: under a kilobyte on the wire.
        assertThat(objectMapper.writeValueAsString(rapport).length()).isLessThan(size * 1_000);
    }

    /** The kept fiches a row is flagged against are capped the same way, the rest counted in one sentence. */
    @Test
    void aRowAgainstManyKeptFichesNamesTheFirstOnesAndCountsTheRest() {
        for (int i = 0; i < 5; i++) {
            store("Amélie", "Durand", LocalDate.of(1990, 3, 12), "a" + i + "@example.org");
        }
        String bruno = store("Bruno", "Durand", LocalDate.of(1990, 3, 12), "b@example.org");
        // Row 2 lands on Bruno by his address and renames him Amélie, born the same day as the five.
        String csv = "prenom;nom;date de naissance;email\nAmélie;Durand;12/03/1990;b@example.org\n";

        AnimateurCsvImportReport rapport = inEdition(() -> csvImport.preview(demande(csv)));

        AnimateurCsvImportReport.ImportedRow row = ligne(rapport, 2);
        assertThat(row.animateurId()).isEqualTo(bruno);
        assertThat(row.doublonDe())
                .hasSize(AnimateurCsvImportService.LISTED_DUPLICATES)
                .allSatisfy(
                        doublon -> assertThat(doublon.kind()).isEqualTo(AnimateurCsvImportReport.DuplicateKind.FICHE));
        assertThat(row.warnings())
                .hasSize(AnimateurCsvImportService.LISTED_DUPLICATES + 1)
                .last()
                .asString()
                .isEqualTo("2 autres fiches, que l'import conserve, portent aussi les mêmes nom, prénom et date de "
                        + "naissance.");
    }

    /**
     * A known person under a new address: the address names nobody, so the
     * name decides, and the write would replace the fiche's address — where
     * its access codes and mails go — without a word. Flagged by the fiche's
     * id, neither address quoted; the row stays an update.
     */
    @Test
    void aRowMatchedByNameUnderANewAddressFlagsTheAddressItReplaces() {
        String jean = store("Jean", "Martin", LocalDate.of(1990, 1, 1), "old@example.org");
        String csv = """
                prenom;nom;date de naissance;email
                Jean;Martin;01/01/1990;new@example.org
                """;

        AnimateurCsvImportReport rapport = inEdition(() -> csvImport.preview(demande(csv)));

        AnimateurCsvImportReport.ImportedRow row = ligne(rapport, 2);
        assertThat(row.action()).isEqualTo(AnimateurCsvImportReport.ImportAction.UPDATED);
        assertThat(row.animateurId()).isEqualTo(jean);
        assertThat(row.doublonDe()).containsExactly(onFiche(AnimateurCsvImportReport.DuplicateKind.NEW_ADDRESS, jean));
        assertThat(row.warnings())
                .singleElement()
                .asString()
                .startsWith("L'adresse e-mail de la fiche " + jean + " sera remplacée")
                .doesNotContain("old@example.org");
        assertThat(rapport.doublonsProbables()).isEqualTo(1);

        AnimateurCsvImportReport ecrit = inEdition(() -> csvImport.apply(demande(csv)));

        assertThat(ligne(ecrit, 2).doublonDe()).isEqualTo(row.doublonDe());
        assertThat(ecrit.doublonsProbables()).isEqualTo(1);
    }

    /**
     * No address cell, or the fiche had none: nothing is replaced. And a
     * namesake under a new address keeps one reference to the fiche — the
     * screen draws one link per reference — with both sentences.
     */
    @Test
    void anAddressIsFlaggedOnlyWhenOneIsReplacedAndOnceWithANamesake() {
        String jean = store("Jean", "Martin", LocalDate.of(1990, 1, 1), "old@example.org");
        String paul = store("Paul", "Durand", LocalDate.of(1985, 5, 5), null);
        String marc = store("Marc", "Petit", LocalDate.of(1980, 2, 2), "marc@example.org");
        String csv = """
                prenom;nom;date de naissance;email
                Jean;Martin;01/01/1990;
                Paul;Durand;05/05/1985;paul@example.org
                Marc;Petit;12/09/2008;marc.petit@example.org
                """;

        AnimateurCsvImportReport rapport = inEdition(() -> csvImport.preview(demande(csv)));

        assertThat(ligne(rapport, 2).animateurId()).isEqualTo(jean);
        assertThat(ligne(rapport, 2).doublonDe()).isEmpty();
        assertThat(ligne(rapport, 3).animateurId()).isEqualTo(paul);
        assertThat(ligne(rapport, 3).doublonDe()).isEmpty();
        assertThat(ligne(rapport, 4).doublonDe())
                .containsExactly(onFiche(AnimateurCsvImportReport.DuplicateKind.NAMESAKE, marc));
        assertThat(ligne(rapport, 4).warnings())
                .hasSize(2)
                .anySatisfy(avis -> assertThat(avis).startsWith("Homonyme ?"))
                .anySatisfy(avis -> assertThat(avis).startsWith("L'adresse e-mail de la fiche " + marc));
        assertThat(rapport.doublonsProbables()).isEqualTo(1);
    }

    /** A rejected row is no identity: it carries its reasons, never a duplicate warning. */
    @Test
    void aRejectedRowIsNeverFlagged() {
        String csv = """
                prenom;nom;date de naissance;email;jours indisponibles
                Amélie;Durand;12/03/1990;a1@example.org;
                Amélie;Durand;12/03/1990;a2@example.org;01/01/2031
                """;

        AnimateurCsvImportReport rapport = inEdition(() -> csvImport.preview(demande(csv)));

        assertThat(rapport.rejected()).isEqualTo(1);
        assertThat(rapport.doublonsProbables()).isZero();
        assertThat(ligne(rapport, 2).doublonDe()).isEmpty();
    }

    /**
     * The replacement warning has to name what the cascade destroys. The
     * unitary delete shows those counters before confirming; an import deleting
     * a whole roster at once must not say less.
     */
    @Test
    void lAvertissementDuRemplacementNommeCeQueLaSuppressionEmporte() {
        inEdition(() -> referenceData.createAnimateur(
                new Animateur("A-PARTANT", "Zoé", "Absente", LocalDate.of(1990, 1, 1), false)));
        AnimateurCsvImportRequest remplacement = new AnimateurCsvImportRequest(
                "a.csv", "prenom;nom;date de naissance\nAmélie;Durand;12/03/1990\n", null, true, false);

        AnimateurCsvImportReport rapport = inEdition(() -> csvImport.preview(remplacement));

        assertThat(rapport.warnings())
                .anySatisfy(avis -> assertThat(avis)
                        .contains("Remplacement complet")
                        .contains("place(s) du planning persisté")
                        .contains("déclaration de disponibilités")
                        .contains("accusé de réception")
                        .contains("code d'accès à l'espace animateur"));
    }

    /* -------------------------------- Mapping ------------------------------- */

    /**
     * Clearing every field is a request, not an absence: re-proposing over it
     * would redraw the correspondence the operator had just erased, and there
     * would be no way to reach « rien n'est mappé » at all.
     */
    @Test
    void unMappingVideFourniNEstPasRempliParLaProposition() {
        AnimateurCsvImportRequest demande = new AnimateurCsvImportRequest(
                "a.csv",
                "prenom;nom;date de naissance\nAmélie;Durand;12/03/1990\n",
                AnimateurCsvMapping.empty(),
                false,
                false);

        AnimateurCsvImportReport rapport = inEdition(() -> csvImport.preview(demande));

        assertThat(rapport.mapping()).isEqualTo(AnimateurCsvMapping.empty());
        assertThat(rapport.accepted()).isZero();
        assertThat(rapport.warnings()).anySatisfy(avis -> assertThat(avis).contains("Aucune colonne"));
    }
}
