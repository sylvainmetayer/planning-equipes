package dev.sylvain.planning.service;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.openpdf.text.Chunk;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Image;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The planning of one animateur, as cards rather than as a table: one card per
 * assignment, with the day, the time range, the stand, its location and the
 * team-mates on the same row.
 *
 * <p>This is the document read on a phone between two shifts, hence the card
 * layout; the organiser's summary holds in compact tables instead
 * ({@link GlobalPlanningPdf}).</p>
 */
@ApplicationScoped
public class AnimateurPlanningPdf {

    private final PdfTheme theme;

    @Inject
    public AnimateurPlanningPdf(PdfTheme theme) {
        this.theme = theme;
    }

    byte[] construire(String animateurName, List<PosteAffectation> postes,
            Map<String, List<String>> teammatesByPoste, List<PlanningExportService.JourRepos> joursRepos,
            String lienEspaceAnimateur) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 40, 40, 40, 54);
        PdfWriter writer = PdfWriter.getInstance(document, output);
        writer.setPageEvent(theme.footerEvent("planning individuel", Instant.now()));
        document.open();

        addHeader(document, animateurName, postes);

        if (postes.isEmpty()) {
            document.add(theme.emptyState());
        } else {
            document.add(buildStandsAffectesCard(postes));
            // Rest days are interleaved at their chronological place, so the
            // document reads as one continuous event rather than a list of
            // shifts with silently missing days.
            Iterator<PlanningExportService.JourRepos> repos = joursRepos.iterator();
            PlanningExportService.JourRepos prochainRepos = repos.hasNext() ? repos.next() : null;
            for (PosteAffectation poste : postes) {
                LocalDate datePoste = poste.getCreneau() == null ? null : poste.getCreneau().getDate();
                while (prochainRepos != null && datePoste != null && prochainRepos.date().isBefore(datePoste)) {
                    document.add(reposCard(prochainRepos));
                    prochainRepos = repos.hasNext() ? repos.next() : null;
                }
                document.add(buildAssignmentCard(poste, teammatesByPoste.getOrDefault(poste.getId(), List.of())));
            }
            while (prochainRepos != null) {
                document.add(reposCard(prochainRepos));
                prochainRepos = repos.hasNext() ? repos.next() : null;
            }
        }
        if (lienEspaceAnimateur != null) {
            document.add(espaceAnimateurCallout(lienEspaceAnimateur));
        }

        document.close();
        return output.toByteArray();
    }

    /**
     * Personal espace link (issue #165), closing the animateur's PDF: click it
     * on screen, or type the printed URL — it opens their planning and the
     * échange request form, no account needed.
     */
    private Paragraph espaceAnimateurCallout(String lien) {
        Paragraph callout = new Paragraph();
        callout.setSpacingBefore(18f);
        callout.add(new Chunk("VOTRE ESPACE EN LIGNE\n", theme.calloutTitleFont()));
        Chunk action = new Chunk(
                "Consulter mon planning et proposer un échange de créneau", theme.calloutTextFont());
        action.setAnchor(lien);
        callout.add(action);
        Chunk url = new Chunk("\n" + lien, theme.footerFont());
        url.setAnchor(lien);
        callout.add(url);
        return callout;
    }

    private void addHeader(Document document, String animateurName, List<PosteAffectation> postes) {
        float pageWidth = document.getPageSize().getWidth();
        float pageHeight = document.getPageSize().getHeight();

        Image strip = theme.strip();
        if (strip != null) {
            float stripWidth = 210f;
            strip.scaleToFit(stripWidth, stripWidth * strip.getHeight() / strip.getWidth());
            strip.setAbsolutePosition(pageWidth - 22f - strip.getScaledWidth(),
                    pageHeight - 20f - strip.getScaledHeight());
            document.add(strip);
        }

        document.add(theme.brandHeader(document, 320f, "PLANNING", animateurName, 22f));

        PdfPTable stats = statBlock(postes);
        stats.setSpacingAfter(24f);
        document.add(stats);
    }

    private PdfPTable statBlock(List<PosteAffectation> postes) {
        PdfPTable table = new PdfPTable(new float[] { 10f, 0.6f, 10f, 0.6f, 10f });
        table.setWidthPercentage(100);
        table.addCell(PosteStatistics.statCell(theme, PosteStatistics.distinctCreneauCount(postes), "CRÉNEAUX", null));
        table.addCell(PosteStatistics.gapCell());
        table.addCell(PosteStatistics.statCell(theme, PosteStatistics.distinctStandCount(postes), "STANDS", null));
        table.addCell(PosteStatistics.gapCell());
        String heuresSubLabel = String.format(Locale.FRENCH, "TOTAL %.1f H TRAVAILLÉES", PosteStatistics.totalHeures(postes));
        table.addCell(PosteStatistics.statCell(theme, PosteStatistics.distinctDayCount(postes), "JOURS", heuresSubLabel));
        return table;
    }

    /**
     * Rounded callout listing every stand this animateur is assigned to at
     * least once over the whole event, in the order they first come up
     * chronologically. Placed between the stat block and the day-by-day
     * planning so the animateur can see at a glance which stands to revise.
     */
    private PdfPTable buildStandsAffectesCard(List<PosteAffectation> postes) {
        PdfPTable card = new PdfPTable(1);
        card.setWidthPercentage(100);
        card.setSpacingAfter(18f);
        card.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        card.setTableEvent(new PdfTheme.RoundedBackgroundEvent(theme.cardBackground(), theme.accent(), 10f));

        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(14f);

        Paragraph title = new Paragraph();
        Chunk titleChunk = new Chunk("VOS STANDS AFFECTÉS", theme.calloutTitleFont());
        titleChunk.setCharacterSpacing(1.1f);
        title.add(titleChunk);
        title.setSpacingAfter(5f);
        cell.addElement(title);

        cell.addElement(new Paragraph(String.join("  ·  ", PosteStatistics.distinctStandNames(postes)), theme.calloutTextFont()));

        card.addCell(cell);
        return card;
    }

    /** One rounded card per assignment: day/date with a "JOURx" badge, a time pill, the stand, its location and the teammates. */
    private PdfPTable buildAssignmentCard(PosteAffectation poste, List<String> coequipiers) {
        Creneau creneau = poste.getCreneau();

        PdfPTable card = new PdfPTable(new float[] { 2.4f, 1.8f, 2.5f, 2.3f });
        card.setWidthPercentage(100);
        card.setSpacingAfter(9f);
        card.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        card.setTableEvent(new PdfTheme.RoundedBackgroundEvent(theme.cardBackground(), theme.accent(), 10f));

        card.addCell(dayCell(creneau));
        card.addCell(timePillCell(poste.heureDebutEffectif(), poste.heureFinEffectif()));
        card.addCell(standCell(poste.getStand(), coequipiers));
        card.addCell(locationCell(poste.getStand()));
        return card;
    }

    private PdfPCell dayCell(Creneau creneau) {
        return dayCell(creneau.getJour(), creneau.getDate());
    }

    private PdfPCell dayCell(int jour, LocalDate date) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPadding(14f);

        cell.addElement(dayBadge(jour));

        Paragraph dateLine = new Paragraph(PdfTheme.formatFrenchDayDate(date), theme.dateFont());
        dateLine.setSpacingBefore(7f);

        cell.addElement(dateLine);
        return cell;
    }

    /**
     * A rest day, same silhouette as an assignment card but muted: the day
     * badge and date on the left, a plain « Repos » where a stand would be —
     * so a day off reads as planned, not as a hole in the document.
     */
    private PdfPTable reposCard(PlanningExportService.JourRepos jourRepos) {
        PdfPTable card = new PdfPTable(new float[] { 2.4f, 6.6f });
        card.setWidthPercentage(100);
        card.setSpacingAfter(9f);
        card.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        card.setTableEvent(new PdfTheme.RoundedBackgroundEvent(theme.cardBackground(), theme.muted(), 10f));

        card.addCell(dayCell(jourRepos.jour(), jourRepos.date()));

        PdfPCell repos = new PdfPCell();
        repos.setBorder(Rectangle.NO_BORDER);
        repos.setVerticalAlignment(Element.ALIGN_MIDDLE);
        repos.setPadding(14f);
        repos.addElement(new Paragraph("Repos", theme.standFont()));
        card.addCell(repos);
        return card;
    }

    /** A small pill-shaped "JOURx" badge, sized to hug its own text rather than stretching to the column width. */
    private PdfPTable dayBadge(int jour) {
        String text = "JOUR" + jour;
        float characterSpacing = 0.6f;
        BaseFont baseFont = theme.badgeFont().getCalculatedBaseFont(false);
        float textWidth = baseFont.getWidthPoint(text, theme.badgeFont().getCalculatedSize()) + characterSpacing * text.length();
        float pillHeight = 15f;
        float pillWidth = textWidth + 18f;

        PdfPTable table = new PdfPTable(1);
        table.setTotalWidth(pillWidth);
        table.setLockedWidth(true);
        table.setHorizontalAlignment(Element.ALIGN_LEFT);

        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setFixedHeight(pillHeight);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setCellEvent(new PdfTheme.RoundedCellFillEvent(theme.accent(), pillHeight / 2f));
        Chunk chunk = new Chunk(text, theme.badgeFont());
        chunk.setCharacterSpacing(characterSpacing);
        cell.setPhrase(new Phrase(chunk));
        table.addCell(cell);
        return table;
    }

    private PdfPCell timePillCell(LocalTime heureDebut, LocalTime heureFin) {
        String text = heureDebut.format(PdfTheme.TIME_FORMAT) + " - " + heureFin.format(PdfTheme.TIME_FORMAT);
        BaseFont baseFont = theme.timeFont().getCalculatedBaseFont(false);
        float textWidth = baseFont.getWidthPoint(text, theme.timeFont().getCalculatedSize());
        float iconDiameter = 8f;
        float iconGap = 5f;
        float pillHeight = 20f;
        float pillWidth = iconDiameter + iconGap + textWidth + 24f;

        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setPadding(14f);
        cell.setCellEvent(new PdfTheme.TimePillEvent(text, theme.timeFont(), theme.pill(), theme.muted(), pillWidth,
                pillHeight, iconDiameter, iconGap));
        return cell;
    }

    /** The stand, and under it who else is on it at that moment — the question every animateur asks about their own planning. */
    private PdfPCell standCell(Stand stand, List<String> coequipiers) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPadding(14f);
        cell.addElement(new Paragraph("Stand " + stand.getNom(), theme.standFont()));

        Paragraph equipe = new Paragraph(coequipiers.isEmpty()
                ? "Seul(e) sur ce stand"
                : "Avec " + String.join(", ", coequipiers), theme.teamFont());
        equipe.setSpacingBefore(3f);
        cell.addElement(equipe);
        return cell;
    }

    /** Location name with a pin icon, kept clickable to OpenStreetMap when the stand's emplacement is geocoded. */
    private PdfPCell locationCell(Stand stand) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        cell.setPadding(14f);

        Emplacement emplacement = stand.getEmplacement();
        if (emplacement != null && emplacement.getNom() != null && !emplacement.getNom().isBlank()) {
            String url = emplacement.getLatitude() != null && emplacement.getLongitude() != null
                    ? osmUrl(emplacement)
                    : null;
            cell.setCellEvent(new PdfTheme.LocationPinEvent(emplacement.getNom(), theme.locationFont(), theme.muted(), 7f, 4f, url));
        }
        return cell;
    }

    private String osmUrl(Emplacement emplacement) {
        double lat = emplacement.getLatitude();
        double lon = emplacement.getLongitude();
        return "https://www.openstreetmap.org/?mlat=" + lat + "&mlon=" + lon + "#map=18/" + lat + "/" + lon;
    }
}
