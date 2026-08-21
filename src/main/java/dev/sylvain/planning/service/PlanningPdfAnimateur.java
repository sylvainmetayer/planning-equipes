package dev.sylvain.planning.service;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
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

/**
 * Le planning d'un animateur, en cartes plutôt qu'en tableau : une carte par
 * affectation, avec le jour, la plage, le stand, son emplacement et les
 * coéquipiers de la même ligne.
 *
 * <p>C'est le document qu'on lit sur un téléphone entre deux vacations, d'où
 * la mise en page en cartes ; le récapitulatif de l'organisateur, lui, tient
 * en tableaux compacts ({@link PlanningPdfGlobal}).</p>
 */
@ApplicationScoped
public class PlanningPdfAnimateur {

    byte[] construire(String animateurName, List<PosteAffectation> postes,
            Map<String, List<String>> coequipiersParPoste, List<PlanningExportService.JourRepos> joursRepos,
            String lienEspaceAnimateur) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 40, 40, 40, 54);
        PdfWriter writer = PdfWriter.getInstance(document, output);
        String generatedAt = "Festival — Centre-ville · généré le "
                + ChartePdf.GENERATED_AT_FORMAT.format(Instant.now().atZone(ZoneOffset.systemDefault()));
        writer.setPageEvent(new ChartePdf.FooterEvent(generatedAt));
        document.open();

        addHeader(document, animateurName, postes);

        if (postes.isEmpty()) {
            document.add(ChartePdf.emptyState());
        } else {
            document.add(buildStandsAffectesCard(postes));
            // Rest days are interleaved at their chronological place, so the
            // document reads as one continuous festival rather than a list of
            // shifts with silently missing days.
            Iterator<PlanningExportService.JourRepos> repos = joursRepos.iterator();
            PlanningExportService.JourRepos prochainRepos = repos.hasNext() ? repos.next() : null;
            for (PosteAffectation poste : postes) {
                LocalDate datePoste = poste.getCreneau() == null ? null : poste.getCreneau().getDate();
                while (prochainRepos != null && datePoste != null && prochainRepos.date().isBefore(datePoste)) {
                    document.add(reposCard(prochainRepos));
                    prochainRepos = repos.hasNext() ? repos.next() : null;
                }
                document.add(buildAssignmentCard(poste, coequipiersParPoste.getOrDefault(poste.getId(), List.of())));
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
    private static Paragraph espaceAnimateurCallout(String lien) {
        Paragraph callout = new Paragraph();
        callout.setSpacingBefore(18f);
        callout.add(new Chunk("VOTRE ESPACE EN LIGNE\n", ChartePdf.CALLOUT_TITLE_FONT));
        Chunk action = new Chunk(
                "Consulter mon planning et proposer un échange de créneau", ChartePdf.CALLOUT_TEXT_FONT);
        action.setAnchor(lien);
        callout.add(action);
        Chunk url = new Chunk("\n" + lien, ChartePdf.FOOTER_FONT);
        url.setAnchor(lien);
        callout.add(url);
        return callout;
    }

    private void addHeader(Document document, String animateurName, List<PosteAffectation> postes) {
        float pageWidth = document.getPageSize().getWidth();
        float pageHeight = document.getPageSize().getHeight();

        Image strip = ChartePdf.loadImage(ChartePdf.STRIP_RESOURCE);
        float stripWidth = 210f;
        strip.scaleToFit(stripWidth, stripWidth * strip.getHeight() / strip.getWidth());
        strip.setAbsolutePosition(pageWidth - 22f - strip.getScaledWidth(), pageHeight - 20f - strip.getScaledHeight());
        document.add(strip);

        document.add(ChartePdf.brandHeader(document, 320f, "PLANNING", animateurName, 22f));

        PdfPTable stats = statBlock(postes);
        stats.setSpacingAfter(24f);
        document.add(stats);
    }

    private PdfPTable statBlock(List<PosteAffectation> postes) {
        PdfPTable table = new PdfPTable(new float[] { 10f, 0.6f, 10f, 0.6f, 10f });
        table.setWidthPercentage(100);
        table.addCell(StatistiquesPostes.statCell(StatistiquesPostes.distinctCreneauCount(postes), "CRÉNEAUX", null));
        table.addCell(StatistiquesPostes.gapCell());
        table.addCell(StatistiquesPostes.statCell(StatistiquesPostes.distinctStandCount(postes), "STANDS", null));
        table.addCell(StatistiquesPostes.gapCell());
        String heuresSubLabel = String.format(Locale.FRENCH, "TOTAL %.1f H TRAVAILLÉES", StatistiquesPostes.totalHeures(postes));
        table.addCell(StatistiquesPostes.statCell(StatistiquesPostes.distinctDayCount(postes), "JOURS", heuresSubLabel));
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
        card.setTableEvent(new ChartePdf.RoundedBackgroundEvent(ChartePdf.CARD_BACKGROUND, ChartePdf.RED, 10f));

        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(14f);

        Paragraph title = new Paragraph();
        Chunk titleChunk = new Chunk("VOS STANDS AFFECTÉS", ChartePdf.CALLOUT_TITLE_FONT);
        titleChunk.setCharacterSpacing(1.1f);
        title.add(titleChunk);
        title.setSpacingAfter(5f);
        cell.addElement(title);

        cell.addElement(new Paragraph(String.join("  ·  ", StatistiquesPostes.distinctStandNames(postes)), ChartePdf.CALLOUT_TEXT_FONT));

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
        card.setTableEvent(new ChartePdf.RoundedBackgroundEvent(ChartePdf.CARD_BACKGROUND, ChartePdf.RED, 10f));

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

        Paragraph dateLine = new Paragraph(ChartePdf.formatFrenchDayDate(date), ChartePdf.DATE_FONT);
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
        card.setTableEvent(new ChartePdf.RoundedBackgroundEvent(ChartePdf.CARD_BACKGROUND, ChartePdf.MUTED, 10f));

        card.addCell(dayCell(jourRepos.jour(), jourRepos.date()));

        PdfPCell repos = new PdfPCell();
        repos.setBorder(Rectangle.NO_BORDER);
        repos.setVerticalAlignment(Element.ALIGN_MIDDLE);
        repos.setPadding(14f);
        repos.addElement(new Paragraph("Repos", ChartePdf.STAND_FONT));
        card.addCell(repos);
        return card;
    }

    /** A small pill-shaped "JOURx" badge, sized to hug its own text rather than stretching to the column width. */
    private PdfPTable dayBadge(int jour) {
        String text = "JOUR" + jour;
        float characterSpacing = 0.6f;
        BaseFont baseFont = ChartePdf.BADGE_FONT.getCalculatedBaseFont(false);
        float textWidth = baseFont.getWidthPoint(text, ChartePdf.BADGE_FONT.getCalculatedSize()) + characterSpacing * text.length();
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
        cell.setCellEvent(new ChartePdf.RoundedCellFillEvent(ChartePdf.RED, pillHeight / 2f));
        Chunk chunk = new Chunk(text, ChartePdf.BADGE_FONT);
        chunk.setCharacterSpacing(characterSpacing);
        cell.setPhrase(new Phrase(chunk));
        table.addCell(cell);
        return table;
    }

    private PdfPCell timePillCell(LocalTime heureDebut, LocalTime heureFin) {
        String text = heureDebut.format(ChartePdf.TIME_FORMAT) + " - " + heureFin.format(ChartePdf.TIME_FORMAT);
        BaseFont baseFont = ChartePdf.TIME_FONT.getCalculatedBaseFont(false);
        float textWidth = baseFont.getWidthPoint(text, ChartePdf.TIME_FONT.getCalculatedSize());
        float iconDiameter = 8f;
        float iconGap = 5f;
        float pillHeight = 20f;
        float pillWidth = iconDiameter + iconGap + textWidth + 24f;

        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setPadding(14f);
        cell.setCellEvent(new ChartePdf.TimePillEvent(text, ChartePdf.PILL_BACKGROUND, ChartePdf.MUTED, pillWidth, pillHeight, iconDiameter, iconGap));
        return cell;
    }

    /** The stand, and under it who else is on it at that moment — the question every animateur asks about their own planning. */
    private PdfPCell standCell(Stand stand, List<String> coequipiers) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPadding(14f);
        cell.addElement(new Paragraph("Stand " + stand.getNom(), ChartePdf.STAND_FONT));

        Paragraph equipe = new Paragraph(coequipiers.isEmpty()
                ? "Seul(e) sur ce stand"
                : "Avec " + String.join(", ", coequipiers), ChartePdf.TEAM_FONT);
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
            cell.setCellEvent(new ChartePdf.LocationPinEvent(emplacement.getNom(), ChartePdf.LOCATION_FONT, ChartePdf.MUTED, 7f, 4f, url));
        }
        return cell;
    }

    private String osmUrl(Emplacement emplacement) {
        double lat = emplacement.getLatitude();
        double lon = emplacement.getLongitude();
        return "https://www.openstreetmap.org/?mlat=" + lat + "&mlon=" + lon + "#map=18/" + lat + "/" + lon;
    }
}
