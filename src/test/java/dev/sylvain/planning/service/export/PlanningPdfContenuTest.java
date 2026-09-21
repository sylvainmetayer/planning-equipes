package dev.sylvain.planning.service.export;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.edition.EtiquetteEdition;
import dev.sylvain.planning.service.espace.ApplicationLinks;
import dev.sylvain.planning.service.referentiel.TypologieLibelles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

/**
 * What the PDFs <b>say</b>, and not merely that they start with {@code %PDF}.
 *
 * <p>The exports used to be covered by "the file is not empty and its header is
 * correct": a card gone missing, a name displayed blank, a day off that no
 * longer prints all passed green. This test reads the rendered text page by
 * page, which is also the net under the splits of
 * {@code PlanningExportService} — the rendering must survive a move of code
 * without a word changing.</p>
 *
 * <p>The extracted text is written to {@code target/sample-exports/}: two runs
 * framing a refactoring can then be compared with {@code diff}.</p>
 */
class PlanningPdfContenuTest {

    /** The édition the documents are about, named and dated as the espace names it (issue #608). */
    private static final EtiquetteEdition EDITION =
            new EtiquetteEdition("Édition de test", LocalDate.parse("2026-07-10"), LocalDate.parse("2026-07-12"));

    /**
     * A fixed provenance: these tests read the documents, not the database.
     * The two plans are dated apart on purpose — a document must carry the date
     * of the plan it renders, not of the other one (issue #245).
     */
    private static final ExportProvenance PROVENANCE = new ExportProvenance() {
        @Override
        public Provenance courante() {
            return new Provenance(EDITION, Instant.parse("2026-07-01T08:30:00Z"), Nature.RESOLUTION);
        }

        @Override
        public Provenance publiee() {
            return new Provenance(EDITION, Instant.parse("2026-06-28T17:00:00Z"), Nature.PUBLICATION);
        }
    };

    /** The typologie vocabulary the documents translate stand ids against. */
    private static final TypologieLibelles TYPOLOGIES = () -> Map.of(
            "STRATEGIE", "Jeux de stratégie",
            "AMBIANCE", "Jeux d'ambiance");

    private final PlanningExportService service = new PlanningExportService(
            new ApplicationLinks(Optional.empty()),
            new AnimateurPlanningPdf(new PdfTheme(), TYPOLOGIES),
            new AnimateurFeuillePdf(new PdfTheme(), TYPOLOGIES),
            new GlobalPlanningPdf(new PdfTheme(), TYPOLOGIES),
            new PlanningIcs(),
            PROVENANCE);

    @Test
    void lePdfIndividuelNommeLAnimateurSesStandsEtSesRepos() throws IOException {
        PlanningEvenement planning = planning();

        String text = textOf(service.exportAnimateurPdf(planning, "A-ADA"));

        assertThat(text)
                .contains("PLANNING")
                .contains("Ada Lovelace")
                .contains("Stratèges Associés")
                .contains("Éditeur Vedette")
                .contains("Kiosque Central")
                .contains("09:00")
                .contains("Repos");
        assertThat(text).doesNotContain("null");
        write("contenu-animateur.txt", text);
    }

    /**
     * A day the band closed for the whole person prints « Repos » — and the
     * motif under its date, or the document reads as if the planner had
     * simply left them out. The note reaches the rest card, not only the
     * assignment card.
     */
    @Test
    void aRestDayUnderConsignePrintsTheMotifUnderItsDate() throws IOException {
        PlanningEvenement planning = planning();
        List<PosteAffectation> postesAda = planning.getPostes().stream()
                .filter(poste -> poste.getAnimateur() != null
                        && "A-ADA".equals(poste.getAnimateur().getId()))
                .toList();
        List<PlanningExportService.JourRepos> repos = PlanningExportService.daysOff(planning, "A-ADA");
        assertThat(repos).extracting(PlanningExportService.JourRepos::date).containsExactly(LocalDate.of(2026, 8, 15));

        byte[] pdf = new AnimateurPlanningPdf(new PdfTheme(), TYPOLOGIES)
                .render(
                        "Ada Lovelace",
                        postesAda,
                        Map.of(),
                        repos,
                        List.of(),
                        List.of(),
                        null,
                        PROVENANCE.publiee(),
                        Map.of(LocalDate.of(2026, 8, 15), "Horaires modifiés — Canicule"));

        String text = textOf(pdf);
        assertThat(text).contains("Repos");
        assertThat(text.lines().map(String::strip).toList())
                .containsSubsequence("Samedi 15 août", "Horaires modifiés — Canicule");
    }

