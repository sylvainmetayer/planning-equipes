package dev.sylvain.planning.service.export;

import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.service.ProductName;
import dev.sylvain.planning.service.analyse.PauseAnalyzer;
import dev.sylvain.planning.service.edition.EtiquetteEdition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * The planning of one animateur as an iCalendar calendar, so they can add it
 * to their own.
 *
 * <p>The only one of the three documents that is not a PDF, and it has nothing
 * in common with them: no layout, but a format specified down to the line —
 * mandatory {@code CRLF}, escaping on commas and semicolons, a named time zone
 * rather than an offset.</p>
 *
 * <p>Two of those lines carry the brand: {@code PRODID}, which every calendar
 * client displays as "imported from", and the domain part of each event's
 * {@code UID}. Both are derived from the deployment's product name, so a
 * customer's calendar never names somebody else's software.</p>
 *
 * <p>A third names the édition: {@code X-WR-CALNAME} is what a client shows in
 * its sidebar for a subscribed calendar, and without it the feed of an
 * animateur who subscribed two years running appears twice under the same
 * nothing (issue #608).</p>
 */
@ApplicationScoped
public class PlanningIcs {

    private static final String FUSEAU = "Europe/Paris";
    /** Fallback slug when the product name holds no letter or digit at all. */
    private static final String DEFAULT_SLUG = "planning";

    private static final DateTimeFormatter HORODATAGE_UTC =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_HEURE_LOCALE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final DateTimeFormatter DATE_SEULE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final String prodId;
    private final String uidDomain;

    @Inject
    public PlanningIcs(ProductName productName) {
        this(productName.value());
    }

    /** Neutral default, for the callers that live outside CDI (the export tests). */
    public PlanningIcs() {
        this(ProductName.neutral().value());
    }

    private PlanningIcs(String productName) {
        String slug = slug(productName);
        this.prodId = "-//" + slug + "//planning//FR";
        this.uidDomain = slug;
    }

    /**
     * Lowercase ASCII-ish slug of the product name: an iCalendar {@code PRODID}
     * and the domain part of a {@code UID} are read by machines, so anything
     * that is not a letter or a digit becomes a hyphen.
     */
    private static String slug(String productName) {
        String slug = Normalizer.normalize(productName, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        return slug.isEmpty() ? DEFAULT_SLUG : slug;
    }

    /**
     * What a client shows under the calendar's name: the édition it comes from,
     * and when the feed was read — the generation stamp this line has always
     * carried, which is how one tells a stale import from a live subscription.
     */
    private static String calendrierDescription(String nomCalendrier) {
        String genere =
                "Généré le " + PdfTheme.GENERATED_AT_FORMAT.format(Instant.now().atZone(ZoneOffset.systemDefault()));
        return nomCalendrier == null || nomCalendrier.isBlank() ? genere : nomCalendrier + " — " + genere;
    }

    public String exportAnimateurIcs(PlanningEvenement planning, String animateurId) {
        return exportAnimateurIcs(planning, animateurId, new PauseAnalyzer().pausesAnimateur(planning, animateurId));
    }

    /** Same feed, with the breaks already read — what a roster-wide export passes in. */
    public String exportAnimateurIcs(
            PlanningEvenement planning, String animateurId, List<PauseAnalyzer.PauseAnimateurView> pauses) {
        return exportAnimateurIcs(planning, animateurId, pauses, EtiquetteEdition.INCONNUE);
    }

    /** The same feed, named after the édition it belongs to (issue #608). */
    public String exportAnimateurIcs(
            PlanningEvenement planning,
            String animateurId,
            List<PauseAnalyzer.PauseAnimateurView> pauses,
            EtiquetteEdition edition) {
        List<PosteAffectation> postes = planning.getPostes().stream()
                .filter(poste -> poste.getAnimateur() != null
                        && animateurId.equals(poste.getAnimateur().getId()))
                .sorted(PlanningExportService.byCreneauThenStand())
                .toList();

        StringBuilder builder = new StringBuilder();
        builder.append("BEGIN:VCALENDAR\r\n")
                .append("VERSION:2.0\r\n")
                .append("PRODID:")
                .append(prodId)
                .append("\r\n");
        // The name a calendar client shows in its sidebar, and the description
        // it shows under it. Written only when the édition could be read: an
        // empty name would be worse than none.
        String nomCalendrier = edition == null ? null : edition.libelle();
        if (nomCalendrier != null && !nomCalendrier.isBlank()) {
            builder.append("X-WR-CALNAME:")
                    .append(escapeIcs("Mon planning — " + nomCalendrier))
                    .append("\r\n");
        }
        builder.append("X-WR-CALDESC:")
                .append(escapeIcs(calendrierDescription(nomCalendrier)))
                .append("\r\n")
                .append("CALSCALE:GREGORIAN\r\n");

        for (PosteAffectation poste : postes) {
            String uid = poste.getId() + "@" + uidDomain;
            builder.append("BEGIN:VEVENT\r\n")
                    .append("UID:")
                    .append(uid)
                    .append("\r\n")
                    .append("DTSTAMP:")
                    .append(HORODATAGE_UTC.format(Instant.now()))
                    .append("\r\n")
                    .append("DTSTART;TZID=")
                    .append(FUSEAU)
                    .append(":")
                    .append(poste.getCreneau()
                            .getDate()
                            .atTime(poste.heureDebutEffectif())
                            .format(DATE_HEURE_LOCALE))
                    .append("\r\n")
                    .append("DTEND;TZID=")
                    .append(FUSEAU)
                    .append(":")
                    .append(poste.getCreneau()
                            .getDate()
                            .atTime(poste.heureFinEffectif())
                            .format(DATE_HEURE_LOCALE))
                    .append("\r\n")
                    .append("SUMMARY:")
                    .append(escapeIcs(poste.getStand().getNom()))
                    .append("\r\n")
                    .append("DESCRIPTION:")
                    .append(escapeIcs("Stand " + poste.getStand().getNom() + " - slot "
                            + poste.getCreneau().getId() + descriptionPauses(pauses, poste)))
                    .append("\r\n");
            Emplacement emplacement = poste.getStand().getEmplacement();
            if (emplacement != null
                    && emplacement.getNom() != null
                    && !emplacement.getNom().isBlank()) {
                builder.append("LOCATION:")
                        .append(escapeIcs(emplacement.getNom()))
                        .append("\r\n");
            }
            if (emplacement != null && emplacement.isGeocoded()) {
                builder.append("GEO:")
                        .append(emplacement.getLatitude())
                        .append(";")
                        .append(emplacement.getLongitude())
                        .append("\r\n");
            }
            builder.append("END:VEVENT\r\n");
        }

        // Rest days as all-day, TRANSParent events: they show in the agenda
        // without marking the animateur busy — a day silently absent from the
        // calendar reads as a missing shift, an explicit « Repos » as a
        // planned one.
        for (PlanningExportService.JourRepos jourRepos : PlanningExportService.daysOff(planning, animateurId)) {
            builder.append("BEGIN:VEVENT\r\n")
                    .append("UID:repos-")
                    .append(jourRepos.date())
                    .append("-")
                    .append(animateurId)
                    .append("@")
                    .append(uidDomain)
                    .append("\r\n")
                    .append("DTSTAMP:")
                    .append(HORODATAGE_UTC.format(Instant.now()))
                    .append("\r\n")
                    .append("DTSTART;VALUE=DATE:")
                    .append(DATE_SEULE.format(jourRepos.date()))
                    .append("\r\n")
                    .append("DTEND;VALUE=DATE:")
                    .append(DATE_SEULE.format(jourRepos.date().plusDays(1)))
                    .append("\r\n")
                    .append("SUMMARY:Repos\r\n")
                    .append("TRANSP:TRANSPARENT\r\n")
                    .append("END:VEVENT\r\n");
        }

        builder.append("END:VCALENDAR\r\n");
        return builder.toString();
    }

    private String escapeIcs(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace(",", "\\,")
                .replace(";", "\\;")
                .replace("\n", "\\n");
    }

    /** « Pause de 18:20 à 18:40 (20 min) », appended to the event the break falls in. */
    private static String descriptionPauses(List<PauseAnalyzer.PauseAnimateurView> pauses, PosteAffectation poste) {
        StringBuilder texte = new StringBuilder();
        for (PauseAnalyzer.PauseAnimateurView pause : pauses) {
            if (pause.fallsInside(poste)) {
                texte.append(" - Pause de ")
                        .append(pause.debut())
                        .append(" à ")
                        .append(pause.fin())
                        .append(" (")
                        .append(pause.dureeMinutes())
                        .append(" min)");
            }
        }
        return texte.toString();
    }
}
