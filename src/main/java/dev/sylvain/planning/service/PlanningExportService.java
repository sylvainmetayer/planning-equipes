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

import org.openpdf.text.Anchor;
import org.openpdf.text.Chunk;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfPageEventHelper;
import org.openpdf.text.pdf.PdfWriter;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
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
    private static final DateTimeFormatter GENERATED_AT_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    // --- Palette ---
    private static final java.awt.Color PRIMARY = new java.awt.Color(37, 99, 235);
    private static final java.awt.Color PRIMARY_DARK = new java.awt.Color(30, 58, 138);
    private static final java.awt.Color HEADER_TEXT = java.awt.Color.WHITE;
    private static final java.awt.Color BANNER_SUBTITLE = new java.awt.Color(191, 219, 254);
    private static final java.awt.Color ZEBRA = new java.awt.Color(240, 245, 253);
    private static final java.awt.Color BORDER = new java.awt.Color(203, 213, 225);
    private static final java.awt.Color TEXT = new java.awt.Color(31, 41, 55);
    private static final java.awt.Color MUTED = new java.awt.Color(107, 114, 128);

    /** Rotating accent colors for stand columns in the calendar grid, so stands stay visually distinct. */
    private static final java.awt.Color[] STAND_PALETTE = {
            new java.awt.Color(37, 99, 235),
            new java.awt.Color(5, 150, 105),
            new java.awt.Color(217, 119, 6),
            new java.awt.Color(219, 39, 119),
            new java.awt.Color(124, 58, 237),
            new java.awt.Color(8, 145, 178)
    };

    // --- Fonts ---
    private static final Font BANNER_TITLE_FONT = new Font(Font.HELVETICA, 20, Font.BOLD, HEADER_TEXT);
    private static final Font BANNER_SUBTITLE_FONT = new Font(Font.HELVETICA, 9.5f, Font.NORMAL, BANNER_SUBTITLE);
    private static final Font SECTION_FONT = new Font(Font.HELVETICA, 13, Font.BOLD, PRIMARY_DARK);
    private static final Font HEADER_CELL_FONT = new Font(Font.HELVETICA, 9, Font.BOLD, HEADER_TEXT);
    private static final Font CELL_FONT = new Font(Font.HELVETICA, 9, Font.NORMAL, TEXT);
    private static final Font TIMESLOT_FONT = new Font(Font.HELVETICA, 9, Font.BOLD, TEXT);
    private static final Font MUTED_CELL_FONT = new Font(Font.HELVETICA, 9, Font.NORMAL, MUTED);
    private static final Font EMPTY_STATE_FONT = new Font(Font.HELVETICA, 10, Font.ITALIC, MUTED);
    private static final Font FOOTER_FONT = new Font(Font.HELVETICA, 8, Font.NORMAL, MUTED);
    private static final Font LINK_FONT = new Font(Font.HELVETICA, 8, Font.UNDERLINE, PRIMARY);

    public byte[] exportAnimateurPdf(PlanningFestival planning, String animateurId) {
        List<PosteAffectation> animateurPostes = planning.getPostes().stream()
                .filter(poste -> poste.getAnimateur() != null && animateurId.equals(poste.getAnimateur().getId()))
                .sorted(byCreneauThenStand())
                .toList();
        return buildPdf(resolveAnimateurName(planning, animateurId), animateurPostes);
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
                id -> exportAnimateurIcs(planning, id).getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    /**
     * Both the PDF and the ICS of every animateur, bundled in a single ZIP so
     * the whole planning can be handed out through one download.
     */
    public byte[] exportAllBundleZip(PlanningFestival planning) {
        return buildZip(planning, List.of(
                new NamedFileBuilder(".pdf", id -> exportAnimateurPdf(planning, id)),
                new NamedFileBuilder(".ics",
                        id -> exportAnimateurIcs(planning, id).getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    /** Bundles one or more files per animateur, named after the animateur, into a ZIP. */
    private byte[] buildZip(PlanningFestival planning, List<NamedFileBuilder> fileBuilders) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            Set<String> usedFilenames = new LinkedHashSet<>();
            for (Animateur animateur : planning.getAnimateurs()) {
                String displayName = resolveAnimateurName(planning, animateur.getId());
                String baseName = (displayName == null || displayName.isBlank() ? animateur.getId() : displayName)
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

    private byte[] buildPdf(String animateurName, List<PosteAffectation> postes) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Document document = new Document(org.openpdf.text.PageSize.A4, 42, 42, 54, 54);
        PdfWriter writer = PdfWriter.getInstance(document, output);
        writer.setPageEvent(new FooterEvent());
        document.open();

        addHeader(document, animateurName, postes);

        addSectionTitle(document, "Calendar view");
        PdfPTable calendarTable = buildCalendarTable(postes);
        if (calendarTable == null) {
            document.add(emptyState());
        } else {
            document.add(calendarTable);
        }
        document.add(spacer());

        addSectionTitle(document, "Detailed list");
        PdfPTable detailTable = buildDetailTable(postes);
        if (detailTable == null) {
            document.add(emptyState());
        } else {
            document.add(detailTable);
        }

        document.close();
        return output.toByteArray();
    }

    private void addHeader(Document document, String animateurName, List<PosteAffectation> postes) {
        String assignmentLabel = postes.size() + (postes.size() > 1 ? " assignments" : " assignment");
        String statsLabel = distinctStandCount(postes) + " stands · " + distinctCreneauCount(postes) + " timeslots · "
                + assignmentLabel;
        String subtitleText = statsLabel + " · generated on "
                + GENERATED_AT_FORMAT.format(Instant.now().atZone(ZoneOffset.systemDefault()));

        PdfPTable banner = new PdfPTable(1);
        banner.setWidthPercentage(100);
        PdfPCell bannerCell = new PdfPCell();
        bannerCell.setBackgroundColor(PRIMARY_DARK);
        bannerCell.setBorder(Rectangle.NO_BORDER);
        bannerCell.setPadding(14f);

        Paragraph title = new Paragraph("Planning — " + animateurName, BANNER_TITLE_FONT);
        title.setSpacingAfter(4);
        Paragraph subtitle = new Paragraph(subtitleText, BANNER_SUBTITLE_FONT);
        bannerCell.addElement(title);
        bannerCell.addElement(subtitle);
        banner.addCell(bannerCell);
        banner.setSpacingAfter(18);
        document.add(banner);
    }

    private int distinctStandCount(List<PosteAffectation> postes) {
        Set<String> ids = new LinkedHashSet<>();
        for (PosteAffectation poste : postes) {
            if (poste.getStand() != null) {
                ids.add(poste.getStand().getId());
            }
        }
        return ids.size();
    }

    private int distinctCreneauCount(List<PosteAffectation> postes) {
        Set<String> ids = new LinkedHashSet<>();
        for (PosteAffectation poste : postes) {
            if (poste.getCreneau() != null) {
                ids.add(poste.getCreneau().getId());
            }
        }
        return ids.size();
    }

    private void addSectionTitle(Document document, String text) {
        Paragraph section = new Paragraph(text, SECTION_FONT);
        section.setSpacingAfter(8);
        document.add(section);
    }

    private Paragraph spacer() {
        Paragraph spacer = new Paragraph(" ");
        spacer.setSpacingAfter(10);
        return spacer;
    }

    private Paragraph emptyState() {
        Paragraph paragraph = new Paragraph("No assignments", EMPTY_STATE_FONT);
        paragraph.setSpacingAfter(10);
        return paragraph;
    }

    private PdfPTable buildDetailTable(List<PosteAffectation> postes) {
        if (postes.isEmpty()) {
            return null;
        }
        PdfPTable table = new PdfPTable(new float[] { 1.4f, 1.2f, 2.4f, 2f });
        table.setWidthPercentage(100);
        table.setSpacingBefore(2);
        table.addCell(headerCell("Date"));
        table.addCell(headerCell("Time"));
        table.addCell(headerCell("Stand"));
        table.addCell(headerCell("Animateur"));

        int row = 0;
        for (PosteAffectation poste : postes) {
            boolean zebra = row++ % 2 == 1;
            table.addCell(bodyCell(poste.getCreneau().getDate().format(DATE_FORMAT), CELL_FONT, zebra));
            table.addCell(bodyCell(
                    poste.getCreneau().getHeureDebut().format(TIME_FORMAT) + " - "
                            + poste.getCreneau().getHeureFin().format(TIME_FORMAT),
                    CELL_FONT, zebra));
            table.addCell(standCell(poste.getStand(), CELL_FONT, zebra));
            String animateur = poste.getAnimateur() == null ? "UNASSIGNED" : toDisplayName(poste.getAnimateur());
            table.addCell(bodyCell(animateur,
                    poste.getAnimateur() == null ? MUTED_CELL_FONT : CELL_FONT, zebra));
        }
        return table;
    }

    // Stand column headers stay plain text (headerCell only accepts a Phrase,
    // not a clickable Anchor); the detail table below already carries the
    // OpenStreetMap link for every stand via standCell().
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
        table.setSpacingBefore(2);
        table.getDefaultCell().setBorderColor(BORDER);
        table.addCell(headerCell("Timeslot", PRIMARY_DARK));
        for (int i = 0; i < stands.size(); i++) {
            table.addCell(headerCell(stands.get(i).getNom(), STAND_PALETTE[i % STAND_PALETTE.length]));
        }
        int row = 0;
        for (Creneau creneau : creneaux) {
            boolean zebra = row++ % 2 == 1;
            PdfPCell slotCell = bodyCell(
                    "J" + creneau.getJour() + " · " + creneau.getDate().format(DATE_FORMAT) + "\n"
                            + creneau.getHeureDebut().format(TIME_FORMAT) + " - "
                            + creneau.getHeureFin().format(TIME_FORMAT),
                    TIMESLOT_FONT, zebra);
            slotCell.setHorizontalAlignment(Element.ALIGN_LEFT);
            table.addCell(slotCell);
            for (Stand stand : stands) {
                List<String> names = assignmentsByKey.getOrDefault(creneau.getId() + "|" + stand.getId(), List.of());
                boolean empty = names.isEmpty();
                PdfPCell cell = bodyCell(empty ? "—" : String.join(", ", names),
                        empty ? MUTED_CELL_FONT : CELL_FONT, zebra);
                cell.setHorizontalAlignment(Element.ALIGN_CENTER);
                table.addCell(cell);
            }
        }
        return table;
    }

    private PdfPCell headerCell(String text) {
        return headerCell(text, PRIMARY);
    }

    private PdfPCell headerCell(String text, java.awt.Color background) {
        PdfPCell cell = new PdfPCell(new Phrase(text, HEADER_CELL_FONT));
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setBackgroundColor(background);
        cell.setBorderColor(background);
        cell.setBorderWidth(0.5f);
        cell.setPadding(6f);
        return cell;
    }

    private PdfPCell bodyCell(String text, Font font, boolean zebra) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setBorderColor(BORDER);
        cell.setBorderWidth(0.5f);
        cell.setPadding(5f);
        if (zebra) {
            cell.setBackgroundColor(ZEBRA);
        }
        return cell;
    }

    /** Stand name, plus a clickable OpenStreetMap link on a second line when the stand has a geocoded emplacement. */
    private PdfPCell standCell(Stand stand, Font font, boolean zebra) {
        PdfPCell cell = new PdfPCell();
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setBorderColor(BORDER);
        cell.setBorderWidth(0.5f);
        cell.setPadding(5f);
        if (zebra) {
            cell.setBackgroundColor(ZEBRA);
        }
        Paragraph paragraph = new Paragraph();
        paragraph.add(new Chunk(stand.getNom(), font));
        Emplacement emplacement = stand.getEmplacement();
        if (emplacement != null && emplacement.getLatitude() != null && emplacement.getLongitude() != null) {
            paragraph.add(Chunk.NEWLINE);
            Anchor link = new Anchor(
                    emplacement.getNom() != null && !emplacement.getNom().isBlank() ? emplacement.getNom() : "Map",
                    LINK_FONT);
            link.setReference(osmUrl(emplacement));
            paragraph.add(link);
        }
        cell.addElement(paragraph);
        return cell;
    }

    private String osmUrl(Emplacement emplacement) {
        double lat = emplacement.getLatitude();
        double lon = emplacement.getLongitude();
        return "https://www.openstreetmap.org/?mlat=" + lat + "&mlon=" + lon + "#map=18/" + lat + "/" + lon;
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

    /** Draws a thin rule and a centered "page X / Y" footer at the bottom of every page. */
    private static final class FooterEvent extends PdfPageEventHelper {
        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            Rectangle page = document.getPageSize();
            float y = document.bottomMargin() - 18;

            org.openpdf.text.pdf.PdfContentByte canvas = writer.getDirectContent();
            canvas.setColorStroke(BORDER);
            canvas.setLineWidth(0.5f);
            canvas.moveTo(document.leftMargin(), y + 12);
            canvas.lineTo(page.getWidth() - document.rightMargin(), y + 12);
            canvas.stroke();

            Phrase footer = new Phrase("planning-equipes", FOOTER_FONT);
            org.openpdf.text.pdf.ColumnText.showTextAligned(canvas, Element.ALIGN_LEFT,
                    footer, document.leftMargin(), y, 0);

            Phrase pageNumber = new Phrase("Page " + writer.getPageNumber(), FOOTER_FONT);
            org.openpdf.text.pdf.ColumnText.showTextAligned(canvas, Element.ALIGN_RIGHT,
                    pageNumber, page.getWidth() - document.rightMargin(), y, 0);
        }
    }
}