    @Test
    void lePdfIndividuelAnnonceLaPauseSousLaVacationQuiLaDoit() throws IOException {
        // 13:00-20:00 on one stand with a colleague: the break is owed at 19:00
        // and the relay is on the stand. The same day alone on the stand says so.
        Stand stand = new Stand("STAND-P", "Stand des Pauses", Set.of("STRATEGIE"), 2, 2, false);
        Creneau releve = new Creneau(11L, 1, LocalDate.of(2026, 8, 14), LocalTime.of(13, 0), LocalTime.of(14, 0));
        Creneau aprem = new Creneau(12L, 1, LocalDate.of(2026, 8, 14), LocalTime.of(14, 0), LocalTime.of(20, 0));
        Animateur ada = new Animateur("A-ADA", "Ada", "Lovelace", LocalDate.of(1990, 1, 1), false);
        Animateur alan = new Animateur("A-ALAN", "Alan", "Turing", LocalDate.of(1992, 2, 2), false);
        List<PosteAffectation> postes = new ArrayList<>(List.of(
                poste("p1", stand, releve, ada), poste("p2", stand, aprem, ada), poste("p3", stand, aprem, alan)));
        PlanningEvenement planning =
                new PlanningEvenement(LocalDate.of(2026, 8, 14), new ArrayList<>(List.of(ada, alan)), postes);

        String text = textOf(service.exportAnimateurPdf(planning, "A-ADA"));
        assertThat(text).contains("Pause de 19:00 à 19:30 (30 min)").contains("en relais avec l'équipe du stand");
        // Alan's six hours end at 20:00 exactly: nothing owed, nothing printed.
        assertThat(textOf(service.exportAnimateurPdf(planning, "A-ALAN"))).doesNotContain("Pause de");

        postes.remove(2);
        String seule = textOf(service.exportAnimateurPdf(planning, "A-ADA"));
        assertThat(seule).contains("Pause de 19:00 à 19:30 (30 min)").contains("personne d'autre sur le stand");
    }

    /**
     * « Où dois-je aller » is answered by the overview and not by the
     * chronological pages: the stands are grouped by place, each with its
     * typologie and how much it weighs — « 1 × · 4 h ».
     */
    @Test
    void laVueDEnsembleGroupeLesStandsParLieuAvecLeursTypologies() throws IOException {
        String text = textOf(service.exportAnimateurPdf(planning(), "A-ADA"));

        assertThat(text.lines().map(String::strip).toList())
                .containsSubsequence(
                        "Vos 2 stands, par lieu",
                        "Kiosque Central",
                        "Éditeur VedetteJeux d'ambiance1 × · 4 h",
                        "Lieu non précisé",
                        "Stratèges AssociésJeux de stratégie1 × · 4 h");
    }

    /**
     * The booklet is three parts in one order — overview, days, teams and
     * places — and every page says which one it is out of how many. A reader
     * holding page four of five knows something is missing; one holding an
     * unnumbered sheet does not.
     */
    @Test
    void leLivretEnchaineVueDEnsembleJourneesPuisEquipes() throws IOException {
        List<String> lignes = textOf(service.exportAnimateurPdf(planning(), "A-ADA"))
                .lines()
                .map(String::strip)
                .toList();

        assertThat(lignes)
                .containsSubsequence(
                        "PLANNING INDIVIDUEL",
                        "Vos 2 jours en un coup d'œil",
                        "1 / 3",
                        "Vos journées",
                        "2 / 3",
                        "Avec qui, et où",
                        "3 / 3");
        // The four figures of the overview, hours first: what the animateur
        // checks against what they agreed to.
        assertThat(lignes).containsSubsequence("8", "heures travaillées", "1", "créneaux", "2", "stands");
        assertThat(lignes).contains("Du vendredi 14 au samedi 15 août 2026 · 2 jours, dont 1 de repos");
    }

