package dev.sylvain.planning.service;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.openpdf.text.Chunk;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Tout le planning dans un seul PDF paysage, pour l'organisateur plutôt que
 * pour les animateurs : qui tient quel siège, partout, d'un coup.
 *
 * <p>Les mêmes affectations y sont posées deux fois, parce que les deux
 * questions qu'on se pose sur le terrain ne sont pas la même : <b>par
 * journée</b> répond à « qui est où en ce moment », <b>par stand</b> à « qui
 * fait tourner ce stand sur toute la durée ». Les sièges que personne ne tient
 * sont écrits en rouge sur leur ligne plutôt qu'absents en silence : un stand
 * non tenu est exactement ce que l'organisateur ouvre ce document pour
 * trouver.</p>
 */
@ApplicationScoped
public class PlanningPdfGlobal {

    byte[] construire(PlanningFestival planning) {
        List<LigneAffectation> lignes = lignesAffectation(planning);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4.rotate(), 34, 34, 34, 50);
        PdfWriter writer = PdfWriter.getInstance(document, output);
        writer.setPageEvent(new ChartePdf.FooterEvent("Festival — Centre-ville · planning global généré le "
                + ChartePdf.GENERATED_AT_FORMAT.format(Instant.now().atZone(ZoneOffset.systemDefault()))));
        document.open();

        addGlobalHeader(document, planning, lignes);
        if (lignes.isEmpty()) {
            document.add(ChartePdf.emptyState());
        } else {
            addSectionParJournee(document, lignes);
            document.newPage();
            addSectionParStand(document, lignes);
        }

