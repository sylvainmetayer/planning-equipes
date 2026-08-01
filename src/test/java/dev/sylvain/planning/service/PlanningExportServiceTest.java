package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypologieJeu;

/**
 * Exercises PDF/ICS export against a small hand-built planning, without a
 * Quarkus context or a database. Beyond asserting the output is a well-formed
 * PDF, this writes the generated files to {@code target/sample-exports/} so a
 * developer can open them after {@code mvn test} to eyeball the rendering.
 */
class PlanningExportServiceTest {

    private final PlanningExportService service = new PlanningExportService();
    private final AtomicInteger posteSequence = new AtomicInteger();

    @Test
    void exportAnimateurPdfProducesAWellFormedNonEmptyPdf() throws IOException {
        PlanningFestival planning = fakePlanning();
        String animateurId = planning.getAnimateurs().get(0).getId();

        byte[] pdf = service.exportAnimateurPdf(planning, animateurId);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        writeSample("planning-sample-" + animateurId + ".pdf", pdf);
    }

    @Test
    void exportAllPdfZipBundlesOnePdfPerAnimateur() throws IOException {
        PlanningFestival planning = fakePlanning();

        byte[] zip = service.exportAllPdfZip(planning);

        assertThat(zip).isNotEmpty();
        writeSample("planning-sample-all.zip", zip);
    }

    @Test
    void exportAllBundleZipContainsBothPdfAndIcsPerAnimateur() throws IOException {
        PlanningFestival planning = fakePlanning();

        byte[] zip = service.exportAllBundleZip(planning);

        List<String> entries = new ArrayList<>();
        try (java.util.zip.ZipInputStream zipIn = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                entries.add(entry.getName());
            }
        }
        assertThat(entries).hasSize(planning.getAnimateurs().size() * 2);
        assertThat(entries).anyMatch(name -> name.endsWith(".pdf"));
        assertThat(entries).anyMatch(name -> name.endsWith(".ics"));
        writeSample("planning-sample-bundle.zip", zip);
    }

    @Test
    void exportAnimateurIcsProducesAValidCalendar() {
        PlanningFestival planning = fakePlanning();
        String animateurId = planning.getAnimateurs().get(0).getId();

        String ics = service.exportAnimateurIcs(planning, animateurId);

        assertThat(ics).startsWith("BEGIN:VCALENDAR").endsWith("END:VCALENDAR\r\n");
    }

    @Test
    void exportAnimateurIcsIncludesLocationAndGeoForAGeocodedStandOnly() {
        PlanningFestival planning = fakePlanning();
        String animateurId = planning.getAnimateurs().get(0).getId();

        String ics = service.exportAnimateurIcs(planning, animateurId);

        // Referent's postes span standStrategie/standAdultes (no emplacement)
        // and standPremium (geocoded, see fakePlanning): exactly one VEVENT
        // should carry the LOCATION/GEO pair, proving the other two postes'
        // null-emplacement path stays untouched.
        assertThat(ics).contains("LOCATION:Kiosque Central\r\n");
        assertThat(ics).contains("GEO:48.8566;2.3522\r\n");
        assertThat(countOccurrences(ics, "LOCATION:")).isEqualTo(1);
        assertThat(countOccurrences(ics, "GEO:")).isEqualTo(1);
    }

    @Test
    void exportAnimateurPdfRendersAGeocodedStandWithoutError() throws IOException {
        PlanningFestival planning = fakePlanning();
        String animateurId = planning.getAnimateurs().get(0).getId();

        byte[] pdf = service.exportAnimateurPdf(planning, animateurId);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    }

    private int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private void writeSample(String filename, byte[] content) throws IOException {
        Path dir = Path.of("target", "sample-exports");
        Files.createDirectories(dir);
        Files.write(dir.resolve(filename), content);
    }

    /**
     * A small, self-contained planning: 4 stands (one premium, one adults-only),
     * 2 animateurs (a beginner and a referent) and 2 days of timeslots, with a
     * couple of unassigned postes to exercise the "UNASSIGNED"/empty-state paths.
     */
    private PlanningFestival fakePlanning() {
        Stand standStrategie = new Stand("STAND-1", "Stratèges Associés", typologies(TypologieJeu.STRATEGIE), 1, 2, false);
        Stand standPremium = new Stand("STAND-2", "Éditeur Vedette", typologies(TypologieJeu.AMBIANCE), 1, 2, false, true);
        standPremium.setEmplacement(new Emplacement("EMP-1", "Kiosque Central", 48.8566, 2.3522));
        Stand standAdultes = new Stand("STAND-3", "Loup-Garou Nocturne", typologies(TypologieJeu.ROLE), 1, 1, true);
        Stand standEnfant = new Stand("STAND-4", "Coin des Petits", typologies(TypologieJeu.ENFANT), 1, 2, false);

        Creneau matinJ1 = new Creneau(1L, 1, LocalDate.of(2026, 8, 14), LocalTime.of(9, 0), LocalTime.of(13, 0));
        Creneau apremJ1 = new Creneau(2L, 1, LocalDate.of(2026, 8, 14), LocalTime.of(14, 0), LocalTime.of(18, 0));
        Creneau matinJ2 = new Creneau(3L, 2, LocalDate.of(2026, 8, 15), LocalTime.of(9, 0), LocalTime.of(13, 0));

        Animateur referent = animateur("A-ADA", "Ada", "Lovelace", NiveauCompetence.REFERENT);
        Animateur debutant = animateur("A-ALAN", "Alan", "Turing", NiveauCompetence.DEBUTANT);

        List<PosteAffectation> postes = new ArrayList<>();
        postes.add(assignedPoste(standStrategie, matinJ1, referent));
        postes.add(assignedPoste(standPremium, matinJ1, referent));
        postes.add(assignedPoste(standAdultes, apremJ1, referent));
        postes.add(assignedPoste(standEnfant, apremJ1, debutant));
        postes.add(assignedPoste(standStrategie, matinJ2, debutant));
        postes.add(assignedPoste(standPremium, matinJ2, debutant));
        postes.add(unassignedPoste(standEnfant, matinJ1));
        postes.add(unassignedPoste(standAdultes, matinJ2));

        return new PlanningFestival(matinJ1.getDate(), List.of(referent, debutant), postes);
    }

    private PosteAffectation assignedPoste(Stand stand, Creneau creneau, Animateur animateur) {
        PosteAffectation poste = new PosteAffectation("P" + posteSequence.incrementAndGet(), stand, creneau);
        poste.setAnimateur(animateur);
        return poste;
    }

    private PosteAffectation unassignedPoste(Stand stand, Creneau creneau) {
        return new PosteAffectation("P" + posteSequence.incrementAndGet(), stand, creneau);
    }

    private Animateur animateur(String id, String prenom, String nom, NiveauCompetence niveau) {
        Animateur animateur = new Animateur(id, prenom, nom, LocalDate.of(1995, 1, 1), false);
        Map<TypologieJeu, NiveauCompetence> competences = new HashMap<>();
        for (TypologieJeu typologie : TypologieJeu.values()) {
            competences.put(typologie, niveau);
        }
        animateur.setCompetences(competences);
        return animateur;
    }

    private Set<TypologieJeu> typologies(TypologieJeu... typologies) {
        return new HashSet<>(List.of(typologies));
    }
}
