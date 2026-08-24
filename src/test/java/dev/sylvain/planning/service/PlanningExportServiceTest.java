package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;

/**
 * Exercises PDF/ICS export against a small hand-built planning, without a
 * Quarkus context or a database. Beyond asserting the output is a well-formed
 * PDF, this writes the generated files to {@code target/sample-exports/} so a
 * developer can open them after {@code mvn test} to eyeball the rendering.
 */
class PlanningExportServiceTest {

    /** A fixed provenance: these tests read the documents, not the database. */
    private static final ExportProvenance PROVENANCE = () -> new ExportProvenance.Provenance(
            "Édition de test", Instant.parse("2026-07-01T08:30:00Z"));

    private final PlanningExportService service = new PlanningExportService(new ApplicationLinks(Optional.empty()),
            new AnimateurPlanningPdf(new PdfTheme()), new GlobalPlanningPdf(new PdfTheme()), new PlanningIcs(), PROVENANCE);
    private final AtomicInteger posteSequence = new AtomicInteger();

    @Test
    void exportAnimateurPdfProducesAWellFormedNonEmptyPdf() throws IOException {
        PlanningEvenement planning = fakePlanning();
        String animateurId = planning.getAnimateurs().get(0).getId();

        byte[] pdf = service.exportAnimateurPdf(planning, animateurId);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        writeSample("planning-sample-" + animateurId + ".pdf", pdf);
    }

    /**
     * An event day without any seat for the animateur is an explicit
     * « Repos » day — but only for animateurs who hold at least one seat:
     * someone absent from the plan is not "resting every day".
     */
    @Test
    void joursDeReposListsTheEventDaysWithoutAnyAssignment() {
        PlanningEvenement planning = new PlanningEvenement();
        Stand stand = new Stand("STAND-A", "Stand A", Set.of("STRATEGIE"), 1, 1, false);
        Creneau jour1 = new Creneau(1L, 1, LocalDate.of(2026, 8, 14), LocalTime.of(9, 0), LocalTime.of(13, 0));
        Creneau jour2 = new Creneau(2L, 2, LocalDate.of(2026, 8, 15), LocalTime.of(9, 0), LocalTime.of(13, 0));
        Creneau jour3 = new Creneau(3L, 3, LocalDate.of(2026, 8, 16), LocalTime.of(9, 0), LocalTime.of(13, 0));
        Animateur ada = new Animateur("A1", "Ada", "Lovelace", LocalDate.of(2000, 1, 1), false);
        PosteAffectation posteJour1 = new PosteAffectation("p1", stand, jour1);
        posteJour1.setAnimateur(ada);
        PosteAffectation posteJour2 = new PosteAffectation("p2", stand, jour2);
        PosteAffectation posteJour3 = new PosteAffectation("p3", stand, jour3);
        posteJour3.setAnimateur(ada);
        planning.setAnimateurs(List.of(ada));
        planning.setPostes(List.of(posteJour1, posteJour2, posteJour3));

        assertThat(service.daysOff(planning, "A1"))
                .containsExactly(new PlanningExportService.JourRepos(2, LocalDate.of(2026, 8, 15)));
        // No seat at all: no repos days either — the exports keep their empty state.
        assertThat(service.daysOff(planning, "ABSENT")).isEmpty();
    }

    /** Rest days land in the ICS as all-day, transparent events. */
    @Test
    void exportAnimateurIcsMarksRestDaysAsTransparentAllDayEvents() {
        PlanningEvenement planning = new PlanningEvenement();
        Stand stand = new Stand("STAND-A", "Stand A", Set.of("STRATEGIE"), 1, 1, false);
        Creneau jour1 = new Creneau(1L, 1, LocalDate.of(2026, 8, 14), LocalTime.of(9, 0), LocalTime.of(13, 0));
        Creneau jour2 = new Creneau(2L, 2, LocalDate.of(2026, 8, 15), LocalTime.of(9, 0), LocalTime.of(13, 0));
        Animateur ada = new Animateur("A1", "Ada", "Lovelace", LocalDate.of(2000, 1, 1), false);
        PosteAffectation travaille = new PosteAffectation("p1", stand, jour1);
        travaille.setAnimateur(ada);
        planning.setAnimateurs(List.of(ada));
        planning.setPostes(List.of(travaille, new PosteAffectation("p2", stand, jour2)));

        String ics = service.exportAnimateurIcs(planning, "A1");

        assertThat(ics).contains("SUMMARY:Repos")
                .contains("DTSTART;VALUE=DATE:20260815")
                .contains("DTEND;VALUE=DATE:20260816")
                .contains("TRANSP:TRANSPARENT");
    }