    /**
     * The team-mates are gathered at the end, most frequent first: met twice,
     * Alan comes before Grace met once — which no chronological page can say
     * however many times it prints the names.
     */
    @Test
    void lesCoequipiersSontRassemblesEtTriesParFrequence() throws IOException {
        List<String> lignes = textOf(service.exportAnimateurPdf(planningFestival(), "A-ADA"))
                .lines()
                .map(String::strip)
                .toList();

        assertThat(lignes)
                .containsSubsequence("Vos coéquipiers sur les stands", "2×", "Alan Turing", "1×", "Grace Hopper");
    }

    /**
     * The set-up gathers a hundred people: the document says how many and not
     * who. Printing the nominal list, as the former layout did, is a page
     * nobody reads.
     */
    @Test
    void unPosteTresPeupleAfficheUnEffectifEtPasDeNoms() throws IOException {
        String text = textOf(service.exportAnimateurPdf(planningFestival(), "A-ADA"));

        assertThat(text)
                .contains("Avec 9 personnes")
                .contains("Les vacations en nombre")
                .contains("Montage");
        assertThat(text).doesNotContain("Renfort 5");
    }

    /** A worked day a consigne governs carries the sentence under its date, banner and all. */
    @Test
    void uneJourneeTravailleeSousConsignePorteSonBandeau() throws IOException {
        PlanningEvenement planning = planning();
        List<PosteAffectation> postesAda = planning.getPostes().stream()
                .filter(poste -> poste.getAnimateur() != null
                        && "A-ADA".equals(poste.getAnimateur().getId()))
                .toList();

        byte[] pdf = new AnimateurPlanningPdf(new PdfTheme(), TYPOLOGIES)
                .render(
                        "Ada Lovelace",
                        postesAda,
                        Map.of(),
                        PlanningExportService.daysOff(planning, "A-ADA"),
                        List.of(),
                        List.of(),
                        null,
                        PROVENANCE.publiee(),
                        Map.of(LocalDate.of(2026, 8, 14), "Horaires modifiés — arrêté préfectoral · canicule"));

        assertThat(textOf(pdf).lines().map(String::strip).toList())
                .containsSubsequence("Vendredi 14 août", "Horaires modifiés — arrêté préfectoral · canicule", "09:00");
    }

    /**
     * The espace band closes the overview when the overview leaves room for
     * it. The link stays written under the QR code: a photocopy too pale to
     * scan, and a reader who wants to see where the code leads, both need it.
     */
    @Test
    void leCartoucheDeLEspaceFermeLaPremierePageQuandElleALaPlace() throws IOException {
        PlanningEvenement planning = planning();
        byte[] pdf = new AnimateurPlanningPdf(new PdfTheme(), TYPOLOGIES)
                .render(
                        "Ada Lovelace",
                        planning.getPostes().stream()
                                .filter(poste -> poste.getAnimateur() != null
                                        && "A-ADA".equals(poste.getAnimateur().getId()))
                                .toList(),
                        Map.of(),
                        PlanningExportService.daysOff(planning, "A-ADA"),
                        List.of(),
                        List.of(),
                        "https://planning.example.org/animateur/jeton-1",
                        PROVENANCE.publiee(),
                        Map.of());

        assertThat(pageTextOf(pdf, 1))
                .contains("VOTRE ESPACE EN LIGNE")
                .contains("https://planning.example.org/animateur/jeton-1");
    }

    /**
     * The folded sheet says the same things as the booklet on two sides: a
     * calendar week by week, then the teams and the places.
     */
    @Test
    void laFeuilleRectoVersoDitLaMemeChoseSurDeuxPages() throws IOException {
        byte[] pdf = service.exportAnimateurPdf(planningFestival(), "A-ADA", FormatPlanning.FEUILLE);

        String text = textOf(pdf);
        assertThat(pagesOf(pdf)).isEqualTo(2);
        assertThat(text)
                .contains("Ada Lovelace")
                .contains("lundi")
                .contains("dimanche")
                .contains("Repères")
                .contains("Avec qui, et où")
                .contains("Vos coéquipiers sur les stands")
                .contains("Avec 9 personnes");
        assertThat(text).doesNotContain("null");
    }

    /** The team-mates on the same row are named, the animateur themselves is not. */
    @Test
    void lePdfIndividuelNommeLesCoequipiersDeLaMemeLigne() throws IOException {
        PlanningEvenement planning = planning();

        String text = textOf(service.exportAnimateurPdf(planning, "A-ADA"));

        assertThat(text).contains("Alan Turing");
    }

