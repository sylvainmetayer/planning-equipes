package dev.sylvain.planning.service.export;

import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.service.analyse.PauseAnalyzer;
import dev.sylvain.planning.service.referentiel.TypologieLibelles;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.openpdf.text.Chunk;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;

/**
 * The same planning as {@link AnimateurPlanningPdf}, folded onto <b>one</b>
 * landscape sheet printed on both sides: a calendar on the front, the teams and
 * the places on the back.
 *
 * <p>The booklet is more comfortable to read; this one fits in a pocket and
 * costs the organisation one print per person instead of five. Both render the
 * same {@link AnimateurPlanningView}, so neither can say something the other
 * does not.</p>
 */
@ApplicationScoped
public class AnimateurFeuillePdf implements DocumentAnimateur {

    private static final float LARGEUR = PageSize.A4.getHeight() - 60f;

    private static final List<DayOfWeek> SEMAINE = List.of(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY,
            DayOfWeek.SATURDAY,
            DayOfWeek.SUNDAY);

    private static final List<String> JOURS_SEMAINE =
            List.of("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche");

    private final PdfTheme theme;
    private final TypologieLibelles typologies;

    @Inject
    public AnimateurFeuillePdf(PdfTheme theme, TypologieLibelles typologies) {
        this.theme = theme;
        this.typologies = typologies;
    }

    @Override
    public byte[] render(
            String animateurName,
            List<PosteAffectation> postes,
            Map<String, List<String>> teammatesByPoste,
            List<PlanningExportService.JourRepos> joursRepos,
            List<PauseAnalyzer.PauseAnimateurView> pauses,
            List<PauseAnalyzer.CoupureAnimateurView> coupures,
            String lienEspaceAnimateur,
            ExportProvenance.Provenance provenance,
            Map<LocalDate, String> journeesModifiees) {
        return render(
                AnimateurPlanningView.build(
                        animateurName,
                        postes,
                        teammatesByPoste,
                        joursRepos,
                        pauses,
                        coupures,
                        journeesModifiees,
                        TypologiePalette.of(typologies)),
                lienEspaceAnimateur,
                provenance);
    }

