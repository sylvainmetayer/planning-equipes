package dev.sylvain.planning.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.openpdf.text.Anchor;
import org.openpdf.text.Chunk;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.ColumnText;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPCellEvent;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfPTableEvent;
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
    private static final DateTimeFormatter FRENCH_DAY_DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH);
    private static final DateTimeFormatter ICS_UTC_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter ICS_LOCAL_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final DateTimeFormatter GENERATED_AT_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // --- Palette: warm paper-white cards, terracotta for morning slots, mauve for afternoon/evening ones ---
    private static final java.awt.Color HEADLINE = new java.awt.Color(35, 31, 29);
    private static final java.awt.Color MUTED = new java.awt.Color(120, 120, 122);
    private static final java.awt.Color BORDER = new java.awt.Color(226, 224, 221);
    private static final java.awt.Color CARD_BACKGROUND = java.awt.Color.WHITE;
    private static final java.awt.Color LABEL_MORNING = new java.awt.Color(178, 94, 36);
    private static final java.awt.Color LABEL_EVENING = new java.awt.Color(122, 91, 168);
    private static final java.awt.Color PILL_MORNING_BG = new java.awt.Color(252, 231, 205);
    private static final java.awt.Color PILL_EVENING_BG = new java.awt.Color(232, 227, 246);

    // --- Fonts: serif for names/dates/counters, sans for small caps labels and body text ---
    private static final Font BRAND_LABEL_FONT = new Font(Font.HELVETICA, 8.5f, Font.BOLD, LABEL_MORNING);
    private static final Font NAME_FONT = new Font(Font.TIMES_ROMAN, 23, Font.BOLD, HEADLINE);
    private static final Font STAT_NUMBER_FONT = new Font(Font.TIMES_ROMAN, 19, Font.BOLD, HEADLINE);
    private static final Font STAT_LABEL_FONT = new Font(Font.HELVETICA, 7.5f, Font.NORMAL, MUTED);
    private static final Font DATE_FONT = new Font(Font.TIMES_ROMAN, 13, Font.BOLD, HEADLINE);
    private static final Font STAND_FONT = new Font(Font.HELVETICA, 10.5f, Font.BOLD, HEADLINE);
    private static final Font LOCATION_FONT = new Font(Font.HELVETICA, 9, Font.NORMAL, MUTED);
    private static final Font EMPTY_STATE_FONT = new Font(Font.HELVETICA, 10, Font.ITALIC, MUTED);
    private static final Font FOOTER_FONT = new Font(Font.HELVETICA, 8, Font.NORMAL, MUTED);

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
        Document document = new Document(PageSize.A4.rotate(), 40, 40, 36, 40);
        PdfWriter writer = PdfWriter.getInstance(document, output);
        writer.setPageEvent(new FooterEvent());
        document.open();

        addHeader(document, animateurName, postes);

        if (postes.isEmpty()) {
            document.add(emptyState());
        } else {
            for (PosteAffectation poste : postes) {
                document.add(buildAssignmentCard(poste));
            }
        }

        document.close();
        return output.toByteArray();
    }

    private void addHeader(Document document, String animateurName, List<PosteAffectation> postes) {
        PdfPTable header = new PdfPTable(new float[] { 3f, 2f });
        header.setWidthPercentage(100);

        PdfPCell titleCell = new PdfPCell();
        titleCell.setBorder(Rectangle.NO_BORDER);
        titleCell.setVerticalAlignment(Element.ALIGN_BOTTOM);
        Paragraph brandLabel = new Paragraph();
        Chunk brandChunk = new Chunk("PLANNING BÉNÉVOLE", BRAND_LABEL_FONT);
        brandChunk.setCharacterSpacing(1.4f);
        brandLabel.add(brandChunk);
        brandLabel.setSpacingAfter(3f);
        Paragraph name = new Paragraph(animateurName, NAME_FONT);
        titleCell.addElement(brandLabel);
        titleCell.addElement(name);
        header.addCell(titleCell);

        PdfPCell statsCell = new PdfPCell();
        statsCell.setBorder(Rectangle.NO_BORDER);
        statsCell.setVerticalAlignment(Element.ALIGN_BOTTOM);
        statsCell.addElement(statBlock(postes));
        header.addCell(statsCell);

        header.setSpacingAfter(24f);
        document.add(header);
    }

    private PdfPTable statBlock(List<PosteAffectation> postes) {
        PdfPTable table = new PdfPTable(3);
        table.setWidthPercentage(100);
        table.addCell(statCell(distinctCreneauCount(postes), "CRÉNEAUX", false));
        table.addCell(statCell(distinctStandCount(postes), "STANDS", true));
        table.addCell(statCell(distinctDayCount(postes), "JOURS", true));
        return table;
    }

    private PdfPCell statCell(int value, String label, boolean divider) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(divider ? Rectangle.LEFT : Rectangle.NO_BORDER);
        cell.setBorderColor(BORDER);
        cell.setBorderWidthLeft(0.75f);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPadding(4f);

        Paragraph number = new Paragraph(String.valueOf(value), STAT_NUMBER_FONT);
        number.setAlignment(Element.ALIGN_CENTER);
        number.setSpacingAfter(1f);

        Paragraph labelParagraph = new Paragraph();
        labelParagraph.setAlignment(Element.ALIGN_CENTER);
        Chunk labelChunk = new Chunk(label, STAT_LABEL_FONT);
        labelChunk.setCharacterSpacing(1.1f);
        labelParagraph.add(labelChunk);

        cell.addElement(number);
        cell.addElement(labelParagraph);
        return cell;
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
        Set<Long> ids = new LinkedHashSet<>();
        for (PosteAffectation poste : postes) {
            if (poste.getCreneau() != null) {
                ids.add(poste.getCreneau().getId());
            }
        }
        return ids.size();
    }

    private int distinctDayCount(List<PosteAffectation> postes) {
        Set<Integer> jours = new LinkedHashSet<>();
        for (PosteAffectation poste : postes) {
            if (poste.getCreneau() != null) {
                jours.add(poste.getCreneau().getJour());
            }
        }
        return jours.size();
    }

    private Paragraph emptyState() {
        Paragraph paragraph = new Paragraph("Aucune affectation pour ce festival.", EMPTY_STATE_FONT);
        paragraph.setAlignment(Element.ALIGN_CENTER);
        paragraph.setSpacingBefore(24f);
        return paragraph;
    }

    /** One rounded card per assignment: day/date, a time-of-day pill, the stand and its location. */
    private PdfPTable buildAssignmentCard(PosteAffectation poste) {
        Creneau creneau = poste.getCreneau();
        boolean morning = creneau.getHeureDebut().getHour() < 12;
        java.awt.Color accent = morning ? LABEL_MORNING : LABEL_EVENING;
        java.awt.Color pillBackground = morning ? PILL_MORNING_BG : PILL_EVENING_BG;

        PdfPTable card = new PdfPTable(new float[] { 2.4f, 1.6f, 2.6f, 2.3f });
        card.setWidthPercentage(100);
        card.setSpacingAfter(9f);
        card.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        card.setTableEvent(new RoundedBackgroundEvent(CARD_BACKGROUND, BORDER, 8f));

        card.addCell(dayCell(creneau, accent));
        card.addCell(timePillCell(creneau, accent, pillBackground));
        card.addCell(standCell(poste.getStand()));
        card.addCell(locationCell(poste.getStand()));
        return card;
    }

    private PdfPCell dayCell(Creneau creneau, java.awt.Color accent) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPadding(14f);

        Paragraph label = new Paragraph();
        Chunk labelChunk = new Chunk("JOUR " + creneau.getJour(), new Font(Font.HELVETICA, 8f, Font.BOLD, accent));
        labelChunk.setCharacterSpacing(1.2f);
        label.add(labelChunk);
        label.setSpacingAfter(3f);

        Paragraph date = new Paragraph(formatFrenchDayDate(creneau.getDate()), DATE_FONT);

        cell.addElement(label);
        cell.addElement(date);
        return cell;
    }

    private PdfPCell timePillCell(Creneau creneau, java.awt.Color textColor, java.awt.Color background) {
        String text = creneau.getHeureDebut().format(TIME_FORMAT) + " - " + creneau.getHeureFin().format(TIME_FORMAT);
        Font pillFont = new Font(Font.HELVETICA, 8.5f, Font.BOLD, textColor);
        float textWidth = pillFont.getCalculatedBaseFont(false).getWidthPoint(text, pillFont.getCalculatedSize());
        float pillWidth = textWidth + 20f;
        float pillHeight = 20f;

        PdfPCell cell = new PdfPCell(new Phrase(text, pillFont));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setPadding(14f);
        cell.setCellEvent(new PillBackgroundEvent(background, pillWidth, pillHeight));
        return cell;
    }

    private PdfPCell standCell(Stand stand) {
        PdfPCell cell = new PdfPCell(new Phrase("Stand " + stand.getNom(), STAND_FONT));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPadding(14f);
        return cell;
    }

    /** Location name, kept as a clickable OpenStreetMap link when the stand's emplacement is geocoded. */
    private PdfPCell locationCell(Stand stand) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        cell.setPadding(14f);

        Paragraph paragraph = new Paragraph();
        paragraph.setAlignment(Element.ALIGN_RIGHT);
        Emplacement emplacement = stand.getEmplacement();
        if (emplacement != null && emplacement.getNom() != null && !emplacement.getNom().isBlank()) {
            if (emplacement.getLatitude() != null && emplacement.getLongitude() != null) {
                Anchor link = new Anchor(emplacement.getNom(), LOCATION_FONT);
                link.setReference(osmUrl(emplacement));
                paragraph.add(link);
            } else {
                paragraph.add(new Chunk(emplacement.getNom(), LOCATION_FONT));
            }
        }
        cell.addElement(paragraph);
        return cell;
    }

    private String osmUrl(Emplacement emplacement) {
        double lat = emplacement.getLatitude();
        double lon = emplacement.getLongitude();
        return "https://www.openstreetmap.org/?mlat=" + lat + "&mlon=" + lon + "#map=18/" + lat + "/" + lon;
    }

    private String formatFrenchDayDate(LocalDate date) {
        String raw = FRENCH_DAY_DATE_FORMAT.format(date);
        return raw.substring(0, 1).toUpperCase(Locale.FRENCH) + raw.substring(1);
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

    /** Draws a single seamless rounded rectangle behind a whole (single-row) table, used for cards and pills. */
    private static final class RoundedBackgroundEvent implements PdfPTableEvent {
        private final java.awt.Color fill;
        private final java.awt.Color border;
        private final float radius;

        RoundedBackgroundEvent(java.awt.Color fill, java.awt.Color border, float radius) {
            this.fill = fill;
            this.border = border;
            this.radius = radius;
        }

        @Override
        public void tableLayout(PdfPTable table, float[][] widths, float[] heights, int headerRows, int rowStart,
                PdfContentByte[] canvases) {
            float left = widths[0][0];
            float right = widths[0][widths[0].length - 1];
            float top = heights[0];
            float bottom = heights[heights.length - 1];

            // Painted on BASECANVAS rather than BACKGROUNDCANVAS: table-level backgrounds are drawn
            // after each row's cells, so using the same canvas as a cell event (e.g. the time pill)
            // would paint over it. BASECANVAS sits one layer below and is unaffected by draw order.
            PdfContentByte background = canvases[PdfPTable.BASECANVAS];
            background.saveState();
            background.setColorFill(fill);
            background.roundRectangle(left, bottom, right - left, top - bottom, radius);
            background.fill();
            background.restoreState();

            if (border != null) {
                PdfContentByte line = canvases[PdfPTable.LINECANVAS];
                line.saveState();
                line.setColorStroke(border);
                line.setLineWidth(0.75f);
                line.roundRectangle(left + 0.4f, bottom + 0.4f, right - left - 0.8f, top - bottom - 0.8f, radius);
                line.stroke();
                line.restoreState();
            }
        }
    }

    /** Draws a pill-shaped background centered within a cell, sized to fit the given content box exactly. */
    private static final class PillBackgroundEvent implements PdfPCellEvent {
        private final java.awt.Color fill;
        private final float width;
        private final float height;

        PillBackgroundEvent(java.awt.Color fill, float width, float height) {
            this.fill = fill;
            this.width = width;
            this.height = height;
        }

        @Override
        public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
            float left = position.getLeft() + (position.getWidth() - width) / 2f;
            float bottom = position.getBottom() + (position.getHeight() - height) / 2f;

            PdfContentByte background = canvases[PdfPTable.BACKGROUNDCANVAS];
            background.saveState();
            background.setColorFill(fill);
            background.roundRectangle(left, bottom, width, height, height / 2f);
            background.fill();
            background.restoreState();
        }
    }

    /** Draws a thin rule and a "planning-equipes · généré le ..." / "Page X" footer at the bottom of every page. */
    private static final class FooterEvent extends PdfPageEventHelper {
        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            Rectangle page = document.getPageSize();
            float y = document.bottomMargin() - 18;

            PdfContentByte canvas = writer.getDirectContent();
            canvas.setColorStroke(BORDER);
            canvas.setLineWidth(0.5f);
            canvas.moveTo(document.leftMargin(), y + 12);
            canvas.lineTo(page.getWidth() - document.rightMargin(), y + 12);
            canvas.stroke();

            String generatedAt = "planning-equipes · généré le "
                    + GENERATED_AT_FORMAT.format(Instant.now().atZone(ZoneOffset.systemDefault()));
            Phrase footer = new Phrase(generatedAt, FOOTER_FONT);
            ColumnText.showTextAligned(canvas, Element.ALIGN_LEFT, footer, document.leftMargin(), y, 0);

            Phrase pageNumber = new Phrase("Page " + writer.getPageNumber(), FOOTER_FONT);
            ColumnText.showTextAligned(canvas, Element.ALIGN_RIGHT, pageNumber, page.getWidth() - document.rightMargin(),
                    y, 0);
        }
    }
}