    @Test
    void coequipiersNamesTheOthersOnTheSameStandLineOnly() {
        PlanningEvenement planning = new PlanningEvenement();
        Stand stand = new Stand("STAND-A", "Stand A", Set.of("STRATEGIE"), 2, 2, false);
        Stand autre = new Stand("STAND-B", "Stand B", Set.of("STRATEGIE"), 1, 1, false);
        Creneau creneau = new Creneau(1L, 1, LocalDate.of(2026, 8, 14), LocalTime.of(9, 0), LocalTime.of(13, 0));
        Animateur ada = new Animateur("A1", "Ada", "Lovelace", LocalDate.of(2000, 1, 1), false);
        Animateur alan = new Animateur("A2", "Alan", "Turing", LocalDate.of(2000, 1, 1), false);
        Animateur grace = new Animateur("A3", "Grace", "Hopper", LocalDate.of(2000, 1, 1), false);
        PosteAffectation p1 = new PosteAffectation("p1", stand, creneau);
        p1.setAnimateur(ada);
        PosteAffectation p2 = new PosteAffectation("p2", stand, creneau);
        p2.setAnimateur(alan);
        PosteAffectation p3 = new PosteAffectation("p3", stand, creneau); // unfilled seat: nobody to name
        PosteAffectation p4 = new PosteAffectation("p4", autre, creneau); // same créneau, another stand
        p4.setAnimateur(grace);
        planning.setAnimateurs(List.of(ada, alan, grace));
        planning.setPostes(List.of(p1, p2, p3, p4));

        assertThat(service.teammatesByPoste(planning, "A1")).containsExactly(entry("p1", List.of("Alan Turing")));
    }

    @Test
    void coequipiersIgnoresTheOtherSegmentOfAStandSplitByAClosure() {
        PlanningEvenement planning = new PlanningEvenement();
        Stand stand = new Stand("STAND-A", "Stand A", Set.of("STRATEGIE"), 1, 1, false);
        Creneau creneau = new Creneau(1L, 1, LocalDate.of(2026, 8, 14), LocalTime.of(9, 0), LocalTime.of(18, 0));
        Animateur ada = new Animateur("A1", "Ada", "Lovelace", LocalDate.of(2000, 1, 1), false);
        Animateur alan = new Animateur("A2", "Alan", "Turing", LocalDate.of(2000, 1, 1), false);
        PosteAffectation matin = new PosteAffectation("p1", stand, creneau);
        matin.setAnimateur(ada);
        matin.setHeureDebutEffective(LocalTime.of(9, 0));
        matin.setHeureFinEffective(LocalTime.of(12, 0));
        PosteAffectation afternoon = new PosteAffectation("p2", stand, creneau);
        afternoon.setAnimateur(alan);
        afternoon.setHeureDebutEffective(LocalTime.of(14, 0));
        afternoon.setHeureFinEffective(LocalTime.of(18, 0));
        planning.setAnimateurs(List.of(ada, alan));
        planning.setPostes(List.of(matin, afternoon));

        assertThat(service.teammatesByPoste(planning, "A1")).containsExactly(entry("p1", List.of()));
    }

    @Test
    void exportGlobalPdfProducesAWellFormedPdfCoveringEveryAssignment() throws IOException {
        PlanningEvenement planning = fakePlanning();

        byte[] pdf = service.exportGlobalPdf(planning);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        writeSample("planning-sample-global.pdf", pdf);
    }