    byte[] render(AnimateurPlanningView view, String lienEspaceAnimateur, ExportProvenance.Provenance provenance) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4.rotate(), 30, 30, 26, 46);
        PdfWriter writer = PdfWriter.getInstance(document, output);
        PdfTheme.describe(document, writer, "Planning individuel — " + view.nom());
        writer.setPageEvent(theme.footerEvent("planning individuel", Instant.now(), provenance));
        document.open();

        document.add(entete(view, lienEspaceAnimateur, provenance.edition().nom()));
        if (view.vide()) {
            document.add(theme.emptyState());
            document.close();
            return output.toByteArray();
        }
        document.add(calendrier(view));
        document.add(reperes(view));

        document.newPage();
        document.add(titreVerso(view));
        document.add(verso(view));

        document.close();
        return output.toByteArray();
    }

    // --- Front side: the calendar ------------------------------------------------------

    private PdfPTable entete(AnimateurPlanningView view, String lien, String edition) {
        PdfPTable qr = QrCodeEspace.bloc(lien, 48f, theme.headline());
        PdfPTable table =
                qr == null ? new PdfPTable(new float[] {346f, 436f}) : new PdfPTable(new float[] {330f, 396f, 56f});
        table.setTotalWidth(LARGEUR);
        table.setLockedWidth(true);
        table.setSpacingAfter(10f);

        PdfPCell identite = new PdfPCell();
        identite.setBorder(Rectangle.NO_BORDER);
        Paragraph eyebrow = new Paragraph();
        Chunk chunk = new Chunk("PLANNING INDIVIDUEL", theme.brandLabelFont());
        chunk.setCharacterSpacing(1.4f);
        eyebrow.add(chunk);
        eyebrow.setSpacingAfter(2f);
        identite.addElement(eyebrow);
        Paragraph nom = new Paragraph(view.nom(), theme.nameFont());
        nom.setSpacingAfter(2f);
        identite.addElement(nom);
        // The édition under the name, as the booklet says it (issue #608): two
        // years of sheets are otherwise the same sheet twice. The span is on
        // the next line, so its name is all this one owes.
        if (edition != null && !edition.isBlank()) {
            identite.addElement(new Paragraph(edition, theme.editionFont()));
        }
        identite.addElement(new Paragraph(view.periode(), theme.periodeFont()));
        table.addCell(identite);

        PdfPCell droite = new PdfPCell();
        droite.setBorder(Rectangle.NO_BORDER);
        droite.setVerticalAlignment(Element.ALIGN_MIDDLE);
        Paragraph chiffres = new Paragraph();
        chiffres.setAlignment(Element.ALIGN_RIGHT);
        chiffres.add(new Chunk(AnimateurPlanningPdf.heures(view.minutesTravaillees()) + " h  ", theme.chiffreFont()));
        chiffres.add(new Chunk("travaillées   ·   ", theme.chiffreLabelFont()));
        chiffres.add(new Chunk(view.creneaux() + "  ", theme.chiffreFont()));
        chiffres.add(new Chunk("créneaux   ·   ", theme.chiffreLabelFont()));
        chiffres.add(new Chunk(view.standsDistincts() + "  ", theme.chiffreFont()));
        chiffres.add(new Chunk("stands   ·   ", theme.chiffreLabelFont()));
        chiffres.add(new Chunk(view.joursTravailles() + "  ", theme.chiffreFont()));
        chiffres.add(new Chunk("jours travaillés", theme.chiffreLabelFont()));
        droite.addElement(chiffres);
        if (lien != null) {
            Paragraph lienParagraphe = new Paragraph();
            lienParagraphe.setAlignment(Element.ALIGN_RIGHT);
            lienParagraphe.setSpacingBefore(3f);
            lienParagraphe.add(new Chunk("Votre espace en ligne : ", theme.chiffreLabelFont()));
            Chunk url = new Chunk(lien, theme.footerFont());
            url.setAnchor(lien);
            lienParagraphe.add(url);
            droite.addElement(lienParagraphe);
        }
        table.addCell(droite);

        if (qr != null) {
            // Scanned off the folded sheet: the link underneath is long, and
            // the sheet is read standing up between two shifts.
            PdfPCell code = new PdfPCell();
            code.setBorder(Rectangle.NO_BORDER);
            code.setHorizontalAlignment(Element.ALIGN_RIGHT);
            code.setVerticalAlignment(Element.ALIGN_MIDDLE);
            code.addElement(qr);
            table.addCell(code);
        }
        return table;
    }

    /**
     * A week per row, Monday to Sunday: the shape the animateur already has in
     * their head, and the one that shows at a glance which week-end is taken.
     */
    private PdfPTable calendrier(AnimateurPlanningView view) {
        Map<LocalDate, AnimateurPlanningView.Jour> parDate = new LinkedHashMap<>();
        for (AnimateurPlanningView.Jour jour : view.jours()) {
            parDate.put(jour.date(), jour);
        }
        LocalDate premierLundi = view.premierJour().with(DayOfWeek.MONDAY);
        LocalDate dernierDimanche = view.dernierJour().with(DayOfWeek.SUNDAY);
        int semaines = (int) (ChronoUnit.WEEKS.between(premierLundi, dernierDimanche) + 1);

        PdfPTable table = new PdfPTable(7);
        table.setTotalWidth(LARGEUR);
        table.setLockedWidth(true);
        table.setSpacingAfter(8f);
        // A long édition runs to more weeks than one side holds: the last row
        // then splits rather than jumping whole and leaving a blank page.
        table.setSplitLate(false);
        for (String jour : JOURS_SEMAINE) {
            PdfPCell entete = new PdfPCell(new Phrase(jour, theme.tableMiniHeaderFont()));
            entete.setBorder(Rectangle.BOTTOM);
            entete.setBorderColorBottom(theme.headline());
            entete.setPadding(3f);
            table.addCell(entete);
        }

        float hauteur = Math.min(120f, Math.max(52f, 330f / semaines));
        for (int semaine = 0; semaine < semaines; semaine++) {
            for (int index = 0; index < SEMAINE.size(); index++) {
                LocalDate date = premierLundi.plusWeeks(semaine).plusDays(index);
                table.addCell(caseJour(parDate.get(date), date, view, hauteur));
            }
        }
        return table;
    }

    private PdfPCell caseJour(
            AnimateurPlanningView.Jour jour, LocalDate date, AnimateurPlanningView view, float hauteur) {
        PdfPCell cell = new PdfPCell();
        cell.setBorderColor(theme.pill());
        cell.setPadding(4f);
        // A minimum, never a ceiling: the weeks line up on the light days, and
        // a day with three shifts makes its own row taller. Fixed, the box cut
        // what did not fit — and a planning that silently drops a team line is
        // worse than a sheet that runs a little longer.
        cell.setMinimumHeight(hauteur);

        if (jour == null) {
            // A day outside the event: the box exists so the weeks line up,
            // and says nothing.
            cell.setBackgroundColor(theme.voile());
            cell.addElement(new Paragraph(String.valueOf(date.getDayOfMonth()), theme.friseLabelFont()));
            return cell;
        }

        Paragraph entete = new Paragraph();
        entete.add(new Chunk("J" + jour.numero() + " ", theme.friseLabelFont()));
        entete.add(new Chunk(AnimateurPlanningView.jourCourt(jour.date()), theme.friseJourFont()));
        if (!jour.repos()) {
            entete.add(new Chunk(
                    "   " + AnimateurPlanningPdf.heures(jour.minutesTravaillees()) + " h", theme.friseTotalFont()));
        }
        entete.setSpacingAfter(1f);
        cell.addElement(entete);

        if (jour.repos()) {
            cell.setBackgroundColor(theme.voile());
            cell.addElement(new Paragraph("Repos", theme.emptyStateFont()));
            return cell;
        }
        if (jour.sousConsigne()) {
            cell.setBackgroundColor(theme.highlight());
        }

        List<PdfTheme.Barre> barres = new ArrayList<>();
        for (AnimateurPlanningView.Vacation vacation : jour.vacations()) {
            barres.add(new PdfTheme.Barre(
                    vacation.debutMinutes(), vacation.finMinutes(), vacation.couleur(), null, false));
        }
        // The mini timeline is a table of its own inside the box: laid out by
        // the engine, under the date line, rather than drawn at a distance
        // from the top of the cell that any longer date would push into.
        PdfPTable frise = new PdfPTable(1);
        frise.setWidthPercentage(100);
        PdfPCell bande = new PdfPCell();
        bande.setBorder(Rectangle.NO_BORDER);
        bande.setFixedHeight(5f);
        bande.setCellEvent(new PdfTheme.FriseEvent(
                barres,
                view.amplitudeDebutMinutes(),
                view.amplitudeFinMinutes(),
                pas(view),
                theme.pill(),
                null,
                theme.friseBarreFont()));
        frise.addCell(bande);
        frise.setSpacingAfter(3f);
        cell.addElement(frise);

        for (AnimateurPlanningView.Vacation vacation : jour.vacations()) {
            Paragraph ligne = new Paragraph();
            ligne.add(new Chunk(
                    heureCompacte(vacation.debut()) + "–" + heureCompacte(vacation.fin()) + " ",
                    theme.tableMiniHeaderFont()));
            ligne.add(new Chunk(vacation.standNom(), theme.tableMiniFont()));
            ligne.setSpacingAfter(0.5f);
            cell.addElement(ligne);
            Paragraph equipe = new Paragraph(vacation.equipeTexte(), theme.friseLabelFont());
            equipe.setIndentationLeft(4f);
            equipe.setSpacingAfter(1f);
            cell.addElement(equipe);
            for (String note : vacation.notes()) {
                Paragraph pause = new Paragraph(note, theme.legendeFont());
                pause.setIndentationLeft(4f);
                pause.setSpacingAfter(1f);
                cell.addElement(pause);
            }
        }
        return cell;
    }

    private int pas(AnimateurPlanningView view) {
        int heures = Math.max(1, (view.amplitudeFinMinutes() - view.amplitudeDebutMinutes()) / 60);
        return heures <= 13 ? 180 : 360;
    }

    /** « 10h », « 10h30 » — the form a note pinned on a fridge is written in. */
    static String heureCompacte(LocalTime heure) {
        if (heure == null) {
            return "";
        }
        return heure.getMinute() == 0
                ? heure.getHour() + "h"
                : heure.getHour() + "h" + String.format("%02d", heure.getMinute());
    }

    /** The key to the sheet: what the mini timeline shows, the colours, and the days a consigne moved. */
    private PdfPTable reperes(AnimateurPlanningView view) {
        PdfPTable table = new PdfPTable(new float[] {150f, 632f});
        table.setTotalWidth(LARGEUR);
        table.setLockedWidth(true);

        PdfPCell titre = new PdfPCell();
        titre.setBorder(Rectangle.NO_BORDER);
        titre.addElement(new Paragraph("Repères", theme.sectionFont()));
        titre.addElement(new Paragraph(
                "La barre sous la date couvre "
                        + (view.amplitudeDebutMinutes() / 60 % 24) + "h – " + (view.amplitudeFinMinutes() / 60 % 24)
                        + "h ; chaque segment est un créneau.",
                theme.sousTitreFont()));
        table.addCell(titre);

        PdfPCell droite = new PdfPCell();
        droite.setBorder(Rectangle.NO_BORDER);
        PdfPTable legende = new PdfPTable(6);
        legende.setWidthPercentage(100);
        for (TypologiePalette.Entree entree : view.legende()) {
            PdfPCell cell = new PdfPCell(new Phrase(entree.libelle(), theme.legendeFont()));
            cell.setBorder(Rectangle.NO_BORDER);
            cell.setPaddingLeft(10f);
            cell.setPaddingBottom(2f);
            cell.setCellEvent(new PdfTheme.PastilleEvent(entree.couleur(), 6f));
            legende.addCell(cell);
        }
        int reste = view.legende().size() % 6;
        for (int i = 0; reste != 0 && i < 6 - reste; i++) {
            PdfPCell vide = new PdfPCell();
            vide.setBorder(Rectangle.NO_BORDER);
            legende.addCell(vide);
        }
        droite.addElement(legende);
        for (String consigne : consignes(view)) {
            Paragraph ligne = new Paragraph(consigne, theme.bandeauFont());
            ligne.setSpacingBefore(2f);
            droite.addElement(ligne);
        }
        table.addCell(droite);
        return table;
    }

    /** « 22, 23, 24 sept. : Horaires modifiés — … », one line per consigne of the period. */
    private List<String> consignes(AnimateurPlanningView view) {
        Map<String, List<String>> parNote = new LinkedHashMap<>();
        for (AnimateurPlanningView.Jour jour : view.jours()) {
            if (jour.sousConsigne()) {
                parNote.computeIfAbsent(jour.note(), ignored -> new ArrayList<>())
                        .add(AnimateurPlanningView.jourCourt(jour.date()));
            }
        }
        List<String> lignes = new ArrayList<>();
        parNote.forEach((note, dates) -> lignes.add(String.join(", ", dates) + " : " + note));
        return lignes;
    }

    // --- Back side: teams and places ---------------------------------------------------

    private PdfPTable titreVerso(AnimateurPlanningView view) {
        PdfPTable table = new PdfPTable(1);
        table.setTotalWidth(LARGEUR);
        table.setLockedWidth(true);
        table.setSpacingAfter(10f);
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColorBottom(theme.headline());
        cell.setBorderWidthBottom(1f);
        cell.setPaddingBottom(5f);
        cell.addElement(new Paragraph("Avec qui, et où", theme.nameFont()));
        cell.addElement(new Paragraph(view.nom() + " · " + view.periode(), theme.sousTitreFont()));
        table.addCell(cell);
        return table;
    }

    private PdfPTable verso(AnimateurPlanningView view) {
        PdfPTable table = new PdfPTable(new float[] {255f, 255f, 272f});
        table.setTotalWidth(LARGEUR);
        table.setLockedWidth(true);
        // A row taller than what is left of the page would jump whole to the
        // next one, leaving this side blank under its title.
        table.setSplitLate(false);

        // The team-mates run down two columns rather than one, so a roster of
        // thirty still leaves the sheet two-sided.
        List<AnimateurPlanningView.Coequipier> coequipiers = view.coequipiers();
        int moitie = (coequipiers.size() + 1) / 2;
        table.addCell(
                colonneCoequipiers(coequipiers.subList(0, moitie), AnimateurPlanningPdf.sousTitreCoequipiers(view)));
        table.addCell(colonneCoequipiers(coequipiers.subList(moitie, coequipiers.size()), null));

        PdfPCell droite = new PdfPCell();
        droite.setBorder(Rectangle.NO_BORDER);
        if (!view.equipesNombreuses().isEmpty()) {
            droite.addElement(sousTitre(
                    "Montage, démontage et grandes équipes",
                    "Au-delà de " + AnimateurPlanningView.SEUIL_NOMS + " personnes, l'effectif remplace les noms"));
            for (AnimateurPlanningView.EquipeNombreuse equipe : view.equipesNombreuses()) {
                Paragraph ligne = new Paragraph();
                ligne.add(new Chunk(equipe.effectif() + "  ", theme.compteurFont()));
                ligne.add(new Chunk(equipe.standNom(), theme.tableMiniHeaderFont()));
                ligne.setSpacingBefore(2f);
                droite.addElement(ligne);
                Paragraph quand = new Paragraph(
                        AnimateurPlanningView.jourCourt(equipe.date()) + " · " + heureCompacte(equipe.debut()) + " → "
                                + heureCompacte(equipe.fin()) + " · personnes présentes",
                        theme.legendeFont());
                quand.setIndentationLeft(18f);
                droite.addElement(quand);
            }
        }
        droite.addElement(sousTitre(
                "Vos stands et vos lieux",
                view.standsDistincts() + (view.standsDistincts() > 1 ? " stands" : " stand")));
        for (AnimateurPlanningView.Lieu lieu : view.lieux()) {
            Paragraph nomLieu = new Paragraph(lieu.nom(), theme.tableMiniHeaderFont());
            nomLieu.setSpacingBefore(4f);
            droite.addElement(nomLieu);
            for (AnimateurPlanningView.StandUsage stand : lieu.stands()) {
                Paragraph ligne = new Paragraph();
                ligne.add(new Chunk(stand.nom(), theme.tableMiniFont()));
                ligne.add(new Chunk(
                        "  " + (stand.typologieLibelle() == null ? "" : stand.typologieLibelle() + " · ")
                                + stand.creneaux() + " × · " + AnimateurPlanningPdf.heures(stand.minutes()) + " h",
                        theme.legendeFont()));
                ligne.setIndentationLeft(8f);
                droite.addElement(ligne);
            }
        }
        Paragraph jours = new Paragraph();
        jours.setSpacingBefore(6f);
        for (AnimateurPlanningView.LieuJours lieu : view.daysByLieu()) {
            jours.add(new Chunk(
                    lieu.nom() + " : " + lieu.dates().size() + (lieu.dates().size() > 1 ? " jours\n" : " jour\n"),
                    theme.legendeFont()));
        }
        droite.addElement(jours);
        table.addCell(droite);
        return table;
    }

    /**
     * Half of the team-mates, in one column. The second half carries no title
     * — but it carries a blank of the title's own height, so both columns
     * start their first name on the same line.
     */
    private PdfPCell colonneCoequipiers(List<AnimateurPlanningView.Coequipier> coequipiers, String sousTitre) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPaddingRight(12f);
        cell.addElement(
                sousTitre == null ? sousTitre(" ", " ") : sousTitre("Vos coéquipiers sur les stands", sousTitre));
        for (AnimateurPlanningView.Coequipier coequipier : coequipiers) {
            Paragraph ligne = new Paragraph();
            ligne.add(new Chunk(coequipier.fois() + "×  ", theme.compteurFont()));
            ligne.add(new Chunk(coequipier.nom(), theme.tableMiniHeaderFont()));
            ligne.setSpacingBefore(2f);
            cell.addElement(ligne);
            Paragraph moments = new Paragraph(String.join(" · ", coequipier.moments()), theme.legendeFont());
            moments.setIndentationLeft(18f);
            cell.addElement(moments);
        }
        return cell;
    }

    private Paragraph sousTitre(String titre, String detail) {
        Paragraph paragraphe = new Paragraph();
        paragraphe.add(new Chunk(titre + "\n", theme.sectionFont()));
        paragraphe.add(new Chunk(detail, theme.sousTitreFont()));
        paragraphe.setSpacingBefore(8f);
        paragraphe.setSpacingAfter(3f);
        return paragraphe;
    }
}