    /**
     * The folded sheet says everything the booklet says, however loaded the
     * day: its calendar boxes have a minimum height, never a ceiling. Fixed,
     * they cut what did not fit — a third shift lost its team line, and the
     * two layouts told two different stories.
     */
    @Test
    void uneJourneeChargeeNestPasCoupeeDansLaFeuille() throws IOException {
        PlanningEvenement planning = planningJourneesChargees();

        byte[] feuille = service.exportAnimateurPdf(planning, "A-ADA", FormatPlanning.FEUILLE);
        String livret = textOf(service.exportAnimateurPdf(planning, "A-ADA", FormatPlanning.LIVRET));

        // The CALENDAR side, and not the whole file: the back page names every
        // team-mate anyway, so a cut on the front went unseen by a search over
        // the document. « Ada Byron » only ever works the evening shift — the
        // third line of the box, the one that fell off its bottom.
        String calendrier = pageTextOf(feuille, 1);
        for (String coequipier : List.of("Alan Turing", "Grace Hopper", "Ada Byron")) {
            assertThat(calendrier).as("le calendrier nomme " + coequipier).contains(coequipier);
            assertThat(livret).as("le livret nomme " + coequipier).contains(coequipier);
        }
    }

    /**
     * Two créneaux of one day can carry the same hours on the same stand —
     * nothing in the referential forbids it. The grid cell of that window then
     * holds both, names and unfilled seats alike: the line it dropped took its
     * animateurs out of the document.
     */
    @Test
    void deuxCreneauxDeMemesHorairesTiennentDansLaMemeCaseDuGlobal() throws IOException {
        Stand stand = new Stand("STAND-1", "Stratèges Associés", Set.of("STRATEGIE"), 1, 2, false);
        Creneau premier = new Creneau(1L, 1, LocalDate.of(2026, 8, 14), LocalTime.of(9, 0), LocalTime.of(13, 0));
        Creneau second = new Creneau(2L, 1, LocalDate.of(2026, 8, 14), LocalTime.of(9, 0), LocalTime.of(13, 0));
        Animateur ada = new Animateur("A-ADA", "Ada", "Lovelace", LocalDate.of(1990, 1, 1), false);
        Animateur alan = new Animateur("A-ALAN", "Alan", "Turing", LocalDate.of(1992, 2, 2), false);
        PosteAffectation vide = new PosteAffectation("p3", stand, second);
        PlanningEvenement planning = new PlanningEvenement(
                LocalDate.of(2026, 8, 14),
                new ArrayList<>(List.of(ada, alan)),
                new ArrayList<>(List.of(poste("p1", stand, premier, ada), poste("p2", stand, second, alan), vide)));

        byte[] global = service.exportGlobalPdf(planning);

        // The « Par journée » page, and not the whole file: « Par stand » and
        // « Animateurs de A à Z » name everybody anyway, so a line dropped from
        // the grid went unseen by a search over the document.
        String journee = pageContaining(global, "Jour 1 —");
        assertThat(journee).contains("Ada Lovelace").contains("Alan Turing").contains("non pourvu");
    }

    /**
     * The organiser's document is a summary and four sections: the overview
     * grid, then the same assignments by day, by stand and by person. The
     * seats nobody holds are written in plain sight.
     */
    @Test
    void lePdfGlobalEnchaineSommaireEnsembleJourneeStandEtAnimateurs() throws IOException {
        PlanningEvenement planning = planning();

        String text = textOf(service.exportGlobalPdf(planning));
        write("contenu-global.txt", text);

        assertThat(text.lines().map(String::strip).toList())
                .containsSubsequence(
                        "PLANNING GLOBAL · TOUTES LES AFFECTATIONS",
                        "1  Vue d'ensemble",
                        "2  Par journée",
                        "3  Par stand",
                        "4  Animateurs de A à Z");
        assertThat(text)
                .contains("Qui tient quel stand, chaque jour")
                .contains("Animateurs présents")
                .contains("Jour 1 — Vendredi 14 août")
                .contains("Stratèges Associés")
                .contains("Ada Lovelace")
                .contains("Alan Turing")
                .contains("Aucun animateur affecté");
        assertThat(text).doesNotContain("null");
    }

