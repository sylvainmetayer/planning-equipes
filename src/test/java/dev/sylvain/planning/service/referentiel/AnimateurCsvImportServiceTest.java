package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
