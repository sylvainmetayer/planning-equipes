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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;

/**
 * Writes the three documents of a festival-sized édition to
 * {@code target/apercu/}, and checks the few things a text extraction cannot:
 * how many pages each layout takes.
 *
 * <p>Same intent as the extracted texts of {@link PlanningPdfContenuTest} —
 * kept where a human can open them — one step further: a layout regression (a
 * timeline drawn over its own labels, a day cut in half, a sheet spilling onto
 * a third page) is invisible to an assertion on words, and the files this test
 * leaves behind are what makes it visible. The fixture is deliberately larger
 * than the unit ones: sixteen days, six typologies, rest days and a
 * hundred-strong set-up.</p>
 */
class PdfApercuTest {

    private static final Map<String, String> LIBELLES = new LinkedHashMap<>();

    static {
        LIBELLES.put("ENFANCE", "Enfance");
        LIBELLES.put("LOGISTIQUE", "Logistique (montage / démontage)");
        LIBELLES.put("ACCUEIL", "Accueil");
        LIBELLES.put("ANIMATION", "Animation");
        LIBELLES.put("HOMME_JEU", "Homme jeu");
        LIBELLES.put("VIDEO", "Jeux vidéo");
    }

    private static final TypologieLibelles TYPOLOGIES = () -> LIBELLES;

    /** The édition the preview documents are about, named and dated like a real one. */
    private static final EtiquetteEdition EDITION =
            new EtiquetteEdition("festival-demo", LocalDate.parse("2026-09-14"), LocalDate.parse("2026-09-29"));

    private static final ExportProvenance PROVENANCE = new ExportProvenance() {
        @Override
        public Provenance courante() {
            return new Provenance(EDITION, Instant.parse("2026-09-17T19:34:00Z"), Nature.RESOLUTION);
        }

        @Override
        public Provenance publiee() {
            return new Provenance(EDITION, Instant.parse("2026-09-17T19:34:00Z"), Nature.PUBLICATION);
        }
    };

    @Test
    void theThreeDocumentsComposeAndKeepTheirPageCount() throws IOException {
        PlanningEvenement planning = festival();
        PlanningExportService service = new PlanningExportService(
                new ApplicationLinks(Optional.of("http://localhost:8080")),
                new AnimateurPlanningPdf(new PdfTheme(), TYPOLOGIES),
                new AnimateurFeuillePdf(new PdfTheme(), TYPOLOGIES),
                new GlobalPlanningPdf(new PdfTheme(), TYPOLOGIES),
                new PlanningIcs(),
                PROVENANCE,
                null);
        Path dossier = Path.of("target", "apercu");
        Files.createDirectories(dossier);
        byte[] livret = service.exportAnimateurPdf(planning, "A-1", FormatPlanning.LIVRET);
        byte[] feuille = service.exportAnimateurPdf(planning, "A-1", FormatPlanning.FEUILLE);
        byte[] global = service.exportGlobalPdf(planning);
        Files.write(dossier.resolve("livret.pdf"), livret);
        Files.write(dossier.resolve("feuille.pdf"), feuille);
        Files.write(dossier.resolve("global.pdf"), global);

        assertThat(pages(livret)).as("livret").isPositive();
        // The sheet is the one-glance layout: spilling onto a third page defeats it.
        assertThat(pages(feuille)).as("feuille").isBetween(1, 2);
        assertThat(pages(global)).as("global").isPositive();

        // The consignes come from CDI in production; handed over directly here
        // so the banner and the sun can be looked at.
        List<PosteAffectation> miens = planning.getPostes().stream()
                .filter(poste -> poste.getAnimateur() != null
                        && "A-1".equals(poste.getAnimateur().getId()))
                .sorted(PlanningExportService.byCreneauThenStand())
                .toList();
        Map<LocalDate, String> consignes = new LinkedHashMap<>();
        consignes.put(LocalDate.of(2026, 9, 22), "Horaires modifiés — arrêté préfectoral · canicule");
        consignes.put(LocalDate.of(2026, 9, 23), "Horaires modifiés — arrêté préfectoral · canicule");
        consignes.put(LocalDate.of(2026, 9, 24), "Horaires modifiés — arrêté préfectoral · canicule");
        Files.write(
                dossier.resolve("livret-canicule.pdf"),
                new AnimateurPlanningPdf(new PdfTheme(), TYPOLOGIES)
                        .render(
                                new DocumentAnimateur.Contenu(
                                        "Abby Desbarbieux",
                                        miens,
                                        PlanningExportService.teammatesByPoste(planning, "A-1"),
                                        PlanningExportService.daysOff(planning, "A-1"),
                                        List.of(),
                                        List.of(),
                                        consignes),
                                "http://localhost:8080/animateur/jeton-de-demo",
                                PROVENANCE.publiee()));
        Files.write(
                dossier.resolve("feuille-canicule.pdf"),
                new AnimateurFeuillePdf(new PdfTheme(), TYPOLOGIES)
                        .render(
                                new DocumentAnimateur.Contenu(
                                        "Abby Desbarbieux",
                                        miens,
                                        PlanningExportService.teammatesByPoste(planning, "A-1"),
                                        PlanningExportService.daysOff(planning, "A-1"),
                                        List.of(),
                                        List.of(),
                                        consignes),
                                "http://localhost:8080/animateur/jeton-de-demo",
                                PROVENANCE.publiee()));
    }