    /** An animateur with neither prenom nor nom shows by id, never as "null null" nor blank. */
    @Test
    void unAnimateurSansNomSAfficheParSonId() throws IOException {
        PlanningEvenement planning = planning();
        Animateur anonyme = new Animateur("A-VIDE", null, null, LocalDate.of(1990, 1, 1), false);
        planning.getAnimateurs().add(anonyme);
        PosteAffectation poste = new PosteAffectation(
                "p-vide",
                planning.getPostes().get(0).getStand(),
                planning.getPostes().get(0).getCreneau());
        poste.setAnimateur(anonyme);
        planning.getPostes().add(poste);

        assertThat(textOf(service.exportGlobalPdf(planning))).contains("A-VIDE");
    }

    /**
     * The footer is what tells two downloads of the same planning apart. The
     * generation date only dates the click; a printed copy is stale or current
     * according to the solve date, so both PDFs must carry it.
     */
    @Test
    void lesDeuxPdfDatentLEditionEtSaResolution() throws IOException {
        PlanningEvenement planning = planning();

        for (byte[] pdf : List.of(service.exportAnimateurPdf(planning, "A-ADA"), service.exportGlobalPdf(planning))) {
            assertThat(textOf(pdf))
                    .contains("généré le")
                    .contains("à partir des données de l'édition « Édition de test »")
                    .contains("résolue le 01/07/2026");
        }
    }

    /**
     * The name of the édition, in the header rather than in the small print of
     * the footer (issue #608): somebody holding last year's PDF and this year's
     * tells them apart at a glance, not by reading the bottom of the page.
     */
    @Test
    void lePdfIndividuelNommeSonEditionSousLeNomDeLAnimateur() throws IOException {
        // Its name alone, because the line under it already spells the span
        // out — the full label « nom — du … au … » would date the cover twice.
        // Both layouts say it: an animateur holds one or the other, never both.
        for (FormatPlanning format : FormatPlanning.values()) {
            String couverture = pageTextOf(service.exportAnimateurPdf(planning(), "A-ADA", format), 1);
            assertThat(couverture)
                    .as("la couverture " + format + " nomme l'édition")
                    .contains("Édition de test")
                    .contains("Du vendredi 14 au samedi 15 août 2026");
        }
    }

    /** An édition never solved says so rather than leaving the reader to guess. */
    @Test
    void uneEditionJamaisResolueLeDitDansLePied() throws IOException {
        assertThat(textOf(withoutDate().exportAnimateurPdf(planning(), "A-ADA")))
                .contains("« Édition de test », jamais résolue");
    }

    /** Same for an édition whose plan was never communicated. */
    @Test
    void uneEditionJamaisPublieeLeDitDansLePied() throws IOException {
        assertThat(textOf(withoutDate().exportAnimateurPdfPublie(planning(), "A-ADA")))
                .contains("« Édition de test », jamais publiée");
    }

    /**
     * The bug this split exists to remove: a solve run after the publication
     * moved the footer's date without moving a single line of the document.
     * An animateur's PDF renders the published plan, so it is dated by its
     * publication — the later solve must not show through.
     */
    @Test
    void lePdfDUnAnimateurEstDateParSaPublicationPasParUnSolvePosterieur() throws IOException {
        assertThat(textOf(service.exportAnimateurPdfPublie(planning(), "A-ADA")))
                .contains("publiée le 28/06/2026")
                .doesNotContain("résolue le");
    }

    /** The administration's own export still renders — and dates — the working plan. */
    @Test
    void lExportDeLAdministrationResteDateParLaResolution() throws IOException {
        assertThat(textOf(service.exportAnimateurPdf(planning(), "A-ADA")))
                .contains("résolue le 01/07/2026")
                .doesNotContain("publiée le");
    }

    /** A provenance carrying no date at all, whichever plan is asked for. */
    private static PlanningExportService withoutDate() {
        return new PlanningExportService(
                new ApplicationLinks(Optional.empty()),
                new AnimateurPlanningPdf(new PdfTheme(), TYPOLOGIES),
                new AnimateurFeuillePdf(new PdfTheme(), TYPOLOGIES),
                new GlobalPlanningPdf(new PdfTheme(), TYPOLOGIES),
                new PlanningIcs(),
                new ExportProvenance() {
                    @Override
                    public Provenance courante() {
                        return new Provenance(EDITION, null, Nature.RESOLUTION);
                    }

                    @Override
                    public Provenance publiee() {
                        return new Provenance(EDITION, null, Nature.PUBLICATION);
                    }
                });
    }