    /** An empty planning must still produce a readable document, not an exception. */
    @Test
    void exportGlobalPdfHandlesAnEmptyPlanning() {
        PlanningEvenement planning = new PlanningEvenement();
        planning.setAnimateurs(List.of());
        planning.setPostes(List.of());

        byte[] pdf = service.exportGlobalPdf(planning);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    }

    @Test
    void exportAllPdfZipBundlesOnePdfPerAnimateur() throws IOException {
        PlanningEvenement planning = fakePlanning();

        byte[] zip = service.exportAllPdfZip(planning);

        assertThat(zip).isNotEmpty();
        writeSample("planning-sample-all.zip", zip);
    }

    @Test
    void exportAllBundleZipContainsBothPdfAndIcsPerAnimateur() throws IOException {
        PlanningEvenement planning = fakePlanning();

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
        PlanningEvenement planning = fakePlanning();
        String animateurId = planning.getAnimateurs().get(0).getId();

        String ics = service.exportAnimateurIcs(planning, animateurId);

        assertThat(ics).startsWith("BEGIN:VCALENDAR").endsWith("END:VCALENDAR\r\n");
    }

    @Test
    void exportAnimateurIcsIncludesLocationAndGeoForAGeocodedStandOnly() {
        PlanningEvenement planning = fakePlanning();
        String animateurId = planning.getAnimateurs().get(0).getId();

        String ics = service.exportAnimateurIcs(planning, animateurId);

        // Referent's postes span standWithStrategy/standAdultes (no emplacement)
        // and standPremium (geocoded, see fakePlanning): exactly one VEVENT
        // should carry the LOCATION/GEO pair, proving the other two postes'
        // null-emplacement path stays untouched.
        assertThat(ics).contains("LOCATION:Kiosque Central\r\n");
        assertThat(ics).contains("GEO:48.8566;2.3522\r\n");
        assertThat(countOccurrences(ics, "LOCATION:")).isEqualTo(1);
        assertThat(countOccurrences(ics, "GEO:")).isEqualTo(1);
    }

    /**
     * Regression: exporting a poste narrowed by a partial stand closure
     * (issue #60) must use its effective window, not its créneau's full
     * span — the bug reported live (Oscar Fontaine's PDF showing him working
     * the whole 13:40-19:00 créneau on a stand closed 14:00-16:00 inside it).
     * The PDF card uses the exact same {@code PosteAffectation} accessor
     * ({@code heureDebutEffectif()}/{@code heureFinEffectif()}) exercised
     * here through ICS, where the resulting text is easy to assert on.
     */
    @Test
    void exportAnimateurIcsUsesThePosteEffectiveWindowNotTheCreneauFullSpan() {
        Stand stand = new Stand("STAND-1", "Stand", java.util.Set.of(), 1, 1, false);
        Creneau creneau = new Creneau(1L, 1, LocalDate.of(2026, 7, 10), LocalTime.of(13, 40), LocalTime.of(19, 0));
        Animateur oscar = new Animateur("A-OSCAR", "Oscar", "Fontaine", LocalDate.of(1990, 1, 1), false);
        PosteAffectation poste = new PosteAffectation("P1", stand, creneau);
        poste.setAnimateur(oscar);
        poste.setHeureDebutEffective(LocalTime.of(16, 0));
        poste.setHeureFinEffective(LocalTime.of(19, 0));
        PlanningEvenement planning = new PlanningEvenement(creneau.getDate(), List.of(oscar), List.of(poste));

        String ics = service.exportAnimateurIcs(planning, "A-OSCAR");

        assertThat(ics).contains("DTSTART;TZID=Europe/Paris:20260710T160000");
        assertThat(ics).contains("DTEND;TZID=Europe/Paris:20260710T190000");
        assertThat(ics).doesNotContain("T134000");
    }

