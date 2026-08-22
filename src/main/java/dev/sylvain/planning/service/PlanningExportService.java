package dev.sylvain.planning.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * A single door onto the three exports of a planning: the individual PDF, the
 * global summary, the calendar — and the ZIPs that hand them out.
 *
 * <p>It assembles no document itself. Every format has its own class
 * ({@link AnimateurPlanningPdf}, {@link GlobalPlanningPdf},
 * {@link PlanningIcs}) and their shared visual identity lives in
 * {@link PdfTheme}. What is left here is what holds for all three: who is
 * concerned, how they are named, their days off, the link to their espace.</p>
 */
@ApplicationScoped
public class PlanningExportService {

    private final ApplicationLinks liens;
    private final AnimateurPlanningPdf pdfAnimateur;
    private final GlobalPlanningPdf pdfGlobal;
    private final PlanningIcs ics;

    /**
     * Constructor injection rather than field injection: the four collaborators
     * are immutable, and a test outside CDI provides them explicitly — no
     * {@code liens == null} is written for it in production code any more.
     */
    @Inject
    public PlanningExportService(ApplicationLinks liens, AnimateurPlanningPdf pdfAnimateur,
            GlobalPlanningPdf pdfGlobal, PlanningIcs ics) {
        this.liens = liens;
        this.pdfAnimateur = pdfAnimateur;
        this.pdfGlobal = pdfGlobal;
        this.ics = ics;
    }

    public byte[] exportAnimateurPdf(PlanningFestival planning, String animateurId) {
        List<PosteAffectation> animateurPostes = planning.getPostes().stream()
                .filter(poste -> poste.getAnimateur() != null && animateurId.equals(poste.getAnimateur().getId()))
                .sorted(byCreneauThenStand())
                .toList();
        return pdfAnimateur.construire(resolveAnimateurName(planning, animateurId), animateurPostes,
                teammatesByPoste(planning, animateurId), daysOff(planning, animateurId),
                lienEspaceAnimateur(planning, animateurId));
    }

    /** A festival day the animateur is off: its day number and its date. */
    public record JourRepos(int jour, LocalDate date) {
    }

    /**
     * The festival days {@code animateurId} holds no seat on — their rest
     * days, worth saying out loud: a day silently missing from a planning
     * reads as an oversight, an explicit « Repos » reads as a decision (the
     * staffing workbook dedicates a whole sheet to that rotation). Days come from
     * the planning's own créneaux, so the notion of "festival day" follows
     * whatever group the plan was solved for. Empty when the animateur holds
     * no seat at all: someone absent from the plan is not "resting every
     * day", and their exports keep the plain empty state.
     */
    public static List<JourRepos> daysOff(PlanningFestival planning, String animateurId) {
        Map<LocalDate, Integer> joursFestival = new TreeMap<>();
        Set<LocalDate> joursTravailles = new HashSet<>();
        for (PosteAffectation poste : planning.getPostes()) {
            Creneau creneau = poste.getCreneau();
            if (creneau == null || creneau.getDate() == null) {
                continue;
            }
            joursFestival.putIfAbsent(creneau.getDate(), creneau.getJour());
            if (poste.getAnimateur() != null && animateurId.equals(poste.getAnimateur().getId())) {
                joursTravailles.add(creneau.getDate());
            }
        }
        if (joursTravailles.isEmpty()) {
            return List.of();
        }
        return joursFestival.entrySet().stream()
                .filter(jour -> !joursTravailles.contains(jour.getKey()))
                .map(jour -> new JourRepos(jour.getValue(), jour.getKey()))
                .toList();
    }

    /**
     * The animateur's personal espace URL, {@code null} when no base URL is
     * configured or the animateur carries no access token. The token IS the
     * credential of the espace: it only ever leaves through this link — on the
     * animateur's own PDF, or in the mail sending them that PDF
     * ({@code EnvoiPlanningResource}).
     */
    public String lienEspaceAnimateur(PlanningFestival planning, String animateurId) {
        if (planning.getAnimateurs() == null) {
            return null;
        }
        return planning.getAnimateurs().stream()
                .filter(animateur -> animateurId.equals(animateur.getId()))
                .map(Animateur::getJetonAcces)
                // Before findFirst, not after: Stream.findFirst throws on a null
                // element, and an animateur who never opened their espace has no
                // token — the common case on a freshly imported plan.
                .filter(token -> token != null && !token.isBlank())
                .findFirst()
                .flatMap(liens::espaceAnimateur)
                .orElse(null);
    }