    private static String pageTextOf(byte[] pdf, int page) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        try {
            return new PdfTextExtractor(reader).getTextFromPage(page);
        } finally {
            reader.close();
        }
    }

    /** The text of the single page carrying that marker — a section is a page here. */
    private static String pageContaining(byte[] pdf, String marker) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        try {
            PdfTextExtractor extracteur = new PdfTextExtractor(reader);
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                String text = extracteur.getTextFromPage(page);
                if (text.contains(marker)) {
                    return text;
                }
            }
            throw new AssertionError("aucune page ne porte « " + marker + " »");
        } finally {
            reader.close();
        }
    }

    private static int pagesOf(byte[] pdf) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        try {
            return reader.getNumberOfPages();
        } finally {
            reader.close();
        }
    }

    private static String textOf(byte[] pdf) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        try {
            PdfTextExtractor extracteur = new PdfTextExtractor(reader);
            StringBuilder text = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                text.append(extracteur.getTextFromPage(page)).append('\n');
            }
            return text.toString();
        } finally {
            reader.close();
        }
    }

    private static void write(String nom, String text) throws IOException {
        Path dossier = Path.of("target", "sample-exports");
        Files.createDirectories(dossier);
        Files.writeString(dossier.resolve(nom), text, StandardCharsets.UTF_8);
    }

    /** Two animateurs on one stand, a geocoded stand, and a day with no seat for Ada. */
    private static PlanningEvenement planning() {
        Stand strategie = new Stand("STAND-1", "Stratèges Associés", Set.of("STRATEGIE"), 1, 2, false);
        Stand vedette = new Stand("STAND-2", "Éditeur Vedette", Set.of("AMBIANCE"), 1, 2, false);
        vedette.setEmplacement(new Emplacement("EMP-1", "Kiosque Central", 48.8566, 2.3522));

        Creneau matinJ1 = new Creneau(1L, 1, LocalDate.of(2026, 8, 14), LocalTime.of(9, 0), LocalTime.of(13, 0));
        Creneau matinJ2 = new Creneau(2L, 2, LocalDate.of(2026, 8, 15), LocalTime.of(9, 0), LocalTime.of(13, 0));

        Animateur ada = new Animateur("A-ADA", "Ada", "Lovelace", LocalDate.of(1990, 1, 1), false);
        Animateur alan = new Animateur("A-ALAN", "Alan", "Turing", LocalDate.of(1992, 2, 2), false);

        List<PosteAffectation> postes = new ArrayList<>();
        postes.add(poste("p1", strategie, matinJ1, ada));
        postes.add(poste("p2", strategie, matinJ1, alan));
        postes.add(poste("p3", vedette, matinJ1, ada));
        postes.add(poste("p4", strategie, matinJ2, alan));
        postes.add(poste("p5", vedette, matinJ2, null));

        return new PlanningEvenement(
                LocalDate.of(2026, 8, 14), new ArrayList<>(List.of(ada, alan)), new ArrayList<>(postes));
    }

    /**
     * A wider planning: the same stand met twice with the same person, a
     * hundred-strong set-up, and a day off — what the gathered team-mates and
     * the « effectif rather than names » rule need to be read on.
     */
    /**
     * Three shifts a day for four weeks — the shape that used to be cut: the
     * more weeks the calendar holds, the shorter its rows, and a fixed height
     * dropped whatever came last in the box.
     */
    private static PlanningEvenement planningJourneesChargees() {
        Stand matinal = new Stand("STAND-M", "Stand du matin", Set.of("STRATEGIE"), 1, 4, false);
        Stand midi = new Stand("STAND-D", "Stand de midi", Set.of("AMBIANCE"), 1, 4, false);
        Stand soir = new Stand("STAND-S", "Stand du soir", Set.of("STRATEGIE"), 1, 4, false);

        Animateur ada = new Animateur("A-ADA", "Ada", "Lovelace", LocalDate.of(1990, 1, 1), false);
        Animateur alan = new Animateur("A-ALAN", "Alan", "Turing", LocalDate.of(1992, 2, 2), false);
        Animateur grace = new Animateur("A-GRACE", "Grace", "Hopper", LocalDate.of(1991, 3, 3), false);
        Animateur byron = new Animateur("A-BYRON", "Ada", "Byron", LocalDate.of(1993, 4, 4), false);

        List<PosteAffectation> postes = new ArrayList<>();
        long creneauId = 1;
        for (int jour = 1; jour <= 28; jour++) {
            LocalDate date = LocalDate.of(2026, 8, 3).plusDays(jour - 1L);
            Creneau matin = new Creneau(creneauId++, jour, date, LocalTime.of(9, 0), LocalTime.of(12, 0));
            Creneau apresMidi = new Creneau(creneauId++, jour, date, LocalTime.of(13, 0), LocalTime.of(16, 0));
            Creneau soiree = new Creneau(creneauId++, jour, date, LocalTime.of(17, 0), LocalTime.of(20, 0));
            postes.add(poste("m-" + jour, matinal, matin, ada));
            postes.add(poste("m2-" + jour, matinal, matin, alan));
            postes.add(poste("d-" + jour, midi, apresMidi, ada));
            postes.add(poste("d2-" + jour, midi, apresMidi, grace));
            postes.add(poste("s-" + jour, soir, soiree, ada));
            postes.add(poste("s2-" + jour, soir, soiree, byron));
        }
        return new PlanningEvenement(
                LocalDate.of(2026, 8, 3), new ArrayList<>(List.of(ada, alan, grace, byron)), postes);
    }

    private static PlanningEvenement planningFestival() {
        Stand strategie = new Stand("STAND-1", "Stratèges Associés", Set.of("STRATEGIE"), 1, 4, false);
        Stand montage = new Stand("STAND-M", "Montage du festival", Set.of("AMBIANCE"), 1, 20, false);

        Animateur ada = new Animateur("A-ADA", "Ada", "Lovelace", LocalDate.of(1990, 1, 1), false);
        Animateur alan = new Animateur("A-ALAN", "Alan", "Turing", LocalDate.of(1992, 2, 2), false);
        Animateur grace = new Animateur("A-GRACE", "Grace", "Hopper", LocalDate.of(1991, 3, 3), false);
        List<Animateur> animateurs = new ArrayList<>(List.of(ada, alan, grace));

        Creneau montageCreneau =
                new Creneau(1L, 1, LocalDate.of(2026, 8, 14), LocalTime.of(13, 0), LocalTime.of(16, 0));
        Creneau j2 = new Creneau(2L, 2, LocalDate.of(2026, 8, 15), LocalTime.of(9, 0), LocalTime.of(13, 0));
        Creneau j3 = new Creneau(3L, 3, LocalDate.of(2026, 8, 16), LocalTime.of(9, 0), LocalTime.of(13, 0));
        Creneau j4 = new Creneau(4L, 4, LocalDate.of(2026, 8, 17), LocalTime.of(9, 0), LocalTime.of(13, 0));

        List<PosteAffectation> postes = new ArrayList<>();
        postes.add(poste("m-ada", montage, montageCreneau, ada));
        for (int i = 1; i <= 9; i++) {
            Animateur renfort = new Animateur("A-R" + i, "Renfort", String.valueOf(i), LocalDate.of(1990, 1, 1), false);
            animateurs.add(renfort);
            postes.add(poste("m-" + i, montage, montageCreneau, renfort));
        }
        postes.add(poste("p1", strategie, j2, ada));
        postes.add(poste("p2", strategie, j2, alan));
        postes.add(poste("p3", strategie, j3, ada));
        postes.add(poste("p4", strategie, j3, alan));
        postes.add(poste("p5", strategie, j4, ada));
        postes.add(poste("p6", strategie, j4, grace));
        // A day of the event Ada holds no seat on: her rest day.
        postes.add(poste(
                "p7",
                strategie,
                new Creneau(5L, 5, LocalDate.of(2026, 8, 18), LocalTime.of(9, 0), LocalTime.of(13, 0)),
                alan));

        return new PlanningEvenement(LocalDate.of(2026, 8, 14), animateurs, postes);
    }

    private static PosteAffectation poste(String id, Stand stand, Creneau creneau, Animateur animateur) {
        PosteAffectation poste = new PosteAffectation(id, stand, creneau);
        poste.setAnimateur(animateur);
        return poste;
    }
}
