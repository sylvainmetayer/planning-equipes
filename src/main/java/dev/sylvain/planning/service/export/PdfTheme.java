package dev.sylvain.planning.service.export;

import dev.sylvain.planning.config.ConfigBranding;
import dev.sylvain.planning.service.ProductName;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * The visual identity of the PDFs: the palette, the fonts, the images and the
 * cell events that draw the rounded corners.
 *
 * <p>Kept apart from the three documents because it changes for other reasons
 * than they do: a colour or a font weight moves when the theme moves, never
 * when the way of planning changes. The global PDF and the individual one both
 * use it — which is what makes them look alike.</p>
 *
 * <p>It is a bean rather than a bag of constants because the theme belongs to
 * the <b>deployment</b>: one instance per customer, each with its own name,
 * images and colours ({@link ConfigBranding}). What does not depend on the
 * customer — the date formats, the icon drawing, the layout events — stays
 * static.</p>
 */
@ApplicationScoped
public class PdfTheme {

    static final String EVENT_TIMEZONE = "Europe/Paris";
    static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    static final DateTimeFormatter FRENCH_DAY_DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH);
    static final DateTimeFormatter GENERATED_AT_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH:mm");

    /** Prefix marking an image bundled in the application rather than mounted next to it. */
    private static final String CLASSPATH_PREFIX = "classpath:";

    private final String productName;
    private final String organisation;
    private final String logoResource;
    private final String stripResource;

    // --- Palette: five colours, all deployment-configurable, neutral grey-blue by default ---
    private final Color headline;
    private final Color muted;
    private final Color accent;
    private final Color highlight;
    private final Color pill;
    private final Color cardBackground = Color.WHITE;

    // --- Fonts: bold sans for headline figures, plain sans for supporting text ---
    private final Font brandLabelFont;
    private final Font nameFont;
    private final Font statNumberFont;
    private final Font statLabelFont;
    private final Font statSubLabelFont;
    private final Font dateFont;
    private final Font calloutTitleFont;
    private final Font calloutTextFont;
    private final Font badgeFont;
    private final Font timeFont;
    private final Font standFont;
    private final Font locationFont;
    /** Teammates line under the stand name: present but secondary to the stand itself. */
    private final Font teamFont;

    private final Font emptyStateFont;
    private final Font footerFont;
    // --- Global (organiser) export: dense tables rather than per-seat cards ---
    private final Font tableHeaderFont;
    private final Font tableBodyFont;
    private final Font tableAlertFont;

    @Inject
    PdfTheme(ConfigBranding branding, ProductName productName) {
        this(
                productName.value(),
                branding.organisation().orElse(""),
                branding.pdf().palette().headline(),
                branding.pdf().palette().muted(),
                branding.pdf().palette().accent(),
                branding.pdf().palette().highlight(),
                branding.pdf().palette().pill(),
                branding.pdf().logo().orElse(""),
                branding.pdf().strip().orElse(""));
    }

    /**
     * Neutral defaults, for the callers that live outside CDI — the export
     * tests, which assert on content and not on a customer's colours.
     */
    PdfTheme() {
        this(ProductName.neutral().value(), "", "#1f2933", "#6b7280", "#3a6ea5", "#e4eaf1", "#f1f4f8", "", "");
    }

    private PdfTheme(
            String productName,
            String organisation,
            String headline,
            String muted,
            String accent,
            String highlight,
            String pill,
            String logoResource,
            String stripResource) {
        this.productName = productName.trim();
        this.organisation = organisation.trim();
        this.logoResource = logoResource.trim();
        this.stripResource = stripResource.trim();
        this.headline = parseColor(headline);
        this.muted = parseColor(muted);
        this.accent = parseColor(accent);
        this.highlight = parseColor(highlight);
        this.pill = parseColor(pill);

        this.brandLabelFont = new Font(Font.HELVETICA, 8.5f, Font.BOLD, this.accent);
        this.nameFont = new Font(Font.HELVETICA, 24, Font.BOLD, this.headline);
        this.statNumberFont = new Font(Font.HELVETICA, 19, Font.BOLD, this.headline);
        this.statLabelFont = new Font(Font.HELVETICA, 7.5f, Font.BOLD, this.headline);
        this.statSubLabelFont = new Font(Font.HELVETICA, 6.5f, Font.BOLD, this.muted);
        this.dateFont = new Font(Font.HELVETICA, 13, Font.BOLD, this.headline);
        this.calloutTitleFont = new Font(Font.HELVETICA, 8.5f, Font.BOLD, this.accent);
        this.calloutTextFont = new Font(Font.HELVETICA, 10.5f, Font.NORMAL, this.headline);
        this.badgeFont = new Font(Font.HELVETICA, 7.5f, Font.BOLD, Color.WHITE);
        this.timeFont = new Font(Font.HELVETICA, 8.5f, Font.BOLD, this.muted);
        this.standFont = new Font(Font.HELVETICA, 10.5f, Font.BOLD, this.headline);
        this.locationFont = new Font(Font.HELVETICA, 9, Font.NORMAL, this.muted);
        this.teamFont = new Font(Font.HELVETICA, 9, Font.ITALIC, this.muted);
        this.emptyStateFont = new Font(Font.HELVETICA, 10, Font.ITALIC, this.muted);
        this.footerFont = new Font(Font.HELVETICA, 8, Font.NORMAL, this.muted);
        this.tableHeaderFont = new Font(Font.HELVETICA, 8, Font.BOLD, this.headline);
        this.tableBodyFont = new Font(Font.HELVETICA, 8, Font.NORMAL, this.headline);
        this.tableAlertFont = new Font(Font.HELVETICA, 8, Font.BOLD, this.accent);
    }

    String productName() {
        return productName;
    }

    /**
     * Who this document belongs to, as printed at the foot of every page: the
     * customer when the deployment named one, the product otherwise — never a
     * event nobody here has heard of.
     */
    String footerOwner() {
        return organisation.isEmpty() ? productName : organisation;
    }

    Color headline() {
        return headline;
    }

    Color muted() {
        return muted;
    }

    Color accent() {
        return accent;
    }

    Color highlight() {
        return highlight;
    }

    Color pill() {
        return pill;
    }

    Color cardBackground() {
        return cardBackground;
    }

    Font brandLabelFont() {
        return brandLabelFont;
    }

    Font nameFont() {
        return nameFont;
    }

    Font statNumberFont() {
        return statNumberFont;
    }

    Font statLabelFont() {
        return statLabelFont;
    }

    Font statSubLabelFont() {
        return statSubLabelFont;
    }

    Font dateFont() {
        return dateFont;
    }

    Font calloutTitleFont() {
        return calloutTitleFont;
    }

    Font calloutTextFont() {
        return calloutTextFont;
    }

    Font badgeFont() {
        return badgeFont;
    }

    Font timeFont() {
        return timeFont;
    }

    Font standFont() {
        return standFont;
    }

    Font locationFont() {
        return locationFont;
    }

    Font teamFont() {
        return teamFont;
    }

    Font footerFont() {
        return footerFont;
    }

    Font tableHeaderFont() {
        return tableHeaderFont;
    }

    Font tableBodyFont() {
        return tableBodyFont;
    }

    Font tableAlertFont() {
        return tableAlertFont;
    }

    /**
     * The footer of every page: who the document belongs to and when the file
     * was produced, then a second line saying which édition the data came from
     * and when that édition was solved.
     *
     * <p>The generation date alone dates the click, not the schedule. Someone
     * holding a printed copy needs the date of the plan itself to know whether
     * it has moved since — and an animateur comparing two downloads has nothing
     * else to go on. Which date that is depends on which plan the document
     * carries: see {@link ExportProvenance}.</p>
     */
    FooterEvent footerEvent(String what, Instant generatedAt, ExportProvenance.Provenance provenance) {
        String text = footerOwner() + " · " + what + " généré le "
                + GENERATED_AT_FORMAT.format(generatedAt.atZone(ZoneId.systemDefault()));
        return new FooterEvent(text, provenanceText(provenance), footerFont, muted);
    }

    /**
     * How the plan's date is said, which depends on which plan the document
     * carries: an animateur's PDF renders the published plan, so it is dated by
     * its publication — saying « résolue le » there would name a version they
     * do not hold (issue #245).
     */
    private static String datation(ExportProvenance.Provenance provenance) {
        boolean publiee = provenance.nature() == ExportProvenance.Nature.PUBLICATION;
        if (provenance.date() == null) {
            return publiee ? ", jamais publiée" : ", jamais résolue";
        }
        return (publiee ? ", publiée le " : ", résolue le ")
                + GENERATED_AT_FORMAT.format(provenance.date().atZone(ZoneId.systemDefault()));
    }

    private static String provenanceText(ExportProvenance.Provenance provenance) {
        if (provenance == null) {
            return null;
        }
        String edition =
                provenance.editionNom() == null || provenance.editionNom().isBlank()
                        ? "à partir des données de l'édition courante"
                        : "à partir des données de l'édition « " + provenance.editionNom() + " »";
        return edition + datation(provenance);
    }

    /**
     * Branded page header shared by the global and per-animateur PDFs: the
     * deployment's logo when it has one, a spaced small-caps brand label and
     * the page's title — only the title-column width, the texts and the bottom
     * spacing differ.
     *
     * <p>An instance that configured no logo gets the same header without the
     * image column: a missing mark is better than someone else's.</p>
     */
    PdfPTable brandHeader(Document document, float titleWidth, String brandText, String title, float spacingAfter) {
        Image logo = loadOptionalImage(logoResource);
        PdfPTable header =
                logo == null ? new PdfPTable(new float[] {titleWidth}) : new PdfPTable(new float[] {46f, titleWidth});
        header.setTotalWidth(document.getPageSize().getWidth() - document.leftMargin() - document.rightMargin());
        header.setLockedWidth(true);

        if (logo != null) {
            logo.scaleToFit(46f, 46f);
            PdfPCell logoCell = new PdfPCell(logo, false);
            logoCell.setBorder(Rectangle.NO_BORDER);
            logoCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            logoCell.setPadding(0f);
            header.addCell(logoCell);
        }

        PdfPCell titleCell = new PdfPCell();
        titleCell.setBorder(Rectangle.NO_BORDER);
        titleCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        titleCell.setPaddingLeft(logo == null ? 0f : 14f);
        Paragraph brandLabel = new Paragraph();
        Chunk brandChunk = new Chunk(brandText, brandLabelFont);
        brandChunk.setCharacterSpacing(1.4f);
        brandLabel.add(brandChunk);
        brandLabel.setSpacingAfter(3f);
        titleCell.addElement(brandLabel);
        titleCell.addElement(new Paragraph(title, nameFont));
        header.addCell(titleCell);
        header.setSpacingAfter(spacingAfter);
        return header;
    }

    /** Decorative band of the individual planning's first page, or {@code null} when the deployment configured none. */
    Image strip() {
        return loadOptionalImage(stripResource);
    }

    Paragraph emptyState() {
        Paragraph paragraph = new Paragraph("Aucune affectation pour cet événement.", emptyStateFont);
        paragraph.setAlignment(Element.ALIGN_CENTER);
        paragraph.setSpacingBefore(24f);
        return paragraph;
    }

    static String formatFrenchDayDate(LocalDate date) {
        String raw = FRENCH_DAY_DATE_FORMAT.format(date);
        return raw.substring(0, 1).toUpperCase(Locale.FRENCH) + raw.substring(1);
    }

    /**
     * Reads {@code #rrggbb} (the {@code #} optional), the shape an operator
     * copies out of a brand guide. Anything else is a configuration mistake and
     * must be loud: a silently ignored colour would ship a customer's documents
     * in the wrong palette.
     */
    private static Color parseColor(String value) {
        String hex = value.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        if (hex.length() != 6) {
            throw new IllegalArgumentException("Invalid branding colour '" + value + "': expected #rrggbb");
        }
        try {
            return new Color(Integer.parseInt(hex, 16));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid branding colour '" + value + "': expected #rrggbb", e);
        }
    }

    /**
     * Loads a configured image, or answers {@code null} when the deployment
     * configured none. {@code classpath:/x.png} reads an image bundled in the
     * application; anything else is a filesystem path, which is how a logo
     * mounted next to the container reaches the documents.
     */
    private static Image loadOptionalImage(String resource) {
        if (resource == null || resource.isEmpty()) {
            return null;
        }
        try {
            byte[] bytes = resource.startsWith(CLASSPATH_PREFIX)
                    ? readClasspath(resource.substring(CLASSPATH_PREFIX.length()))
                    : Files.readAllBytes(Path.of(resource));
            return Image.getInstance(bytes);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load branding image " + resource, e);
        }
    }

    private static byte[] readClasspath(String path) throws IOException {
        try (InputStream in = PdfTheme.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("Missing classpath resource: " + path);
            }
            return in.readAllBytes();
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
            background.roundRectangle(
                    position.getLeft(), position.getBottom(), position.getWidth(), position.getHeight(), radius);
            background.fill();
            background.restoreState();
        }
    }

    /** Draws a centered pill (background + clock icon + text) sized to its own content, ignoring the cell's own padding. */
    static final class TimePillEvent implements PdfPCellEvent {
        private final String text;
        private final Font font;
        private final Color background;
        private final Color contentColor;
        private final float pillWidth;
        private final float pillHeight;
        private final float iconDiameter;
        private final float iconGap;

        TimePillEvent(
                String text,
                Font font,
                Color background,
                Color contentColor,
                float pillWidth,
                float pillHeight,
                float iconDiameter,
                float iconGap) {
            this.text = text;
            this.font = font;
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

            BaseFont baseFont = font.getCalculatedBaseFont(false);
            float textWidth = baseFont.getWidthPoint(text, font.getCalculatedSize());
            float contentLeft = left + (pillWidth - (iconDiameter + iconGap + textWidth)) / 2f;

            PdfContentByte line = canvases[PdfPTable.LINECANVAS];
            drawClockIcon(line, contentLeft + iconDiameter / 2f, centerY, iconDiameter / 2f, contentColor);

            PdfContentByte textCanvas = canvases[PdfPTable.TEXTCANVAS];
            textCanvas.saveState();
            textCanvas.beginText();
            textCanvas.setFontAndSize(baseFont, font.getCalculatedSize());
            textCanvas.setColorFill(contentColor);
            textCanvas.setTextMatrix(contentLeft + iconDiameter + iconGap, centerY - font.getCalculatedSize() * 0.35f);
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
        public void tableLayout(
                PdfPTable table,
                float[][] widths,
                float[] heights,
                int headerRows,
                int rowStart,
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
        private final String provenanceText;
        private final Font font;
        private final Color color;
        private final List<PdfTemplate> pageCounterTemplates = new ArrayList<>();

        FooterEvent(String generatedAtText, String provenanceText, Font font, Color color) {
            this.generatedAtText = generatedAtText;
            this.provenanceText = provenanceText;
            this.font = font;
            this.color = color;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            Rectangle page = document.getPageSize();
            float y = document.bottomMargin() - 18;

            PdfContentByte canvas = writer.getDirectContent();
            Phrase generated = new Phrase(generatedAtText, font);
            ColumnText.showTextAligned(canvas, Element.ALIGN_LEFT, generated, document.leftMargin(), y, 0);

            // Second line rather than a longer first one: the page counter sits
            // on the right of that first line, and a provenance naming a long
            // édition would run into it.
            if (provenanceText != null) {
                Phrase provenance = new Phrase(provenanceText, font);
                ColumnText.showTextAligned(
                        canvas, Element.ALIGN_LEFT, provenance, document.leftMargin(), y - font.getSize() - 2f, 0);
            }

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
            BaseFont baseFont = font.getCalculatedBaseFont(false);
            for (int i = 0; i < pageCounterTemplates.size(); i++) {
                PdfTemplate template = pageCounterTemplates.get(i);
                String text = "Page " + (i + 1) + "/" + totalPages;
                template.beginText();
                template.setFontAndSize(baseFont, font.getSize());
                template.setColorFill(color);
                template.showTextAligned(Element.ALIGN_RIGHT, text, 70f, 3f, 0);
                template.endText();
            }
        }
    }
}