    private static int pages(byte[] pdf) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        try {
            return reader.getNumberOfPages();
        } finally {
            reader.close();
        }
    }

    /** Sixteen days, six typologies, rest days, a consigne, a hundred-strong set-up. */
    static PlanningEvenement festival() {
        LocalDate debut = LocalDate.of(2026, 9, 14);
        Emplacement enfants = new Emplacement("EMP-1", "Place du 11 Novembre – Village des Enfants", 46.65, -0.25);
        Emplacement jeux = new Emplacement("EMP-2", "Place du Drapeau – Village des Jeux", 46.651, -0.251);
        Emplacement parc = new Emplacement("EMP-3", "Parc de la Meilleraye", null, null);

        Stand construction = stand("S-1", "Construction", "ENFANCE", enfants);
        Stand crea = stand("S-2", "Créa", "ENFANCE", enfants);
        Stand motricite = stand("S-3", "Motricité/imitation/roulants", "ENFANCE", enfants);
        Stand accueil = stand("S-4", "Accueil", "ACCUEIL", jeux);
        Stand maif = stand("S-5", "MAIF", "ANIMATION", jeux);
        Stand fou = stand("S-6", "Fou", "HOMME_JEU", parc);
        Stand multimedia = stand("S-7", "Multimédia", "VIDEO", parc);
        Stand montage = stand("S-8", "Montage du festival", "LOGISTIQUE", null);
        Stand demontage = stand("S-9", "Démontage du festival", "LOGISTIQUE", null);

        List<Animateur> animateurs = new ArrayList<>();
        Animateur moi = new Animateur("A-1", "Abby", "Desbarbieux", LocalDate.of(1998, 3, 4), false);
        animateurs.add(moi);
        for (int i = 2; i <= 120; i++) {
            animateurs.add(new Animateur("A-" + i, "Prénom" + i, "Nom" + i, LocalDate.of(1999, 1, 1), false));
        }

        List<PosteAffectation> postes = new ArrayList<>();
        long creneauId = 1;

        // J1: the set-up, a hundred people at once
        Creneau montageCreneau = new Creneau(creneauId++, 1, debut, LocalTime.of(13, 0), LocalTime.of(16, 0));
        for (int i = 1; i <= 105; i++) {
            postes.add(poste("p-montage-" + i, montage, montageCreneau, animateurs.get(i - 1)));
        }

        // J3 to J14: shifts, two typologies a day, with rest days
        Stand[] rotation = {construction, crea, motricite, accueil, maif, fou, multimedia};
        for (int jour = 2; jour <= 15; jour++) {
            LocalDate date = debut.plusDays(jour - 1L);
            Stand matin = rotation[jour % rotation.length];
            Stand aprem = rotation[(jour + 3) % rotation.length];
            Creneau creneauMatin = new Creneau(creneauId++, jour, date, LocalTime.of(10, 0), LocalTime.of(12, 0));
            Creneau creneauAprem = new Creneau(creneauId++, jour, date, LocalTime.of(14, 0), LocalTime.of(20, 0));
            boolean repos = jour == 2 || jour == 8 || jour == 12 || jour == 15;
            postes.add(poste("p-m2-" + jour, matin, creneauMatin, animateurs.get(jour)));
            postes.add(poste("p-m3-" + jour, matin, creneauMatin, animateurs.get(jour + 20)));
            postes.add(poste("p-a2-" + jour, aprem, creneauAprem, animateurs.get(jour + 1)));
            if (repos) {
                continue;
            }
            postes.add(poste("p-m-" + jour, matin, creneauMatin, moi));
            postes.add(poste("p-a-" + jour, aprem, creneauAprem, moi));
        }

        // J16: the take-down
        Creneau demontageCreneau =
                new Creneau(creneauId, 16, debut.plusDays(15), LocalTime.of(9, 0), LocalTime.of(12, 0));
        for (int i = 1; i <= 95; i++) {
            postes.add(poste("p-demontage-" + i, demontage, demontageCreneau, animateurs.get(i - 1)));
        }

        return new PlanningEvenement(debut, animateurs, postes);
    }

    private static Stand stand(String id, String nom, String typologie, Emplacement emplacement) {
        Stand stand = new Stand(id, nom, Set.of(typologie), 1, 8, false);
        stand.setEmplacement(emplacement);
        return stand;
    }

    private static PosteAffectation poste(String id, Stand stand, Creneau creneau, Animateur animateur) {
        PosteAffectation poste = new PosteAffectation(id, stand, creneau);
        poste.setAnimateur(animateur);
        return poste;
    }
}
