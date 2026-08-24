package dev.sylvain.planning.service;

import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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
 */
@ApplicationScoped
public class PlanningIcs {

    private static final String FUSEAU = "Europe/Paris";
    /** Fallback slug when the product name holds no letter or digit at all. */
    private static final String DEFAULT_SLUG = "planning";
    private static final DateTimeFormatter HORODATAGE_UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);
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

    public String exportAnimateurIcs(PlanningEvenement planning, String animateurId) {
        List<PosteAffectation> postes = planning.getPostes().stream()
                .filter(poste -> poste.getAnimateur() != null && animateurId.equals(poste.getAnimateur().getId()))
                .sorted(PlanningExportService.byCreneauThenStand())
                .toList();

        StringBuilder builder = new StringBuilder();
        builder.append("BEGIN:VCALENDAR\r\n")
                .append("VERSION:2.0\r\n")
                .append("PRODID:").append(prodId).append("\r\n")
                .append("X-WR-CALDESC:Généré le ")
                .append(PdfTheme.GENERATED_AT_FORMAT.format(Instant.now().atZone(ZoneOffset.systemDefault())))
                .append("\r\n")
                .append("CALSCALE:GREGORIAN\r\n");

        for (PosteAffectation poste : postes) {
            String uid = poste.getId() + "@" + uidDomain;
            builder.append("BEGIN:VEVENT\r\n")
                    .append("UID:").append(uid).append("\r\n")
                    .append("DTSTAMP:").append(HORODATAGE_UTC.format(Instant.now())).append("\r\n")
                    .append("DTSTART;TZID=").append(FUSEAU).append(":")
                    .append(poste.getCreneau().getDate().atTime(poste.heureDebutEffectif())
                            .format(DATE_HEURE_LOCALE))
                    .append("\r\n")
                    .append("DTEND;TZID=").append(FUSEAU).append(":")
                    .append(poste.getCreneau().getDate().atTime(poste.heureFinEffectif())
                            .format(DATE_HEURE_LOCALE))
                    .append("\r\n")
                    .append("SUMMARY:").append(escapeIcs(poste.getStand().getNom())).append("\r\n")
                    .append("DESCRIPTION:")
                    .append(escapeIcs("Stand " + poste.getStand().getNom() + " - slot " + poste.getCreneau().getId()))
                    .append("\r\n");
            Emplacement emplacement = poste.getStand().getEmplacement();
            if (emplacement != null && emplacement.getNom() != null && !emplacement.getNom().isBlank()) {
                builder.append("LOCATION:").append(escapeIcs(emplacement.getNom())).append("\r\n");
            }
            if (emplacement != null && emplacement.getLatitude() != null && emplacement.getLongitude() != null) {
                builder.append("GEO:").append(emplacement.getLatitude()).append(";").append(emplacement.getLongitude())
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
                    .append("UID:repos-").append(jourRepos.date()).append("-").append(animateurId)
                    .append("@").append(uidDomain).append("\r\n")
                    .append("DTSTAMP:").append(HORODATAGE_UTC.format(Instant.now())).append("\r\n")
                    .append("DTSTART;VALUE=DATE:").append(DATE_SEULE.format(jourRepos.date())).append("\r\n")
                    .append("DTEND;VALUE=DATE:").append(DATE_SEULE.format(jourRepos.date().plusDays(1)))
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
}
