package dev.sylvain.planning.service;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.openpdf.text.Chunk;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.Image;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.ColumnText;
import org.openpdf.text.pdf.PdfAction;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPCellEvent;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfPTableEvent;
import org.openpdf.text.pdf.PdfPageEventHelper;
import org.openpdf.text.pdf.PdfTemplate;
import org.openpdf.text.pdf.PdfWriter;

/**
 * The visual identity of the PDFs: the palette, the fonts, the logo, the
 * hand-drawn icons and the cell events that draw the rounded corners.
 *
 * <p>Kept apart from the three documents because it changes for other reasons
 * than they do: a colour or a font weight moves when the theme moves, never
 * when the way of planning changes. The global PDF and the individual one both
 * use it — which is what makes them look alike.</p>
 */
final class PdfTheme {

    private PdfTheme() {
    }

    static final String FESTIVAL_TIMEZONE = "Europe/Paris";
    static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    static final DateTimeFormatter FRENCH_DAY_DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH);
    static final DateTimeFormatter GENERATED_AT_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH:mm");

    static final String LOGO_RESOURCE = "/branding/logo.png";
    static final String STRIP_RESOURCE = "/branding/strip.png";

    // --- Palette, sampled from the brand mark: crimson red, golden yellow, warm dark ink ---
    static final Color HEADLINE = new Color(43, 33, 24);
    static final Color MUTED = new Color(146, 121, 87);
    static final Color RED = new Color(200, 29, 37);
    static final Color YELLOW = new Color(255, 214, 62);
    static final Color CARD_BACKGROUND = Color.WHITE;
    static final Color PILL_BACKGROUND = new Color(250, 235, 208);

    // --- Fonts: bold rounded sans for headline figures, plain sans for supporting text ---
    static final Font BRAND_LABEL_FONT = new Font(Font.HELVETICA, 8.5f, Font.BOLD, RED);
    static final Font NAME_FONT = new Font(Font.HELVETICA, 24, Font.BOLD, HEADLINE);
    static final Font STAT_NUMBER_FONT = new Font(Font.HELVETICA, 19, Font.BOLD, HEADLINE);
    static final Font STAT_LABEL_FONT = new Font(Font.HELVETICA, 7.5f, Font.BOLD, HEADLINE);
    static final Font STAT_SUBLABEL_FONT = new Font(Font.HELVETICA, 6.5f, Font.BOLD, MUTED);
    static final Font DATE_FONT = new Font(Font.HELVETICA, 13, Font.BOLD, HEADLINE);
    static final Font CALLOUT_TITLE_FONT = new Font(Font.HELVETICA, 8.5f, Font.BOLD, RED);
    static final Font CALLOUT_TEXT_FONT = new Font(Font.HELVETICA, 10.5f, Font.NORMAL, HEADLINE);
    static final Font BADGE_FONT = new Font(Font.HELVETICA, 7.5f, Font.BOLD, Color.WHITE);
    static final Font TIME_FONT = new Font(Font.HELVETICA, 8.5f, Font.BOLD, MUTED);
    static final Font STAND_FONT = new Font(Font.HELVETICA, 10.5f, Font.BOLD, HEADLINE);
    static final Font LOCATION_FONT = new Font(Font.HELVETICA, 9, Font.NORMAL, MUTED);
    /** Teammates line under the stand name: present but secondary to the stand itself. */
    static final Font TEAM_FONT = new Font(Font.HELVETICA, 9, Font.ITALIC, MUTED);
    static final Font EMPTY_STATE_FONT = new Font(Font.HELVETICA, 10, Font.ITALIC, MUTED);
    static final Font FOOTER_FONT = new Font(Font.HELVETICA, 8, Font.NORMAL, MUTED);
    // --- Global (organiser) export: dense tables rather than per-seat cards ---
    static final Font TABLE_HEADER_FONT = new Font(Font.HELVETICA, 8, Font.BOLD, HEADLINE);
    static final Font TABLE_BODY_FONT = new Font(Font.HELVETICA, 8, Font.NORMAL, HEADLINE);
    static final Font TABLE_ALERT_FONT = new Font(Font.HELVETICA, 8, Font.BOLD, RED);

    /**
     * Branded page header shared by the global and per-animateur PDFs: the
     * FESTIVAL logo, a spaced small-caps brand label and the page's title —
     * only the title-column width, the texts and the bottom spacing differ.
     */
    static PdfPTable brandHeader(Document document, float titleWidth, String brandText, String title,
            float spacingAfter) {
        Image logo = loadImage(LOGO_RESOURCE);
        logo.scaleToFit(46f, 46f);

        PdfPTable header = new PdfPTable(new float[] { 46f, titleWidth });
        header.setTotalWidth(document.getPageSize().getWidth() - document.leftMargin() - document.rightMargin());
        header.setLockedWidth(true);

        PdfPCell logoCell = new PdfPCell(logo, false);
        logoCell.setBorder(Rectangle.NO_BORDER);
        logoCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        logoCell.setPadding(0f);
        header.addCell(logoCell);

        PdfPCell titleCell = new PdfPCell();
        titleCell.setBorder(Rectangle.NO_BORDER);
        titleCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        titleCell.setPaddingLeft(14f);
        Paragraph brandLabel = new Paragraph();
        Chunk brandChunk = new Chunk(brandText, BRAND_LABEL_FONT);
        brandChunk.setCharacterSpacing(1.4f);
        brandLabel.add(brandChunk);
        brandLabel.setSpacingAfter(3f);
        titleCell.addElement(brandLabel);
        titleCell.addElement(new Paragraph(title, NAME_FONT));
        header.addCell(titleCell);
        header.setSpacingAfter(spacingAfter);
        return header;
    }

    static Paragraph emptyState() {
        Paragraph paragraph = new Paragraph("Aucune affectation pour ce festival.", EMPTY_STATE_FONT);
        paragraph.setAlignment(Element.ALIGN_CENTER);
        paragraph.setSpacingBefore(24f);
        return paragraph;
    }

    static String formatFrenchDayDate(LocalDate date) {
        String raw = FRENCH_DAY_DATE_FORMAT.format(date);
        return raw.substring(0, 1).toUpperCase(Locale.FRENCH) + raw.substring(1);
    }

    /** Loads a PNG bundled under {@code src/main/resources} (not the webui's own public/ folder, which isn't on the Java classpath). */
    static Image loadImage(String resourcePath) {
        try (InputStream in = PdfTheme.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Missing classpath resource: " + resourcePath);
            }
            return Image.getInstance(in.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException("Unable to load image " + resourcePath, e);
        }
    }

    /** Draws a small clock face (circle + two hands) used ahead of a time-slot pill's text. */
    static void drawClockIcon(PdfContentByte canvas, float centerX, float centerY, float radius, Color color) {
        canvas.saveState();
        canvas.setColorStroke(color);
        canvas.setLineWidth(0.8f);
        canvas.circle(centerX, centerY, radius);
        canvas.stroke();
        canvas.moveTo(centerX, centerY);
        canvas.lineTo(centerX, centerY + radius * 0.55f);
        canvas.moveTo(centerX, centerY);
        canvas.lineTo(centerX + radius * 0.5f, centerY - radius * 0.15f);
        canvas.stroke();
        canvas.restoreState();
    }

    /** Draws a small outlined map-pin (circle head, pointed tail, center dot) used ahead of a location's text. */
    static void drawPinIcon(PdfContentByte canvas, float centerX, float centerY, float radius, Color color) {
        float headCenterY = centerY + radius * 0.55f;
        canvas.saveState();
        canvas.setColorStroke(color);
        canvas.setLineWidth(0.8f);
        canvas.circle(centerX, headCenterY, radius);
        canvas.stroke();
        canvas.moveTo(centerX - radius * 0.75f, headCenterY - radius * 0.6f);
        canvas.lineTo(centerX, headCenterY - radius * 2.1f);
        canvas.lineTo(centerX + radius * 0.75f, headCenterY - radius * 0.6f);
        canvas.stroke();
        canvas.setColorFill(color);
        canvas.circle(centerX, headCenterY, radius * 0.28f);
        canvas.fill();
        canvas.restoreState();
    }

    /** Fills a cell's own box with a rounded rectangle, used for stat tiles and pill badges. */
    static final class RoundedCellFillEvent implements PdfPCellEvent {
        private final Color fill;
        private final float radius;

        RoundedCellFillEvent(Color fill, float radius) {
            this.fill = fill;
            this.radius = radius;
        }

        @Override
        public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
            PdfContentByte background = canvases[PdfPTable.BACKGROUNDCANVAS];
            background.saveState();
            background.setColorFill(fill);
            background.roundRectangle(position.getLeft(), position.getBottom(), position.getWidth(), position.getHeight(),
                    radius);
            background.fill();
            background.restoreState();
        }
    }

    /** Draws a centered pill (background + clock icon + text) sized to its own content, ignoring the cell's own padding. */
    static final class TimePillEvent implements PdfPCellEvent {
        private final String text;
        private final Color background;
        private final Color contentColor;
        private final float pillWidth;
        private final float pillHeight;
        private final float iconDiameter;
        private final float iconGap;

        TimePillEvent(String text, Color background, Color contentColor, float pillWidth,
                float pillHeight, float iconDiameter, float iconGap) {
            this.text = text;
            this.background = background;
            this.contentColor = contentColor;
            this.pillWidth = pillWidth;
            this.pillHeight = pillHeight;
            this.iconDiameter = iconDiameter;
            this.iconGap = iconGap;
        }

        @Override
        public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
            float left = position.getLeft() + (position.getWidth() - pillWidth) / 2f;
            float bottom = position.getBottom() + (position.getHeight() - pillHeight) / 2f;
            float centerY = bottom + pillHeight / 2f;

            PdfContentByte background2 = canvases[PdfPTable.BACKGROUNDCANVAS];
            background2.saveState();
            background2.setColorFill(background);
            background2.roundRectangle(left, bottom, pillWidth, pillHeight, pillHeight / 2f);
            background2.fill();
            background2.restoreState();

            BaseFont baseFont = TIME_FONT.getCalculatedBaseFont(false);
            float textWidth = baseFont.getWidthPoint(text, TIME_FONT.getCalculatedSize());
            float contentLeft = left + (pillWidth - (iconDiameter + iconGap + textWidth)) / 2f;

            PdfContentByte line = canvases[PdfPTable.LINECANVAS];
            drawClockIcon(line, contentLeft + iconDiameter / 2f, centerY, iconDiameter / 2f, contentColor);

            PdfContentByte textCanvas = canvases[PdfPTable.TEXTCANVAS];
            textCanvas.saveState();
            textCanvas.beginText();
            textCanvas.setFontAndSize(baseFont, TIME_FONT.getCalculatedSize());
            textCanvas.setColorFill(contentColor);
            textCanvas.setTextMatrix(contentLeft + iconDiameter + iconGap, centerY - TIME_FONT.getCalculatedSize() * 0.35f);
            textCanvas.showText(text);
            textCanvas.endText();
            textCanvas.restoreState();
        }
    }

    /** Draws a right-aligned pin icon + location name as a single unit, clickable when a URL is supplied. */
    static final class LocationPinEvent implements PdfPCellEvent {
        private final String text;
        private final Font font;
        private final Color color;
        private final float iconDiameter;
        private final float iconGap;
        private final String url;

        LocationPinEvent(String text, Font font, Color color, float iconDiameter, float iconGap, String url) {
            this.text = text;
            this.font = font;
            this.color = color;
            this.iconDiameter = iconDiameter;
            this.iconGap = iconGap;
            this.url = url;
        }

        @Override
        public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
            BaseFont baseFont = font.getCalculatedBaseFont(false);
            float textWidth = baseFont.getWidthPoint(text, font.getCalculatedSize());
            float right = position.getRight() - 14f;
            float centerY = position.getBottom() + position.getHeight() / 2f;
            float textLeft = right - textWidth;
            float iconCenterX = textLeft - iconGap - iconDiameter / 2f;

            PdfContentByte line = canvases[PdfPTable.LINECANVAS];
            drawPinIcon(line, iconCenterX, centerY, iconDiameter / 2f, color);

            PdfContentByte textCanvas = canvases[PdfPTable.TEXTCANVAS];
            textCanvas.saveState();
            textCanvas.beginText();
            textCanvas.setFontAndSize(baseFont, font.getCalculatedSize());
            textCanvas.setColorFill(color);
            textCanvas.setTextMatrix(textLeft, centerY - font.getCalculatedSize() * 0.35f);
            textCanvas.showText(text);
            textCanvas.endText();
            textCanvas.restoreState();

            if (url != null) {
                float left = iconCenterX - iconDiameter / 2f - 2f;
                textCanvas.setAction(new PdfAction(url), left, position.getBottom(), right + 2f, position.getTop());
            }
        }
    }

    /** Draws a single seamless rounded rectangle behind a whole (single-row) table, used for the assignment cards. */
    static final class RoundedBackgroundEvent implements PdfPTableEvent {
        private final Color fill;
        private final Color border;
        private final float radius;

        RoundedBackgroundEvent(Color fill, Color border, float radius) {
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
                line.setLineWidth(1f);
                line.roundRectangle(left + 0.5f, bottom + 0.5f, right - left - 1f, top - bottom - 1f, radius);
                line.stroke();
                line.restoreState();
            }
        }
    }

    /** Draws the "généré le ..." footer and a "Page x/y" counter, back-filled once the total page count is known. */
    static final class FooterEvent extends PdfPageEventHelper {
        private final String generatedAtText;
        private final List<PdfTemplate> pageCounterTemplates = new ArrayList<>();

        FooterEvent(String generatedAtText) {
            this.generatedAtText = generatedAtText;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            Rectangle page = document.getPageSize();
            float y = document.bottomMargin() - 18;

            PdfContentByte canvas = writer.getDirectContent();
            Phrase generated = new Phrase(generatedAtText, FOOTER_FONT);
            ColumnText.showTextAligned(canvas, Element.ALIGN_LEFT, generated, document.leftMargin(), y, 0);

            float templateWidth = 70f;
            PdfTemplate template = canvas.createTemplate(templateWidth, 12f);
            canvas.addTemplate(template, page.getWidth() - document.rightMargin() - templateWidth, y - 3f);
            pageCounterTemplates.add(template);
        }

        @Override
        public void onCloseDocument(PdfWriter writer, Document document) {
            // One template was stacked per page actually written; the writer's
            // own counter is already sitting on the next, not-yet-written page,
            // which is what made every document read "Page 1/2" at one page.
            int totalPages = pageCounterTemplates.size();
            BaseFont baseFont = FOOTER_FONT.getCalculatedBaseFont(false);
            for (int i = 0; i < pageCounterTemplates.size(); i++) {
                PdfTemplate template = pageCounterTemplates.get(i);
                String text = "Page " + (i + 1) + "/" + totalPages;
                template.beginText();
                template.setFontAndSize(baseFont, FOOTER_FONT.getSize());
                template.setColorFill(MUTED);
                template.showTextAligned(Element.ALIGN_RIGHT, text, 70f, 3f, 0);
                template.endText();
            }
        }
    }
}
