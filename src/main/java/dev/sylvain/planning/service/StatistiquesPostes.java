package dev.sylvain.planning.service;

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

/**
 * Ce qu'un ensemble de sièges représente — combien de jours, de stands, de
 * créneaux, d'heures — et la tuile qui l'affiche.
 *
 * <p>Le PDF individuel et le PDF global posent la même rangée de tuiles en
 * en-tête, sur des ensembles différents : l'un les affectations d'une
 * personne, l'autre celles du festival entier. Compter et afficher au même
 * endroit est ce qui garantit que « 3 JOURS » veut dire la même chose sur les
 * deux documents.</p>
 */
final class StatistiquesPostes {

    private StatistiquesPostes() {
    }

    static PdfPCell gapCell() {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        return cell;
    }

    /** @param subLabel extra line below the main label (e.g. total hours), omitted when {@code null}. */
    static PdfPCell statCell(int value, String label, String subLabel) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPadding(14f);
        cell.setCellEvent(new ChartePdf.RoundedCellFillEvent(ChartePdf.YELLOW, 10f));

        Paragraph number = new Paragraph(String.valueOf(value), ChartePdf.STAT_NUMBER_FONT);
        number.setAlignment(Element.ALIGN_CENTER);
        number.setSpacingAfter(2f);

        Paragraph labelParagraph = new Paragraph();
        labelParagraph.setAlignment(Element.ALIGN_CENTER);
        Chunk labelChunk = new Chunk(label, ChartePdf.STAT_LABEL_FONT);
        labelChunk.setCharacterSpacing(1.1f);
        labelParagraph.add(labelChunk);

        cell.addElement(number);
        cell.addElement(labelParagraph);

        if (subLabel != null) {
            Paragraph subLabelParagraph = new Paragraph();
            subLabelParagraph.setAlignment(Element.ALIGN_CENTER);
            subLabelParagraph.setSpacingBefore(3f);
            Chunk subLabelChunk = new Chunk(subLabel, ChartePdf.STAT_SUBLABEL_FONT);
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

    /** Stand names this animateur is assigned to, deduplicated, in first-appearance (chronological) order. */
    static List<String> distinctStandNames(List<PosteAffectation> postes) {
        List<String> names = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        for (PosteAffectation poste : postes) {
            Stand stand = poste.getStand();
            if (stand != null && seenIds.add(stand.getId())) {
                names.add(stand.getNom());
            }
        }
        return names;
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
