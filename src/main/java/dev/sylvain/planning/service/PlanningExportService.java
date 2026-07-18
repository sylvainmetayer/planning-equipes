package dev.sylvain.planning.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import com.lowagie.text.Document;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PlanningExportService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HHmmss");
    private static final DateTimeFormatter ICS_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

    public byte[] exportGlobalPdf(PlanningFestival planning) {
        return buildPdf(
                "Global planning",
                planning.getPostes().stream()
                        .sorted(byCreneauThenStand())
                        .map(this::formatPosteLine)
                        .toList());
    }

    public byte[] exportAnimateurPdf(PlanningFestival planning, String animateurId) {
        List<String> lines = planning.getPostes().stream()
                .filter(poste -> poste.getAnimateur() != null && animateurId.equals(poste.getAnimateur().getId()))
                .sorted(byCreneauThenStand())
                .map(this::formatPosteLine)
                .toList();
        return buildPdf("Planning for " + animateurId, lines);
    }

    public String exportAnimateurIcs(PlanningFestival planning, String animateurId) {
        List<PosteAffectation> postes = planning.getPostes().stream()
                .filter(poste -> poste.getAnimateur() != null && animateurId.equals(poste.getAnimateur().getId()))
                .sorted(byCreneauThenStand())
                .toList();

        StringBuilder builder = new StringBuilder();
        builder.append("BEGIN:VCALENDAR\r\n")
                .append("VERSION:2.0\r\n")
                .append("PRODID:-//planning-equipes//planning//EN\r\n")
                .append("CALSCALE:GREGORIAN\r\n");

        for (PosteAffectation poste : postes) {
            String uid = poste.getId() + "@planning-equipes";
            builder.append("BEGIN:VEVENT\r\n")
                    .append("UID:").append(uid).append("\r\n")
                    .append("DTSTAMP:")
                    .append(poste.getCreneau().getDate().atStartOfDay().format(ICS_DATE_TIME))
                    .append("Z\r\n")
                    .append("DTSTART:")
                    .append(poste.getCreneau().getDate().atTime(poste.getCreneau().getHeureDebut()).format(ICS_DATE_TIME))
                    .append("\r\n")
                    .append("DTEND:")
                    .append(poste.getCreneau().getDate().atTime(poste.getCreneau().getHeureFin()).format(ICS_DATE_TIME))
                    .append("\r\n")
                    .append("SUMMARY:").append(escapeIcs(poste.getStand().getNom())).append("\r\n")
                    .append("DESCRIPTION:")
                    .append(escapeIcs("Stand " + poste.getStand().getNom() + " - slot " + poste.getCreneau().getId()))
                    .append("\r\n")
                    .append("END:VEVENT\r\n");
        }

        builder.append("END:VCALENDAR\r\n");
        return builder.toString();
    }

    public String resolveAnimateurName(PlanningFestival planning, String animateurId) {
        return planning.getAnimateurs().stream()
                .filter(animateur -> animateurId.equals(animateur.getId()))
                .findFirst()
                .map(this::toDisplayName)
                .orElse(animateurId);
    }

    private byte[] buildPdf(String title, List<String> lines) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Document document = new Document();
        PdfWriter.getInstance(document, output);
        document.open();
        document.add(new Paragraph(title));
        document.add(new Paragraph(" "));
        if (lines.isEmpty()) {
            document.add(new Paragraph("No assignments"));
        } else {
            for (String line : lines) {
                document.add(new Paragraph(line));
            }
        }
        document.close();
        return output.toByteArray();
    }

    private String formatPosteLine(PosteAffectation poste) {
        String animateur = poste.getAnimateur() == null ? "UNASSIGNED" : toDisplayName(poste.getAnimateur());
        return poste.getCreneau().getDate().format(DATE_FORMAT)
                + " "
                + poste.getCreneau().getHeureDebut().format(TIME_FORMAT)
                + "-"
                + poste.getCreneau().getHeureFin().format(TIME_FORMAT)
                + " | "
                + poste.getStand().getNom()
                + " | "
                + animateur;
    }

    private Comparator<PosteAffectation> byCreneauThenStand() {
        return Comparator
                .comparing((PosteAffectation poste) -> poste.getCreneau().getDate())
                .thenComparing(poste -> poste.getCreneau().getHeureDebut())
                .thenComparing(poste -> poste.getStand().getNom());
    }

    private String toDisplayName(Animateur animateur) {
        return List.of(animateur.getPrenom(), animateur.getNom()).stream()
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining(" "));
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
