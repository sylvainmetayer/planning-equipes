package dev.sylvain.planning.service.export;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.openpdf.text.Chunk;
import org.openpdf.text.Element;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.PdfPCell;

import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.export.PdfTheme;

/**
 * What a set of seats amounts to — how many days, stands, timeslots, hours —
 * and the tile that displays it.
 *
 * <p>The individual PDF and the global PDF lay the same row of tiles in their
 * header, over different sets: one the assignments of a single person, the
 * other those of the whole event. Counting and displaying in the same place
 * is what guarantees that "3 JOURS" means the same thing on both documents.</p>
 */
final class PosteStatistics {

    private PosteStatistics() {
    }

    static PdfPCell gapCell() {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        return cell;
    }

    /**
     * @param theme    the deployment's palette and fonts
     * @param subLabel extra line below the main label (e.g. total hours), omitted when {@code null}.
     */
    static PdfPCell statCell(PdfTheme theme, int value, String label, String subLabel) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPadding(14f);
        cell.setCellEvent(new PdfTheme.RoundedCellFillEvent(theme.highlight(), 10f));

        Paragraph number = new Paragraph(String.valueOf(value), theme.statNumberFont());
        number.setAlignment(Element.ALIGN_CENTER);
        number.setSpacingAfter(2f);

        Paragraph labelParagraph = new Paragraph();
        labelParagraph.setAlignment(Element.ALIGN_CENTER);
        Chunk labelChunk = new Chunk(label, theme.statLabelFont());
        labelChunk.setCharacterSpacing(1.1f);
        labelParagraph.add(labelChunk);

        cell.addElement(number);
        cell.addElement(labelParagraph);

        if (subLabel != null) {
            Paragraph subLabelParagraph = new Paragraph();
            subLabelParagraph.setAlignment(Element.ALIGN_CENTER);
            subLabelParagraph.setSpacingBefore(3f);
            Chunk subLabelChunk = new Chunk(subLabel, theme.statSubLabelFont());
            subLabelChunk.setCharacterSpacing(0.6f);
            subLabelParagraph.add(subLabelChunk);
            cell.addElement(subLabelParagraph);
        }
        return cell;
    }

    static double totalHeures(List<PosteAffectation> postes) {
        int totalMinutes = 0;
        for (PosteAffectation poste : postes) {
            totalMinutes += poste.getDureeEffectiveMinutes();
        }
        return totalMinutes / 60.0;
    }

    /** Stands this animateur is assigned to, deduplicated, in first-appearance (chronological) order. */
    static List<Stand> distinctStands(List<PosteAffectation> postes) {
        List<Stand> stands = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        for (PosteAffectation poste : postes) {
            Stand stand = poste.getStand();
            if (stand != null && seenIds.add(stand.getId())) {
                stands.add(stand);
            }
        }
        return stands;
    }

    static int distinctStandCount(List<PosteAffectation> postes) {
        Set<String> ids = new LinkedHashSet<>();
        for (PosteAffectation poste : postes) {
            if (poste.getStand() != null) {
                ids.add(poste.getStand().getId());
            }
        }
        return ids.size();
    }

    static int distinctCreneauCount(List<PosteAffectation> postes) {
        Set<Long> ids = new LinkedHashSet<>();
        for (PosteAffectation poste : postes) {
            if (poste.getCreneau() != null) {
                ids.add(poste.getCreneau().getId());
            }
        }
        return ids.size();
    }

    static int distinctDayCount(List<PosteAffectation> postes) {
        Set<Integer> jours = new LinkedHashSet<>();
        for (PosteAffectation poste : postes) {
            if (poste.getCreneau() != null) {
                jours.add(poste.getCreneau().getJour());
            }
        }
        return jours.size();
    }
}