    @Test
    void exportAnimateurPdfRendersAGeocodedStandWithoutError() throws IOException {
        PlanningEvenement planning = fakePlanning();
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
    private PlanningEvenement fakePlanning() {
        Stand standWithStrategy = new Stand("STAND-1", "Stratèges Associés", typologies("STRATEGIE"), 1, 2, false);
        Stand standPremium = new Stand("STAND-2", "Éditeur Vedette", typologies("AMBIANCE"), 1, 2, false, true);
        standPremium.setEmplacement(new Emplacement("EMP-1", "Kiosque Central", 48.8566, 2.3522));
        Stand standAdultes = new Stand("STAND-3", "Loup-Garou Nocturne", typologies("ROLE"), 1, 1, true);
        Stand standEnfant = new Stand("STAND-4", "Coin des Petits", typologies("ENFANT"), 1, 2, false);

        Creneau matinJ1 = new Creneau(1L, 1, LocalDate.of(2026, 8, 14), LocalTime.of(9, 0), LocalTime.of(13, 0));
        Creneau apremJ1 = new Creneau(2L, 1, LocalDate.of(2026, 8, 14), LocalTime.of(14, 0), LocalTime.of(18, 0));
        Creneau matinJ2 = new Creneau(3L, 2, LocalDate.of(2026, 8, 15), LocalTime.of(9, 0), LocalTime.of(13, 0));

        Animateur referent = animateur("A-ADA", "Ada", "Lovelace", NiveauCompetence.REFERENT);
        Animateur debutant = animateur("A-ALAN", "Alan", "Turing", NiveauCompetence.DEBUTANT);

        List<PosteAffectation> postes = new ArrayList<>();
        postes.add(assignedPoste(standWithStrategy, matinJ1, referent));
        postes.add(assignedPoste(standPremium, matinJ1, referent));
        postes.add(assignedPoste(standAdultes, apremJ1, referent));
        postes.add(assignedPoste(standEnfant, apremJ1, debutant));
        postes.add(assignedPoste(standWithStrategy, matinJ2, debutant));
        postes.add(assignedPoste(standPremium, matinJ2, debutant));
        postes.add(unassignedPoste(standEnfant, matinJ1));
        postes.add(unassignedPoste(standAdultes, matinJ2));

        return new PlanningEvenement(matinJ1.getDate(), List.of(referent, debutant), postes);
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
        Map<String, NiveauCompetence> competences = new HashMap<>();
        for (String typologie : List.of("STRATEGIE", "AMBIANCE", "ENFANT", "COOPERATIF", "ADRESSE", "ROLE", "ENIGME",
                "HOMME_JEU")) {
            competences.put(typologie, niveau);
        }
        animateur.setCompetences(competences);
        return animateur;
    }

    /** The same service, but with a public URL configured: the espace links become printable. */
    private static PlanningExportService exportsWithLinks(String baseUrl) {
        return new PlanningExportService(new ApplicationLinks(Optional.of(baseUrl)),
                new AnimateurPlanningPdf(new PdfTheme()), new GlobalPlanningPdf(new PdfTheme()), new PlanningIcs(), PROVENANCE);
    }

    private Set<String> typologies(String... typologies) {
        return new HashSet<>(List.of(typologies));
    }

    /**
     * The exact regression centralising the links introduced: an animateur who
     * has never opened their espace has no token, and that is the common case on
     * a freshly imported plan. Putting {@code findFirst()} before the filter
     * then made the PDF export of <b>every</b> animateur throw a
     * {@code NullPointerException}.
     */
    @Test
    void unAnimateurSansJetonNaPasDeLienEspaceMaisNeFaitPasEchouerLExport() {
        PlanningExportService service = exportsWithLinks("https://planning.example.org");
        PlanningEvenement planning = new PlanningEvenement();
        Animateur withoutToken = new Animateur("SANS", "Sans", "Jeton", LocalDate.of(2000, 1, 1), false);
        Animateur withToken = new Animateur("AVEC", "Avec", "Jeton", LocalDate.of(2000, 1, 1), false);
        withToken.setAccessToken("jeton-1");
        planning.setAnimateurs(List.of(withoutToken, withToken));

        assertThat(service.lienEspaceAnimateur(planning, "SANS")).isNull();
        assertThat(service.lienEspaceAnimateur(planning, "AVEC"))
                .isEqualTo("https://planning.example.org/animateur/jeton-1");
    }
}
