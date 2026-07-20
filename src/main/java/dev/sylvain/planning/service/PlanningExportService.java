package dev.sylvain.planning.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PlanningExportService {

    private static final String FESTIVAL_TIMEZONE = "Europe/Paris";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter ICS_UTC_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter ICS_LOCAL_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

    public byte[] exportGlobalPdf(PlanningFestival planning) {
        List<String> lines = planning.getPostes().stream()
                .sorted(byCreneauThenStand())
                .map(this::formatPosteLine)
                .toList();
        return buildPdf("Global planning", lines, planning.getPostes());
    }

    public byte[] exportAnimateurPdf(PlanningFestival planning, String animateurId) {
        List<PosteAffectation> animateurPostes = planning.getPostes().stream()
                .filter(poste -> poste.getAnimateur() != null && animateurId.equals(poste.getAnimateur().getId()))
                .sorted(byCreneauThenStand())
                .toList();
        List<String> lines = animateurPostes.stream().map(this::formatPosteLine).toList();
        return buildPdf("Planning for " + animateurId, lines, animateurPostes);
    }

    public byte[] exportAllIcsZip(PlanningFestival planning) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            Set<String> usedFilenames = new LinkedHashSet<>();
            for (Animateur animateur : planning.getAnimateurs()) {
                String ics = exportAnimateurIcs(planning, animateur.getId());
                String displayName = resolveAnimateurName(planning, animateur.getId());
                String baseName = (displayName == null || displayName.isBlank() ? animateur.getId() : displayName)
                        .replaceAll("[\\\\/\\r\\n\\\"]", "_");
                String filename = baseName + ".ics";
                int suffix = 2;
                while (!usedFilenames.add(filename)) {
                    filename = baseName + "-" + suffix + ".ics";
                    suffix++;
                }
                zip.putNextEntry(new ZipEntry(filename));
                zip.write(ics.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new RuntimeException("Unable to build ICS ZIP export", e);
        }
        return output.toByteArray();
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
                    .append("DTSTAMP:").append(ICS_UTC_DATE_TIME.format(Instant.now())).append("\r\n")
                    .append("DTSTART;TZID=").append(FESTIVAL_TIMEZONE).append(":")
                    .append(poste.getCreneau().getDate().atTime(poste.getCreneau().getHeureDebut())
                            .format(ICS_LOCAL_DATE_TIME))
                    .append("\r\n")
                    .append("DTEND;TZID=").append(FESTIVAL_TIMEZONE).append(":")
                    .append(poste.getCreneau().getDate().atTime(poste.getCreneau().getHeureFin())
                            .format(ICS_LOCAL_DATE_TIME))
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

    private byte[] buildPdf(String title, List<String> lines, List<PosteAffectation> postes) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Document document = new Document();
        PdfWriter.getInstance(document, output);
        document.open();
        document.add(new Paragraph(title));
        document.add(new Paragraph(" "));

        document.add(new Paragraph("Calendar view", new Font(Font.HELVETICA, 12, Font.BOLD)));
        document.add(new Paragraph(" "));
        PdfPTable calendarTable = buildCalendarTable(postes);
        if (calendarTable == null) {
            document.add(new Paragraph("No assignments"));
        } else {
            document.add(calendarTable);
        }
        document.add(new Paragraph(" "));

        document.add(new Paragraph("Detailed list", new Font(Font.HELVETICA, 12, Font.BOLD)));
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

    private PdfPTable buildCalendarTable(List<PosteAffectation> postes) {
        Map<String, Creneau> creneauxById = new LinkedHashMap<>();
        Map<String, Stand> standsById = new LinkedHashMap<>();
        for (PosteAffectation poste : postes) {
            if (poste.getCreneau() != null) {
                creneauxById.put(poste.getCreneau().getId(), poste.getCreneau());
            }
            if (poste.getStand() != null) {
                standsById.put(poste.getStand().getId(), poste.getStand());
            }
        }
        if (creneauxById.isEmpty() || standsById.isEmpty()) {
            return null;
        }

        List<Creneau> creneaux = new ArrayList<>(creneauxById.values());
        creneaux.sort(Comparator.comparing(Creneau::getDate).thenComparing(Creneau::getHeureDebut));
        List<Stand> stands = new ArrayList<>(standsById.values());
        stands.sort(Comparator.comparing(Stand::getNom));

        Map<String, List<String>> assignmentsByKey = new LinkedHashMap<>();
        for (PosteAffectation poste : postes) {
            if (poste.getCreneau() == null || poste.getStand() == null) {
                continue;
            }
            String key = poste.getCreneau().getId() + "|" + poste.getStand().getId();
            List<String> names = assignmentsByKey.computeIfAbsent(key, k -> new ArrayList<>());
            if (poste.getAnimateur() != null) {
                names.add(toDisplayName(poste.getAnimateur()));
            }
        }

        PdfPTable table = new PdfPTable(stands.size() + 1);
        table.setWidthPercentage(100);
        table.addCell(headerCell("Timeslot"));
        for (Stand stand : stands) {
            table.addCell(headerCell(stand.getNom()));
        }
        for (Creneau creneau : creneaux) {
            table.addCell(new PdfPCell(new com.lowagie.text.Phrase(
                    "J" + creneau.getJour() + " " + creneau.getDate().format(DATE_FORMAT) + " "
                            + creneau.getHeureDebut().format(TIME_FORMAT) + "-"
                            + creneau.getHeureFin().format(TIME_FORMAT))));
            for (Stand stand : stands) {
                List<String> names = assignmentsByKey.getOrDefault(creneau.getId() + "|" + stand.getId(), List.of());
                String text = names.isEmpty() ? "-" : String.join(", ", names);
                table.addCell(new PdfPCell(new com.lowagie.text.Phrase(text)));
            }
        }
        return table;
    }

    private PdfPCell headerCell(String text) {
        PdfPCell cell = new PdfPCell(new com.lowagie.text.Phrase(text, new Font(Font.HELVETICA, 10, Font.BOLD)));
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setBorderWidth(1);
        cell.setBackgroundColor(new java.awt.Color(240, 240, 240));
        return cell;
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