    /**
     * For each of this animateur's postes, the names of the others holding a
     * seat on the same stand, same créneau and same window — who they will
     * actually be working alongside, which is what someone reads their own
     * planning to find out. Keyed by poste id, empty list when they hold the
     * stand alone.
     *
     * <p>Same grouping key as the calendars: two segments of one stand split by
     * a mid-créneau closure are not the same line, and the people on either
     * side never meet.</p>
     *
     * <p>Package-private so the rule is unit-tested on plain objects rather
     * than through the bytes of a generated PDF.</p>
     */
    static Map<String, List<String>> teammatesByPoste(PlanningFestival planning, String animateurId) {
        Map<String, List<String>> equipeParLigne = new LinkedHashMap<>();
        for (PosteAffectation poste : planning.getPostes()) {
            if (poste.getAnimateur() == null || poste.getCreneau() == null || poste.getStand() == null) {
                continue;
            }
            equipeParLigne.computeIfAbsent(ligneKey(poste), ignored -> new ArrayList<>())
                    .add(poste.getAnimateur().nomAffiche());
        }
        Map<String, List<String>> parPoste = new LinkedHashMap<>();
        for (PosteAffectation poste : planning.getPostes()) {
            if (poste.getAnimateur() == null || !animateurId.equals(poste.getAnimateur().getId())
                    || poste.getCreneau() == null || poste.getStand() == null) {
                continue;
            }
            List<String> equipe = new ArrayList<>(equipeParLigne.getOrDefault(ligneKey(poste), List.of()));
            equipe.remove(poste.getAnimateur().nomAffiche());
            equipe.sort(String.CASE_INSENSITIVE_ORDER);
            parPoste.put(poste.getId(), equipe);
        }
        return parPoste;
    }

    private static String ligneKey(PosteAffectation poste) {
        return poste.getStand().getId() + "@" + poste.getCreneau().getId() + "#" + poste.heureDebutEffectif() + "-"
                + poste.heureFinEffectif();
    }

    /** The whole planning in one landscape PDF, for the organiser — see {@link GlobalPlanningPdf}. */
    public byte[] exportGlobalPdf(PlanningFestival planning) {
        return pdfGlobal.construire(planning);
    }

    /** The animateur's planning as an iCalendar feed — see {@link PlanningIcs}. */
    public String exportAnimateurIcs(PlanningFestival planning, String animateurId) {
        return ics.exportAnimateurIcs(planning, animateurId);
    }

    /**
     * One PDF per animateur, bundled in a single ZIP. Replaces the former
     * global PDF: the planning is always handed out person by person.
     */
    public byte[] exportAllPdfZip(PlanningFestival planning) {
        return buildZip(planning, List.of(new NamedFileBuilder(".pdf", id -> exportAnimateurPdf(planning, id))));
    }

    public byte[] exportAllIcsZip(PlanningFestival planning) {
        return buildZip(planning, List.of(new NamedFileBuilder(".ics",
                id -> ics.exportAnimateurIcs(planning, id).getBytes(StandardCharsets.UTF_8))));
    }

    /**
     * Both the PDF and the ICS of every animateur, bundled in a single ZIP so
     * the whole planning can be handed out through one download.
     */
    public byte[] exportAllBundleZip(PlanningFestival planning) {
        return buildZip(planning, List.of(
                new NamedFileBuilder(".pdf", id -> exportAnimateurPdf(planning, id)),
                new NamedFileBuilder(".ics",
                        id -> ics.exportAnimateurIcs(planning, id).getBytes(StandardCharsets.UTF_8))));
    }

    /** Bundles one or more files per animateur, named after the animateur, into a ZIP. */
    private byte[] buildZip(PlanningFestival planning, List<NamedFileBuilder> fileBuilders) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            Set<String> usedFilenames = new LinkedHashSet<>();
            for (Animateur animateur : planning.getAnimateurs()) {
                String baseName = resolveAnimateurName(planning, animateur.getId())
                        .replaceAll("[\\\\/\\r\\n\\\"]", "_");
                for (NamedFileBuilder fileBuilder : fileBuilders) {
                    String filename = baseName + fileBuilder.extension();
                    int suffix = 2;
                    while (!usedFilenames.add(filename)) {
                        filename = baseName + "-" + suffix + fileBuilder.extension();
                        suffix++;
                    }
                    zip.putNextEntry(new ZipEntry(filename));
                    zip.write(fileBuilder.builder().build(animateur.getId()));
                    zip.closeEntry();
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Unable to build ZIP export", e);
        }
        return output.toByteArray();
    }

    private record NamedFileBuilder(String extension, AnimateurFileBuilder builder) {
    }

    @FunctionalInterface
    private interface AnimateurFileBuilder {
        byte[] build(String animateurId);
    }

    /**
     * File name of an individual planning: {@code planning-Prenom-Nom.pdf}.
     * Written here rather than in every resource — the admin who gets the PDF by
     * mail and the animateur who downloads it from their espace must read the
     * same name.
     */
    public static String planningFileName(String nomAffiche, String extension) {
        String sansAccroc = nomAffiche.replaceAll("[^\\p{L}\\p{N}]+", "-").replaceAll("^-+|-+$", "");
        return "planning-" + (sansAccroc.isEmpty() ? "animateur" : sansAccroc) + "." + extension;
    }

    public static String resolveAnimateurName(PlanningFestival planning, String animateurId) {
        return planning.getAnimateurs().stream()
                .filter(animateur -> animateurId.equals(animateur.getId()))
                .findFirst()
                .map(Animateur::nomAffiche)
                .orElse(animateurId);
    }

    /** Chronological, then by stand — the order an animateur reads their day in. */
    static Comparator<PosteAffectation> byCreneauThenStand() {
        return Comparator
                .comparing((PosteAffectation poste) -> poste.getCreneau().getDate())
                .thenComparing(poste -> poste.getCreneau().getHeureDebut())
                .thenComparing(poste -> poste.getStand().getNom());
    }
}
