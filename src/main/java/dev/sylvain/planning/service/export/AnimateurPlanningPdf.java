package dev.sylvain.planning.service.export;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.analyse.PauseAnalyzer;
import dev.sylvain.planning.service.referentiel.TypologieLibelles;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
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
    private final TypologieLibelles typologies;

    @Inject
    public AnimateurPlanningPdf(PdfTheme theme, TypologieLibelles typologies) {
        this.theme = theme;
        this.typologies = typologies;
    }

    byte[] construire(
            String animateurName,
            List<PosteAffectation> postes,
            Map<String, List<String>> teammatesByPoste,
            List<PlanningExportService.JourRepos> joursRepos,
            List<PauseAnalyzer.PauseAnimateurView> pauses,
            List<PauseAnalyzer.CoupureAnimateurView> coupures,
            String lienEspaceAnimateur,
            ExportProvenance.Provenance provenance,
            Map<LocalDate, String> journeesModifiees) {
        // The days a consigne governs, and what to print under their date —
        // handed down rather than kept on the bean, which is shared by every
        // build running at once.
        Map<LocalDate, String> notes = journeesModifiees == null ? Map.of() : journeesModifiees;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 40, 40, 40, 54);
        PdfWriter writer = PdfWriter.getInstance(document, output);
        writer.setPageEvent(theme.footerEvent("planning individuel", Instant.now(), provenance));
        document.open();

        addHeader(document, animateurName, provenance.edition().libelle(), postes);

        if (postes.isEmpty()) {
            document.add(theme.emptyState());
        } else {
            document.add(buildStandsAffectesCard(postes));
            // Rest days are interleaved at their chronological place, so the
            // document reads as one continuous event rather than a list of
            // shifts with silently missing days.
            Map<PosteAffectation, List<PauseAnalyzer.CoupureAnimateurView>> coupuresParPoste =
                    ancrerCoupures(postes, coupures, pauses);
            Iterator<PlanningExportService.JourRepos> repos = joursRepos.iterator();
            PlanningExportService.JourRepos prochainRepos = repos.hasNext() ? repos.next() : null;
            for (PosteAffectation poste : postes) {
                LocalDate datePoste =
                        poste.getCreneau() == null ? null : poste.getCreneau().getDate();
                while (prochainRepos != null
                        && datePoste != null
                        && prochainRepos.date().isBefore(datePoste)) {
                    document.add(reposCard(prochainRepos, notes));
                    prochainRepos = repos.hasNext() ? repos.next() : null;
                }
                document.add(
                        buildAssignmentCard(poste, teammatesByPoste.getOrDefault(poste.getId(), List.of()), notes));
                // The break sits right under the shift it falls in, at its
                // chronological place: a line the animateur reads while
                // reading their day, not a footnote.
                for (PauseAnalyzer.PauseAnimateurView pause : pauses) {
                    if (pause.fallsInside(poste)) {
                        document.add(pauseCard(pause, coupureQuiCouvre(coupures, pause)));
                    }
                }
                // The meal break (issue #598) hangs off the shift it follows:
                // it is a gap between two stretches, so it belongs under the
                // one the animateur has just left. Printed here only when no
                // legal break of the day already carried it — one card says
                // both when they overlap.
                for (PauseAnalyzer.CoupureAnimateurView coupure : coupuresParPoste.getOrDefault(poste, List.of())) {
                    document.add(coupureCard(coupure));
                }
            }
            while (prochainRepos != null) {
                document.add(reposCard(prochainRepos, notes));
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
        Chunk action = new Chunk("Consulter mon planning et proposer un échange de créneau", theme.calloutTextFont());
        action.setAnchor(lien);
        callout.add(action);
        Chunk url = new Chunk("\n" + lien, theme.footerFont());
        url.setAnchor(lien);
        callout.add(url);
        return callout;
    }

    private void addHeader(Document document, String animateurName, String edition, List<PosteAffectation> postes) {
        float pageWidth = document.getPageSize().getWidth();
        float pageHeight = document.getPageSize().getHeight();

        Image strip = theme.strip();
        if (strip != null) {
            float stripWidth = 210f;
            strip.scaleToFit(stripWidth, stripWidth * strip.getHeight() / strip.getWidth());
            strip.setAbsolutePosition(
                    pageWidth - 22f - strip.getScaledWidth(), pageHeight - 20f - strip.getScaledHeight());
            document.add(strip);
        }

        // The édition named right under the name (issue #608): an animateur who
        // came back from one year to the next holds two of these documents, and
        // « PLANNING / Prénom Nom » alone does not tell them apart.
        document.add(theme.brandHeader(document, 320f, "PLANNING", animateurName, edition, 22f));

        PdfPTable stats = statBlock(postes);
        stats.setSpacingAfter(24f);
        document.add(stats);
    }

    private PdfPTable statBlock(List<PosteAffectation> postes) {
        PdfPTable table = new PdfPTable(new float[] {10f, 0.6f, 10f, 0.6f, 10f});
        table.setWidthPercentage(100);
        table.addCell(PosteStatistics.statCell(theme, PosteStatistics.distinctCreneauCount(postes), "CRÉNEAUX", null));
        table.addCell(PosteStatistics.gapCell());
        table.addCell(PosteStatistics.statCell(theme, PosteStatistics.distinctStandCount(postes), "STANDS", null));
        table.addCell(PosteStatistics.gapCell());
        String heuresSubLabel =
                String.format(Locale.FRENCH, "TOTAL %.1f H TRAVAILLÉES", PosteStatistics.totalHeures(postes));
        table.addCell(
                PosteStatistics.statCell(theme, PosteStatistics.distinctDayCount(postes), "JOURS", heuresSubLabel));
        return table;
    }

    /**
     * Rounded callout listing every stand this animateur is assigned to at
     * least once over the whole event, in the order they first come up
     * chronologically — one stand per line, and under each one the game
     * typologies it proposes, one per line too. Placed between the stat block
     * and the day-by-day planning: this is the revision list, so it says both
     * which stands to revise and which games are played there.
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

        Map<String, String> libelles = typologies.labelsById();
        List<Stand> stands = PosteStatistics.distinctStands(postes);
        for (int i = 0; i < stands.size(); i++) {
            Stand stand = stands.get(i);
            Paragraph ligne = new Paragraph(stand.getNom(), theme.standFont());
            ligne.setSpacingBefore(i == 0 ? 0f : 7f);
            cell.addElement(ligne);
            for (String typologie : typologiesLisibles(stand, libelles)) {
                Paragraph detail = new Paragraph(typologie, theme.locationFont());
                detail.setIndentationLeft(12f);
                cell.addElement(detail);
            }
        }

        card.addCell(cell);
        return card;
    }

    /**
     * The game typologies of a stand, as the animateur reads them: labels
     * rather than referential ids, sorted so two runs of the same planning
     * produce the same document — {@code typologiesProposees} is a set with no
     * order of its own. An id the referential no longer knows is printed as-is
     * rather than dropped: a stand losing a line silently would be worse than
     * a raw id.
     */
    private List<String> typologiesLisibles(Stand stand, Map<String, String> libelles) {
        return stand.getTypologiesProposees().stream()
                .map(id -> libelles.getOrDefault(id, id))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    /** One rounded card per assignment: day/date with a "JOURx" badge, a time pill, the stand, its location and the teammates. */
    private PdfPTable buildAssignmentCard(
            PosteAffectation poste, List<String> coequipiers, Map<LocalDate, String> notes) {
        Creneau creneau = poste.getCreneau();

        PdfPTable card = new PdfPTable(new float[] {2.4f, 1.8f, 2.5f, 2.3f});
        card.setWidthPercentage(100);
        card.setSpacingAfter(9f);
        card.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        card.setTableEvent(new PdfTheme.RoundedBackgroundEvent(theme.cardBackground(), theme.accent(), 10f));

        card.addCell(dayCell(creneau.getJour(), creneau.getDate(), notes.get(creneau.getDate())));
        card.addCell(timePillCell(poste.heureDebutEffectif(), poste.heureFinEffectif()));
        card.addCell(standCell(poste.getStand(), coequipiers));
        card.addCell(locationCell(poste.getStand()));
        return card;
    }

    /** @param note what a consigne says of that day (issue #4), {@code null} on an ordinary day */
    private PdfPCell dayCell(int jour, LocalDate date, String note) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPadding(14f);

        cell.addElement(dayBadge(jour));

        Paragraph dateLine = new Paragraph(PdfTheme.formatFrenchDayDate(date), theme.dateFont());
        dateLine.setSpacingBefore(7f);

        cell.addElement(dateLine);
        // A day a consigne governs (issue #4) says so under its date: the
        // hours printed beside it are not the usual ones, and the person
        // must read why from the document itself, not only from a mail.
        if (note != null) {
            Paragraph ligne = new Paragraph(note, theme.footerFont());
            ligne.setSpacingBefore(3f);
            cell.addElement(ligne);
        }
        return cell;
    }

    /**
     * A rest day, same silhouette as an assignment card but muted: the day
     * badge and date on the left, a plain « Repos » where a stand would be —
     * so a day off reads as planned, not as a hole in the document.
     */
    private PdfPTable reposCard(PlanningExportService.JourRepos jourRepos, Map<LocalDate, String> notes) {
        PdfPTable card = new PdfPTable(new float[] {2.4f, 6.6f});
        card.setWidthPercentage(100);
        card.setSpacingAfter(9f);
        card.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        card.setTableEvent(new PdfTheme.RoundedBackgroundEvent(theme.cardBackground(), theme.muted(), 10f));

        card.addCell(dayCell(jourRepos.jour(), jourRepos.date(), notes.get(jourRepos.date())));

        PdfPCell repos = new PdfPCell();
        repos.setBorder(Rectangle.NO_BORDER);
        repos.setVerticalAlignment(Element.ALIGN_MIDDLE);
        repos.setPadding(14f);
        repos.addElement(new Paragraph("Repos", theme.standFont()));
        card.addCell(repos);
        return card;
    }

    /** A small pill-shaped "JOURx" badge, sized to hug its own text rather than stretching to the column width. */
    /**
     * « Pause de 18:20 à 18:40 » — under the shift, in the accent colour, so
     * the one legal obligation the animateur has to act on themselves stands
     * out from the shifts somebody else planned.
     */
    /**
     * The meal break covering this legal one, or {@code null}. The two are
     * different objects — art. L3121-16 owes twenty minutes at the sixth hour,
     * the meal break is the rule the organisation gives itself — but when the
     * legal one falls inside the meal one, printing both makes the animateur
     * read two obligations where there is one moment (issue #598).
     */
    private static PauseAnalyzer.CoupureAnimateurView coupureQuiCouvre(
            List<PauseAnalyzer.CoupureAnimateurView> coupures, PauseAnalyzer.PauseAnimateurView pause) {
        return coupures.stream()
                .filter(coupure -> coupure.couvre(pause))
                .findFirst()
                .orElse(null);
    }

    /**
     * Each meal break hung off the shift it follows — the last one of its day
     * that ends before it, or, for a break opening the day, the first one
     * after. A document with no other timeline needs that anchor to print the
     * break at its chronological place.
     *
     * <p>A break a legal one already carries is dropped here rather than
     * printed twice: {@link #pauseCard} then says both in one card.</p>
     */
    private static Map<PosteAffectation, List<PauseAnalyzer.CoupureAnimateurView>> ancrerCoupures(
            List<PosteAffectation> postes,
            List<PauseAnalyzer.CoupureAnimateurView> coupures,
            List<PauseAnalyzer.PauseAnimateurView> pauses) {
        Map<PosteAffectation, List<PauseAnalyzer.CoupureAnimateurView>> parPoste = new LinkedHashMap<>();
        for (PauseAnalyzer.CoupureAnimateurView coupure : coupures) {
            if (pauses.stream().anyMatch(coupure::couvre)) {
                continue;
            }
            PosteAffectation ancre = null;
            PosteAffectation suivant = null;
            for (PosteAffectation poste : postes) {
                LocalDate date =
                        poste.getCreneau() == null ? null : poste.getCreneau().getDate();
                if (!coupure.date().equals(date) || poste.heureFinEffectif() == null) {
                    continue;
                }
                if (!poste.heureFinEffectif().isAfter(coupure.debut())) {
                    ancre = poste;
                } else if (suivant == null) {
                    suivant = poste;
                }
            }
            PosteAffectation retenu = ancre != null ? ancre : suivant;
            if (retenu != null) {
                parPoste.computeIfAbsent(retenu, ignored -> new ArrayList<>()).add(coupure);
            }
        }
        return parPoste;
    }

    /** « Repas de 13:00 à 14:00 (60 min) », the meal break of issue #598. */
    private PdfPTable coupureCard(PauseAnalyzer.CoupureAnimateurView coupure) {
        return calloutCard("Repas de " + coupure.debut().format(PdfTheme.TIME_FORMAT) + " à "
                + coupure.fin().format(PdfTheme.TIME_FORMAT) + " (" + coupure.dureeMinutes() + " min)");
    }

    private PdfPTable pauseCard(PauseAnalyzer.PauseAnimateurView pause, PauseAnalyzer.CoupureAnimateurView coupure) {
        String moment = coupure == null
                ? "Pause de " + pause.debut().format(PdfTheme.TIME_FORMAT) + " à "
                        + pause.fin().format(PdfTheme.TIME_FORMAT) + " (" + pause.dureeMinutes() + " min)"
                : "Repas de " + coupure.debut().format(PdfTheme.TIME_FORMAT) + " à "
                        + coupure.fin().format(PdfTheme.TIME_FORMAT) + " (" + coupure.dureeMinutes()
                        + " min), pause légale comprise";
        return calloutCard(moment
                + (pause.relaisDisponible()
                        ? ", en relais avec l'équipe du stand"
                        : " — personne d'autre sur le stand : demandez le relais à l'organisation"));
    }

    /** The accent-coloured strip both the legal break and the meal break print on. */
    private PdfPTable calloutCard(String texte) {
        PdfPTable card = new PdfPTable(1);
        card.setWidthPercentage(100);
        card.setSpacingAfter(9f);
        card.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(8f);
        cell.setPaddingLeft(14f);
        cell.addElement(new Paragraph(texte, theme.calloutTitleFont()));
        card.addCell(cell);
        return card;
    }

    private PdfPTable dayBadge(int jour) {
        String text = "JOUR" + jour;
        float characterSpacing = 0.6f;
        BaseFont baseFont = theme.badgeFont().getCalculatedBaseFont(false);
        float textWidth =
                baseFont.getWidthPoint(text, theme.badgeFont().getCalculatedSize()) + characterSpacing * text.length();
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
        cell.setCellEvent(new PdfTheme.TimePillEvent(
                text, theme.timeFont(), theme.pill(), theme.muted(), pillWidth, pillHeight, iconDiameter, iconGap));
        return cell;
    }

    /** The stand, and under it who else is on it at that moment — the question every animateur asks about their own planning. */
    private PdfPCell standCell(Stand stand, List<String> coequipiers) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPadding(14f);
        cell.addElement(new Paragraph("Stand " + stand.getNom(), theme.standFont()));

        Paragraph equipe = new Paragraph(
                coequipiers.isEmpty() ? "Seul(e) sur ce stand" : "Avec " + String.join(", ", coequipiers),
                theme.teamFont());
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
        if (emplacement != null
                && emplacement.getNom() != null
                && !emplacement.getNom().isBlank()) {
            String url = emplacement.isGeocoded() ? osmUrl(emplacement) : null;
            cell.setCellEvent(new PdfTheme.LocationPinEvent(
                    emplacement.getNom(), theme.locationFont(), theme.muted(), 7f, 4f, url));
        }
        return cell;
    }

    private String osmUrl(Emplacement emplacement) {
        double lat = emplacement.getLatitude();
        double lon = emplacement.getLongitude();
        return "https://www.openstreetmap.org/?mlat=" + lat + "&mlon=" + lon + "#map=18/" + lat + "/" + lon;
    }
}
