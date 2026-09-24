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
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfName;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPCellEvent;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfPageEventHelper;
import org.openpdf.text.pdf.PdfString;
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
    /** The first date of a period, shorn of the month the second one carries. */
    static final DateTimeFormatter JOUR_SANS_MOIS = DateTimeFormatter.ofPattern("EEEE d", Locale.FRENCH);

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
    private final Font editionFont;
    private final Font calloutTitleFont;
    private final Font calloutTextFont;
    private final Font standFont;
    private final Font locationFont;
    /** Teammates line under the stand name: present but secondary to the stand itself. */
    private final Font teamFont;

    // --- Redesign of the individual booklet and of the organiser's document:
    // an overview built of figures, a timeline and section titles, where the
    // former layout only ever had cards ---
    private final Font periodeFont;
    private final Font chiffreFont;
    private final Font chiffreLabelFont;
    private final Font sectionFont;
    private final Font sousTitreFont;
    private final Font friseLabelFont;
    private final Font friseJourFont;
    private final Font friseBarreFont;
    private final Font friseTotalFont;
    private final Font legendeFont;
    private final Font jourTitreFont;
    private final Font jourNumeroFont;
    private final Font jourTotalFont;
    private final Font heureFont;
    private final Font heureFinFont;
    private final Font bandeauFont;
    private final Font compteurFont;
    private final Font effectifFont;
    private final Font tableMiniFont;
    private final Font tableMiniHeaderFont;
    private final Font lienFont;
    private final Font lienLabelFont;
    private final Font lienCourantFont;

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
        this.editionFont = new Font(Font.HELVETICA, 10.5f, Font.NORMAL, this.muted);
        this.calloutTitleFont = new Font(Font.HELVETICA, 8.5f, Font.BOLD, this.accent);
        this.calloutTextFont = new Font(Font.HELVETICA, 10.5f, Font.NORMAL, this.headline);
        this.standFont = new Font(Font.HELVETICA, 10.5f, Font.BOLD, this.headline);
        this.locationFont = new Font(Font.HELVETICA, 9, Font.NORMAL, this.muted);
        this.teamFont = new Font(Font.HELVETICA, 9, Font.ITALIC, this.muted);
        this.periodeFont = new Font(Font.HELVETICA, 10, Font.NORMAL, this.muted);
        this.chiffreFont = new Font(Font.HELVETICA, 22, Font.BOLD, this.headline);
        this.chiffreLabelFont = new Font(Font.HELVETICA, 7.5f, Font.NORMAL, this.muted);
        this.sectionFont = new Font(Font.HELVETICA, 14, Font.BOLD, this.headline);
        this.sousTitreFont = new Font(Font.HELVETICA, 8, Font.NORMAL, this.muted);
        this.friseLabelFont = new Font(Font.HELVETICA, 6.5f, Font.NORMAL, this.muted);
        this.friseJourFont = new Font(Font.HELVETICA, 7, Font.BOLD, this.headline);
        this.friseBarreFont = new Font(Font.HELVETICA, 6, Font.BOLD, Color.WHITE);
        this.friseTotalFont = new Font(Font.HELVETICA, 7, Font.BOLD, this.headline);
        this.legendeFont = new Font(Font.HELVETICA, 6.5f, Font.NORMAL, this.headline);
        this.jourTitreFont = new Font(Font.HELVETICA, 13, Font.BOLD, this.headline);
        this.jourNumeroFont = new Font(Font.HELVETICA, 7.5f, Font.BOLD, this.muted);
        this.jourTotalFont = new Font(Font.HELVETICA, 11, Font.BOLD, this.accent);
        this.heureFont = new Font(Font.HELVETICA, 11, Font.BOLD, this.headline);
        this.heureFinFont = new Font(Font.HELVETICA, 8.5f, Font.NORMAL, this.muted);
        this.bandeauFont = new Font(Font.HELVETICA, 7.5f, Font.BOLD, this.accent);
        this.compteurFont = new Font(Font.HELVETICA, 11, Font.BOLD, this.accent);
        this.effectifFont = new Font(Font.HELVETICA, 20, Font.BOLD, this.headline);
        this.tableMiniFont = new Font(Font.HELVETICA, 6, Font.NORMAL, this.headline);
        this.tableMiniHeaderFont = new Font(Font.HELVETICA, 6, Font.BOLD, this.headline);
        this.lienFont = new Font(Font.HELVETICA, 9, Font.BOLD, this.accent);
        this.lienLabelFont = new Font(Font.HELVETICA, 7, Font.NORMAL, this.muted);
        this.lienCourantFont = new Font(Font.HELVETICA, 9, Font.BOLD, this.headline);
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

    /** The line naming the édition, under the title of a document about one. */
    Font editionFont() {
        return editionFont;
    }

    Font calloutTitleFont() {
        return calloutTitleFont;
    }

    Font calloutTextFont() {
        return calloutTextFont;
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

    /** Italic muted: « Repos », « Aucune affectation » — what is said when nothing is planned. */
    Font emptyStateFont() {
        return emptyStateFont;
    }

    Font periodeFont() {
        return periodeFont;
    }

    Font chiffreFont() {
        return chiffreFont;
    }

    Font chiffreLabelFont() {
        return chiffreLabelFont;
    }

    Font sectionFont() {
        return sectionFont;
    }

    Font sousTitreFont() {
        return sousTitreFont;
    }

    Font friseLabelFont() {
        return friseLabelFont;
    }

    Font friseJourFont() {
        return friseJourFont;
    }

    Font friseBarreFont() {
        return friseBarreFont;
    }

    Font friseTotalFont() {
        return friseTotalFont;
    }

    Font legendeFont() {
        return legendeFont;
    }

    Font jourTitreFont() {
        return jourTitreFont;
    }

    Font jourNumeroFont() {
        return jourNumeroFont;
    }

    Font jourTotalFont() {
        return jourTotalFont;
    }

    Font heureFont() {
        return heureFont;
    }

    Font heureFinFont() {
        return heureFinFont;
    }

    Font bandeauFont() {
        return bandeauFont;
    }

    Font compteurFont() {
        return compteurFont;
    }

    Font effectifFont() {
        return effectifFont;
    }

    Font tableMiniFont() {
        return tableMiniFont;
    }

    Font tableMiniHeaderFont() {
        return tableMiniHeaderFont;
    }

    Font lienFont() {
        return lienFont;
    }

    Font lienLabelFont() {
        return lienLabelFont;
    }

    /** The entry of the navigation strip the reader is already on: named, not a link. */
    Font lienCourantFont() {
        return lienCourantFont;
    }

    /**
     * The four steps of the organiser's heat scale, from the lightest to the
     * strongest: the deployment's accent diluted in white rather than a fixed
     * yellow, so a white-labelled document keeps reading as its own.
     *
     * @param niveau 0 to 3; anything outside is clamped
     */
    Color chaleur(int niveau) {
        float[] parts = {0.14f, 0.34f, 0.62f, 0.9f};
        float part = parts[Math.max(0, Math.min(parts.length - 1, niveau))];
        return melange(accent, Color.WHITE, part);
    }

    /** The background of a day band, a section header or a rest row: the accent, barely there. */
    Color voile() {
        return melange(accent, Color.WHITE, 0.07f);
    }

    /**
     * What a reader's software needs to announce the document rather than a
     * file name (RGAA 13.3): a title shown in its place, and the language the
     * text is written in, so a screen reader pronounces it in French.
     *
     * <p>Deliberately <em>not</em> {@code writer.setTagged()}: OpenPDF marks
     * the file as tagged but builds no structure over what {@code Document.add}
     * writes, and a tagged PDF with an empty structure tree reads as blank to
     * the software that trusts the flag — worse than an untagged one, which it
     * reads as a text stream. The espace animateur is the accessible version of
     * the same planning.</p>
     */
    static void describe(Document document, PdfWriter writer, String title) {
        document.addTitle(title);
        writer.getExtraCatalog().put(PdfName.LANG, new PdfString("fr-FR"));
        writer.setViewerPreferences(PdfWriter.DisplayDocTitle);
    }

    /** {@code part} of {@code couleur} over {@code fond} — no alpha in a PDF fill, so the blend is computed. */
    static Color melange(Color couleur, Color fond, float part) {
        float reste = 1f - part;
        return new Color(
                Math.round(couleur.getRed() * part + fond.getRed() * reste),
                Math.round(couleur.getGreen() * part + fond.getGreen() * reste),
                Math.round(couleur.getBlue() * part + fond.getBlue() * reste));
    }

    /** Whether white text reads on that fill, by relative luminance — a legend nobody can read is worse than no colour. */
    static Color lisibleSur(Color fond) {
        double luminance = (0.2126 * fond.getRed() + 0.7152 * fond.getGreen() + 0.0722 * fond.getBlue()) / 255.0;
        return luminance > 0.62 ? Color.BLACK : Color.WHITE;
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
        return brandHeader(document, titleWidth, brandText, title, null, spacingAfter);
    }

    /**
     * The same header with a line under the title — the édition the document is
     * about (issue #608). A {@code null} or blank subtitle prints nothing, so a
     * document produced where the édition cannot be read looks exactly as it
     * did before.
     */
    PdfPTable brandHeader(
            Document document, float titleWidth, String brandText, String title, String subtitle, float spacingAfter) {
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
        if (subtitle != null && !subtitle.isBlank()) {
            Paragraph edition = new Paragraph(subtitle, editionFont);
            edition.setSpacingBefore(2f);
            titleCell.addElement(edition);
        }
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

    /**
     * « Du lundi 14 au mardi 29 septembre 2026 » — the month and the year said
     * once when both dates share them, which is the ordinary case of an
     * édition and the form a reader expects on a cover.
     */
    static String formatPeriode(LocalDate premier, LocalDate dernier) {
        if (premier == null || dernier == null) {
            return "";
        }
        if (premier.equals(dernier)) {
            return formatFrenchDayDate(premier) + " " + premier.getYear();
        }
        boolean memeMois = premier.getMonth() == dernier.getMonth() && premier.getYear() == dernier.getYear();
        String debut = memeMois ? JOUR_SANS_MOIS.format(premier) : FRENCH_DAY_DATE_FORMAT.format(premier);
        return "Du " + debut + " au " + FRENCH_DAY_DATE_FORMAT.format(dernier) + " " + dernier.getYear();
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
                // « 1 / 5 » rather than « Page 1/5 »: on a document read folded
                // in a pocket, the two words are what the number has to fight.
                String text = (i + 1) + " / " + totalPages;
                template.beginText();
                template.setFontAndSize(baseFont, font.getSize());
                template.setColorFill(color);
                template.showTextAligned(Element.ALIGN_RIGHT, text, 70f, 3f, 0);
                template.endText();
            }
        }
    }

    /** Draws a small sun (disc + four rays), the mark of a day an authority's consigne governs. */
    static void drawSunIcon(PdfContentByte canvas, float centerX, float centerY, float radius, Color color) {
        canvas.saveState();
        canvas.setColorFill(color);
        canvas.circle(centerX, centerY, radius * 0.62f);
        canvas.fill();
        canvas.setColorStroke(color);
        canvas.setLineWidth(0.5f);
        // Rays on the four cardinal directions: the diagonals of a first
        // attempt read as a cross at this size, which is the one thing a
        // weather mark must not look like.
        for (int i = 0; i < 4; i++) {
            double angle = i * Math.PI / 2;
            float dx = (float) Math.cos(angle);
            float dy = (float) Math.sin(angle);
            canvas.moveTo(centerX + dx * radius * 0.9f, centerY + dy * radius * 0.9f);
            canvas.lineTo(centerX + dx * radius * 1.45f, centerY + dy * radius * 1.45f);
        }
        canvas.stroke();
        canvas.restoreState();
    }

    /**
     * One mark on a day's timeline: a shift drawn as a filled bar, or a break
     * drawn as a dashed outline — the animateur owes themselves the second one,
     * so it must not read as a third stand.
     *
     * @param libelle what is written inside the bar, dropped when the bar is too narrow for it
     */
    record Barre(int debutMinutes, int finMinutes, Color couleur, String libelle, boolean pause) {}

    /**
     * Draws one row of the overview timeline inside its own cell: the hour
     * guides, then the day's bars positioned on the shared axis.
     *
     * <p>Drawn rather than laid out because a row is a <b>scale</b>: two shifts
     * of the same day must land under the same hour on every row of the page,
     * which nested tables of proportional widths cannot promise.</p>
     */
    static final class FriseEvent implements PdfPCellEvent {
        private final List<Barre> barres;
        private final int debutAmplitude;
        private final int finAmplitude;
        private final int pasMinutes;
        private final Color guide;
        private final Color fond;
        private final Font libelleFont;

        FriseEvent(
                List<Barre> barres,
                int debutAmplitude,
                int finAmplitude,
                int pasMinutes,
                Color guide,
                Color fond,
                Font libelleFont) {
            this.barres = barres;
            this.debutAmplitude = debutAmplitude;
            this.finAmplitude = finAmplitude;
            this.pasMinutes = pasMinutes;
            this.guide = guide;
            this.fond = fond;
            this.libelleFont = libelleFont;
        }

        @Override
        public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
            float left = position.getLeft();
            float width = position.getWidth();
            int span = Math.max(1, finAmplitude - debutAmplitude);
            PdfContentByte fondCanvas = canvases[PdfPTable.BACKGROUNDCANVAS];

            if (fond != null) {
                fondCanvas.saveState();
                fondCanvas.setColorFill(fond);
                fondCanvas.rectangle(left, position.getBottom(), width, position.getHeight());
                fondCanvas.fill();
                fondCanvas.restoreState();
            }

            fondCanvas.saveState();
            fondCanvas.setColorStroke(guide);
            fondCanvas.setLineWidth(0.4f);
            for (int minute = debutAmplitude; minute <= finAmplitude; minute += pasMinutes) {
                float x = left + width * (minute - debutAmplitude) / span;
                fondCanvas.moveTo(x, position.getBottom());
                fondCanvas.lineTo(x, position.getTop());
            }
            fondCanvas.stroke();
            fondCanvas.restoreState();

            for (Barre barre : barres) {
                float x1 = left + width * (clamp(barre.debutMinutes()) - debutAmplitude) / span;
                float x2 = left + width * (clamp(barre.finMinutes()) - debutAmplitude) / span;
                float largeur = Math.max(1.5f, x2 - x1);
                if (barre.pause()) {
                    PdfContentByte ligne = canvases[PdfPTable.LINECANVAS];
                    ligne.saveState();
                    ligne.setColorStroke(barre.couleur());
                    ligne.setLineWidth(0.7f);
                    ligne.setLineDash(1.6f, 1.4f, 0f);
                    float hauteur = position.getHeight() * 0.42f;
                    ligne.rectangle(x1, position.getBottom() + (position.getHeight() - hauteur) / 2f, largeur, hauteur);
                    ligne.stroke();
                    ligne.restoreState();
                    continue;
                }
                float hauteur = position.getHeight() - 2.5f;
                float bas = position.getBottom() + 1.25f;
                fondCanvas.saveState();
                fondCanvas.setColorFill(barre.couleur());
                fondCanvas.roundRectangle(x1, bas, largeur, hauteur, 1.5f);
                fondCanvas.fill();
                fondCanvas.restoreState();

                String libelle = barre.libelle();
                if (libelle == null || libelle.isBlank()) {
                    continue;
                }
                Font font = new Font(libelleFont);
                font.setColor(lisibleSur(barre.couleur()));
                BaseFont baseFont = font.getCalculatedBaseFont(false);
                float taille = font.getCalculatedSize();
                float disponible = largeur - 4f;
                String texte = libelle;
                while (baseFont.getWidthPoint(texte, taille) > disponible && texte.length() > 1) {
                    texte = texte.substring(0, texte.length() - 1);
                }
                if (baseFont.getWidthPoint(texte, taille) > disponible) {
                    continue;
                }
                PdfContentByte texteCanvas = canvases[PdfPTable.TEXTCANVAS];
                texteCanvas.saveState();
                texteCanvas.beginText();
                texteCanvas.setFontAndSize(baseFont, taille);
                texteCanvas.setColorFill(font.getColor());
                texteCanvas.setTextMatrix(x1 + 2f, bas + (hauteur - taille) / 2f + 0.8f);
                texteCanvas.showText(texte);
                texteCanvas.endText();
                texteCanvas.restoreState();
            }
        }

        private int clamp(int minute) {
            return Math.max(debutAmplitude, Math.min(finAmplitude, minute));
        }
    }

    /** Draws the hour labels of the timeline's axis, on the same scale as the rows below it. */
    static final class FriseAxeEvent implements PdfPCellEvent {
        private final int debutAmplitude;
        private final int finAmplitude;
        private final int pasMinutes;
        private final Font font;

        FriseAxeEvent(int debutAmplitude, int finAmplitude, int pasMinutes, Font font) {
            this.debutAmplitude = debutAmplitude;
            this.finAmplitude = finAmplitude;
            this.pasMinutes = pasMinutes;
            this.font = font;
        }

        @Override
        public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
            int span = Math.max(1, finAmplitude - debutAmplitude);
            PdfContentByte canvas = canvases[PdfPTable.TEXTCANVAS];
            for (int minute = debutAmplitude; minute <= finAmplitude; minute += pasMinutes) {
                float x = position.getLeft() + position.getWidth() * (minute - debutAmplitude) / span;
                String texte = (minute / 60) % 24 + "h";
                ColumnText.showTextAligned(
                        canvas, Element.ALIGN_CENTER, new Phrase(texte, font), x, position.getBottom() + 1.5f, 0);
            }
        }
    }

    /**
     * A row of the timeline that is not a working day: a flat band with a word
     * in it. « Repos » is a decision, and a blank row would read as an
     * oversight.
     */
    static final class FriseTextEvent implements PdfPCellEvent {
        private final String texte;
        private final Font font;
        private final Color fond;

        FriseTextEvent(String texte, Font font, Color fond) {
            this.texte = texte;
            this.font = font;
            this.fond = fond;
        }

        @Override
        public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
            PdfContentByte canvas = canvases[PdfPTable.BACKGROUNDCANVAS];
            canvas.saveState();
            canvas.setColorFill(fond);
            canvas.rectangle(position.getLeft(), position.getBottom(), position.getWidth(), position.getHeight());
            canvas.fill();
            canvas.restoreState();
            ColumnText.showTextAligned(
                    canvases[PdfPTable.TEXTCANVAS],
                    Element.ALIGN_LEFT,
                    new Phrase(texte, font),
                    position.getLeft() + 5f,
                    position.getBottom() + (position.getHeight() - font.getCalculatedSize()) / 2f + 0.8f,
                    0);
        }
    }

    /** A small filled chip, the colour of a legend entry or of a stand on a day card. */
    static final class PastilleEvent implements PdfPCellEvent {
        private final Color couleur;
        private final float diametre;

        PastilleEvent(Color couleur, float diametre) {
            this.couleur = couleur;
            this.diametre = diametre;
        }

        @Override
        public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
            PdfContentByte canvas = canvases[PdfPTable.BACKGROUNDCANVAS];
            float bas = position.getBottom() + (position.getHeight() - diametre) / 2f;
            canvas.saveState();
            canvas.setColorFill(couleur);
            canvas.roundRectangle(position.getLeft(), bas, diametre, diametre, diametre / 3f);
            canvas.fill();
            canvas.restoreState();
        }
    }

    /** The two marks a document draws beside a text rather than writing: a sun, a map pin. */
    enum Icone {
        /** A day an authority's consigne governs. */
        SOLEIL,
        /** A place, ahead of its name. */
        REPERE
    }

    /**
     * Draws one of those marks in its own cell, at its left edge or at its
     * right — an icon a font does not carry, and which the layout therefore
     * cannot write.
     */
    static final class IconeEvent implements PdfPCellEvent {
        private final Icone icone;
        private final Color couleur;
        private final boolean aDroite;

        IconeEvent(Icone icone, Color couleur, boolean aDroite) {
            this.icone = icone;
            this.couleur = couleur;
            this.aDroite = aDroite;
        }

        @Override
        public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
            float x = aDroite ? position.getRight() - 5f : position.getLeft() + 4.5f;
            float y = position.getBottom() + position.getHeight() / 2f;
            PdfContentByte canvas = canvases[PdfPTable.LINECANVAS];
            if (icone == Icone.SOLEIL) {
                drawSunIcon(canvas, x, y, 3f, couleur);
            } else {
                drawPinIcon(canvas, x, y, 2.6f, couleur);
            }
        }
    }
}
