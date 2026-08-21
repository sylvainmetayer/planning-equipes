package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;

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

    private final PlanningExportService service = new PlanningExportService(new LiensApplication(Optional.empty()),
            new PlanningPdfAnimateur(), new PlanningPdfGlobal(), new PlanningIcs());

    @Test
    void lePdfIndividuelNommeLAnimateurSesStandsEtSesRepos() throws IOException {
        PlanningFestival planning = planning();

        String texte = texteDe(service.exportAnimateurPdf(planning, "A-ADA"));

        assertThat(texte)
                .contains("PLANNING")
                .contains("Ada Lovelace")
                .contains("Stratèges Associés")
                .contains("Éditeur Vedette")
                .contains("Kiosque Central")
                .contains("09:00")
                .contains("Repos");
        assertThat(texte).doesNotContain("null");
        ecrire("contenu-animateur.txt", texte);
    }

    /** The team-mates on the same row are named, the animateur themselves is not. */
    @Test
    void lePdfIndividuelNommeLesCoequipiersDeLaMemeLigne() throws IOException {
        PlanningFestival planning = planning();

        String texte = texteDe(service.exportAnimateurPdf(planning, "A-ADA"));

        assertThat(texte).contains("Alan Turing");
    }

    /**
     * The global PDF presents the same assignments twice — by day, then by
     * stand — and writes in plain sight the seats nobody holds.
     */
    @Test
    void lePdfGlobalPresenteLesAffectationsParJourneePuisParStand() throws IOException {
        PlanningFestival planning = planning();

        String texte = texteDe(service.exportGlobalPdf(planning));

        assertThat(texte)
                .contains("PLANNING GLOBAL")
                .contains("Toutes les affectations")
                .contains("Vendredi 14 août")
                .contains("Stratèges Associés")
                .contains("Ada Lovelace")
                .contains("Alan Turing");
        assertThat(texte).doesNotContain("null");
        ecrire("contenu-global.txt", texte);
    }

    /** An animateur with neither prenom nor nom shows by id, never as "null null" nor blank. */
    @Test
    void unAnimateurSansNomSAfficheParSonId() throws IOException {
        PlanningFestival planning = planning();
        Animateur anonyme = new Animateur("A-VIDE", null, null, LocalDate.of(1990, 1, 1), false);
        planning.getAnimateurs().add(anonyme);
        PosteAffectation poste = new PosteAffectation("p-vide",
                planning.getPostes().get(0).getStand(), planning.getPostes().get(0).getCreneau());
        poste.setAnimateur(anonyme);
        planning.getPostes().add(poste);

        assertThat(texteDe(service.exportGlobalPdf(planning))).contains("A-VIDE");
    }

    private static String texteDe(byte[] pdf) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        try {
            PdfTextExtractor extracteur = new PdfTextExtractor(reader);
            StringBuilder texte = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                texte.append(extracteur.getTextFromPage(page)).append('\n');
            }
            return texte.toString();
        } finally {
            reader.close();
        }
    }

    private static void ecrire(String nom, String texte) throws IOException {
        Path dossier = Path.of("target", "sample-exports");
        Files.createDirectories(dossier);
        Files.writeString(dossier.resolve(nom), texte, StandardCharsets.UTF_8);
    }

    /** Two animateurs on one stand, a geocoded stand, and a day with no seat for Ada. */
    private static PlanningFestival planning() {
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

        return new PlanningFestival(LocalDate.of(2026, 8, 14),
                new ArrayList<>(List.of(ada, alan)), new ArrayList<>(postes));
    }

    private static PosteAffectation poste(String id, Stand stand, Creneau creneau, Animateur animateur) {
        PosteAffectation poste = new PosteAffectation(id, stand, creneau);
        poste.setAnimateur(animateur);
        return poste;
    }
}