        document.close();
        return output.toByteArray();
    }

    /**
     * One line per stand × vacation × open segment: the seats of a same stand
     * on a same window are one line holding every name, not one line each.
     * Mirrors how the calendars group them, and is what makes the document
     * readable at festival scale (3 500 seats becoming ~2 000 lines).
     */
    private List<LigneAffectation> lignesAffectation(PlanningFestival planning) {
        Map<String, LigneAffectation> parCle = new LinkedHashMap<>();
        for (PosteAffectation poste : planning.getPostes()) {
            Creneau creneau = poste.getCreneau();
            Stand stand = poste.getStand();
            if (creneau == null || stand == null) {
                continue;
            }
            String cle = stand.getId() + "@" + creneau.getId() + "#" + poste.heureDebutEffectif() + "-"
                    + poste.heureFinEffectif();
            LigneAffectation ligne = parCle.computeIfAbsent(cle,
                    ignored -> new LigneAffectation(stand, creneau, poste.heureDebutEffectif(),
                            poste.heureFinEffectif(), new ArrayList<>(), new int[] { 0 }));
            ligne.sieges()[0]++;
            if (poste.getAnimateur() != null) {
                ligne.animateurs().add(poste.getAnimateur().nomAffiche());
            }
        }
        List<LigneAffectation> lignes = new ArrayList<>(parCle.values());
        for (LigneAffectation ligne : lignes) {
            ligne.animateurs().sort(String::compareToIgnoreCase);
        }
        return lignes;
    }

    /**
     * One stand × vacation line of the global export.
     *
     * @param sieges seats generated for that line, as a single-element array so
     *               the count can be incremented while grouping — never the
     *               stand's {@code effectifMin}, which a meal-pause coverage
     *               vacation deliberately halves
     */
    private record LigneAffectation(Stand stand, Creneau creneau, LocalTime debut, LocalTime fin,
            List<String> animateurs, int[] sieges) {

        boolean incomplete() {
            return animateurs.size() < sieges[0];
        }
    }

    private void addGlobalHeader(Document document, PlanningFestival planning, List<LigneAffectation> lignes) {
        document.add(ChartePdf.brandHeader(document, 420f, "PLANNING GLOBAL", "Toutes les affectations", 18f));

        PdfPTable stats = new PdfPTable(new float[] { 10f, 0.6f, 10f, 0.6f, 10f, 0.6f, 10f });
        stats.setWidthPercentage(100);
        stats.addCell(StatistiquesPostes.statCell(StatistiquesPostes.distinctDayCount(planning.getPostes()), "JOURS", null));
        stats.addCell(StatistiquesPostes.gapCell());
        stats.addCell(StatistiquesPostes.statCell(StatistiquesPostes.distinctStandCount(planning.getPostes()), "STANDS", null));
        stats.addCell(StatistiquesPostes.gapCell());
        int sieges = lignes.stream().mapToInt(ligne -> ligne.sieges()[0]).sum();
        int pourvus = lignes.stream().mapToInt(ligne -> ligne.animateurs().size()).sum();
        stats.addCell(StatistiquesPostes.statCell(sieges, "SIÈGES", String.format(Locale.FRENCH, "%d POURVUS", pourvus)));
        stats.addCell(StatistiquesPostes.gapCell());
        stats.addCell(StatistiquesPostes.statCell(planning.getAnimateurs().size(), "ANIMATEURS",
                String.format(Locale.FRENCH, "TOTAL %.0f H TRAVAILLÉES", StatistiquesPostes.totalHeures(planning.getPostes()))));
        stats.setSpacingAfter(20f);
        document.add(stats);
    }

    /** Section 1: chronological reading — one page per festival day. */
    private void addSectionParJournee(Document document, List<LigneAffectation> lignes) {
        document.add(sectionTitle("Planning par journée"));
        List<LocalDate> dates = lignes.stream()
                .map(ligne -> ligne.creneau().getDate())
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        boolean premier = true;
        for (LocalDate date : dates) {
            if (!premier) {
                document.newPage();
            }
            premier = false;
            List<LigneAffectation> duJour = lignes.stream()
                    .filter(ligne -> date.equals(ligne.creneau().getDate()))
                    .sorted(Comparator.comparing(LigneAffectation::debut)
                            .thenComparing(ligne -> ligne.stand().getNom(), String.CASE_INSENSITIVE_ORDER))
                    .toList();
            document.add(groupTitle("Jour " + duJour.get(0).creneau().getJour() + " — " + ChartePdf.formatFrenchDayDate(date)));

            PdfPTable table = globalTable(new float[] { 1.3f, 3.2f, 2.4f, 6f, 1f },
                    "Horaires", "Stand", "Emplacement", "Animateurs", "Effectif");
            for (LigneAffectation ligne : duJour) {
                table.addCell(bodyCell(formatHoraires(ligne)));
                table.addCell(bodyCell(ligne.stand().getNom()));
                table.addCell(bodyCell(emplacementNom(ligne.stand())));
                table.addCell(animateursCell(ligne));
                table.addCell(effectifCell(ligne));
            }
            document.add(table);
        }
    }

    /** Section 2: per-stand reading — every vacation of a stand, in one block. */
    private void addSectionParStand(Document document, List<LigneAffectation> lignes) {
        document.add(sectionTitle("Planning par stand"));
        List<Stand> stands = lignes.stream()
                .map(LigneAffectation::stand)
                .collect(Collectors.toMap(Stand::getId, stand -> stand, (left, right) -> left,
                        LinkedHashMap::new))
                .values().stream()
                .sorted(Comparator.comparing(Stand::getNom, String.CASE_INSENSITIVE_ORDER))
                .toList();
        for (Stand stand : stands) {
            List<LigneAffectation> duStand = lignes.stream()
                    .filter(ligne -> ligne.stand().getId().equals(stand.getId()))
                    .sorted(Comparator.comparing((LigneAffectation ligne) -> ligne.creneau().getDate())
                            .thenComparing(LigneAffectation::debut))
                    .toList();
            document.add(groupTitle(stand.getNom() + "  ·  " + emplacementNom(stand)));

            PdfPTable table = globalTable(new float[] { 2.6f, 1.3f, 8.5f, 1f },
                    "Journée", "Horaires", "Animateurs", "Effectif");
            for (LigneAffectation ligne : duStand) {
                table.addCell(bodyCell("J" + ligne.creneau().getJour() + " · "
                        + ChartePdf.DATE_FORMAT.format(ligne.creneau().getDate())));
                table.addCell(bodyCell(formatHoraires(ligne)));
                table.addCell(animateursCell(ligne));
                table.addCell(effectifCell(ligne));
            }
            document.add(table);
        }
    }

    private Paragraph sectionTitle(String text) {
        Paragraph paragraph = new Paragraph(text, ChartePdf.NAME_FONT);
        paragraph.setSpacingAfter(12f);
        return paragraph;
    }

    private Paragraph groupTitle(String text) {
        Paragraph paragraph = new Paragraph(text, ChartePdf.DATE_FONT);
        paragraph.setSpacingBefore(10f);
        paragraph.setSpacingAfter(6f);
        // Never leave a group heading alone at the bottom of a page.
        paragraph.setKeepTogether(true);
        return paragraph;
    }

    /** Table with a coloured header row repeated on every page it spills onto. */
    private PdfPTable globalTable(float[] widths, String... entetes) {
        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        table.setSpacingAfter(8f);
        for (String entete : entetes) {
            PdfPCell cell = new PdfPCell(new Phrase(entete, ChartePdf.TABLE_HEADER_FONT));
            cell.setBackgroundColor(ChartePdf.YELLOW);
            cell.setBorderColor(ChartePdf.PILL_BACKGROUND);
            cell.setPadding(5f);
            table.addCell(cell);
        }
        return table;
    }

    private PdfPCell bodyCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text == null ? "—" : text, ChartePdf.TABLE_BODY_FONT));
        cell.setBorderColor(ChartePdf.PILL_BACKGROUND);
        cell.setPadding(4f);
        return cell;
    }

    /** Names on the line, or the shortfall spelled out in red when seats are left unfilled. */
    private PdfPCell animateursCell(LigneAffectation ligne) {
        if (ligne.animateurs().isEmpty()) {
            PdfPCell cell = new PdfPCell(new Phrase("Aucun animateur affecté", ChartePdf.TABLE_ALERT_FONT));
            cell.setBorderColor(ChartePdf.PILL_BACKGROUND);
            cell.setPadding(4f);
            return cell;
        }
        Paragraph paragraph = new Paragraph(String.join(", ", ligne.animateurs()), ChartePdf.TABLE_BODY_FONT);
        if (ligne.incomplete()) {
            paragraph.add(new Chunk("  ·  " + (ligne.sieges()[0] - ligne.animateurs().size())
                    + " siège(s) non pourvu(s)", ChartePdf.TABLE_ALERT_FONT));
        }
        PdfPCell cell = new PdfPCell(paragraph);
        cell.setBorderColor(ChartePdf.PILL_BACKGROUND);
        cell.setPadding(4f);
        return cell;
    }

    private PdfPCell effectifCell(LigneAffectation ligne) {
        PdfPCell cell = new PdfPCell(new Phrase(ligne.animateurs().size() + "/" + ligne.sieges()[0],
                ligne.incomplete() ? ChartePdf.TABLE_ALERT_FONT : ChartePdf.TABLE_BODY_FONT));
        cell.setBorderColor(ChartePdf.PILL_BACKGROUND);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setPadding(4f);
        return cell;
    }

    private String formatHoraires(LigneAffectation ligne) {
        return ChartePdf.TIME_FORMAT.format(ligne.debut()) + "–" + ChartePdf.TIME_FORMAT.format(ligne.fin());
    }

    private String emplacementNom(Stand stand) {
        Emplacement emplacement = stand.getEmplacement();
        return emplacement == null || emplacement.getNom() == null || emplacement.getNom().isBlank()
                ? "—"
                : emplacement.getNom();
    }
}
