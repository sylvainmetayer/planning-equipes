package dev.sylvain.planning.service.export;

import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.service.analyse.PauseAnalyzer;
import dev.sylvain.planning.service.referentiel.TypologieLibelles;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;

/**
 * The planning of one animateur as an A4 booklet: an <b>overview</b> first,
 * then the days one by one, then who they work with and where.
 *
 * <p>The former layout was a list — one card per seat, chronologically — and
 * three questions an animateur asks their own planning had no short answer in
 * it: what does my festival look like, where do I have to go, who am I with.
 * Each is now a part of its own: a timeline of every day of the event on page
 * one, the stands grouped by place beside it, and the team-mates gathered at
 * the end instead of repeated shift by shift. What the document <b>says</b> is
 * unchanged — breaks, meal breaks, rest days, a consigne's sentence: this is a
 * layout, not a new content.</p>
 *
 * <p>The colour of a bar carries the stand's <b>typologie</b>
 * ({@link TypologiePalette}), never the stand: twelve stands over six
 * categories give a legend that fits under the timeline.</p>
 *
 * @see AnimateurFeuillePdf the same view folded onto one landscape sheet
 */
@ApplicationScoped
public class AnimateurPlanningPdf implements DocumentAnimateur {

    /** Content width of an A4 portrait page with the margins below. */
    private static final float LARGEUR = PageSize.A4.getWidth() - 80f;

    private final PdfTheme theme;
    private final TypologieLibelles typologies;

    @Inject
    public AnimateurPlanningPdf(PdfTheme theme, TypologieLibelles typologies) {
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
        // The days a consigne governs, and what to print under their date —
        // handed down rather than kept on the bean, which is shared by every
        // build running at once.
        AnimateurPlanningView view = AnimateurPlanningView.build(
                animateurName,
                postes,
                teammatesByPoste,
                joursRepos,
                pauses,
                coupures,
                journeesModifiees,
                TypologiePalette.of(typologies));
        return render(view, lienEspaceAnimateur, provenance);
    }

    byte[] render(AnimateurPlanningView view, String lienEspaceAnimateur, ExportProvenance.Provenance provenance) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 40, 40, 40, 54);
        PdfWriter writer = PdfWriter.getInstance(document, output);
        writer.setPageEvent(theme.footerEvent("planning individuel", Instant.now(), provenance));
        document.open();

        addHeader(document, view, provenance.edition().nom());

        if (view.vide()) {
            document.add(theme.emptyState());
            if (lienEspaceAnimateur != null) {
                document.add(espaceAnimateurCallout(lienEspaceAnimateur));
            }
            document.close();
            return output.toByteArray();
        }

        document.add(frise(view));
        document.add(legende(view));
        document.add(standsByLieu(view));
        // The espace band closes the overview when the overview leaves room
        // for it, and closes the document otherwise: a long édition fills page
        // one, and a band pushed alone onto a page of its own would cost a
        // sheet to say one sentence.
        PdfPTable espace = lienEspaceAnimateur == null ? null : espaceAnimateurCallout(lienEspaceAnimateur);
        boolean espacePlace = espace != null && fitsUnderTheOverview(document, writer, espace);
        if (espacePlace) {
            document.add(espace);
        }

        document.newPage();
        document.add(titreSection("Vos journées", "Le détail de chaque jour, dans l'ordre"));
        for (AnimateurPlanningView.Jour jour : view.jours()) {
            document.add(blocJour(jour));
        }

        document.newPage();
        document.add(titreSection("Avec qui, et où", "Les personnes et les lieux de votre édition"));
        document.add(blocCoequipiers(view));
        if (!view.equipesNombreuses().isEmpty()) {
            document.add(blocEquipesNombreuses(view));
        }
        document.add(lieuxBlock(view));
        if (espace != null && !espacePlace) {
            document.add(espace);
        }

        document.close();
        return output.toByteArray();
    }

    // --- Page 1: the overview -------------------------------------------------

    private void addHeader(Document document, AnimateurPlanningView view, String edition) {
        float pageWidth = document.getPageSize().getWidth();
        float pageHeight = document.getPageSize().getHeight();

        Image strip = theme.strip();
        if (strip != null) {
            float stripWidth = 150f;
            strip.scaleToFit(stripWidth, stripWidth * strip.getHeight() / strip.getWidth());
            strip.setAbsolutePosition(
                    pageWidth - 22f - strip.getScaledWidth(), pageHeight - 20f - strip.getScaledHeight());
            document.add(strip);
        }

        // The édition named right under the name (issue #608): an animateur who
        // came back from one year to the next holds two of these documents, and
        // « PLANNING / Prénom Nom » alone does not tell them apart. Its name
        // alone: the line under it already spells the span out, which is what
        // the full label would repeat.
        document.add(theme.brandHeader(document, 320f, "PLANNING INDIVIDUEL", view.nom(), edition, 4f));

        Paragraph periode = new Paragraph(view.periode(), theme.periodeFont());
        periode.setSpacingAfter(14f);
        document.add(periode);

        document.add(chiffres(view));
    }

    /**
     * The four figures of the overview — hours first, because that is the one
     * an animateur checks against what they agreed to, and days worked, which
     * the former header never gave.
     */
    private PdfPTable chiffres(AnimateurPlanningView view) {
        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(100);
        table.setSpacingAfter(16f);
        table.addCell(chiffre(heures(view.minutesTravaillees()), "heures travaillées", true));
        table.addCell(chiffre(String.valueOf(view.creneaux()), "créneaux", false));
        table.addCell(chiffre(String.valueOf(view.standsDistincts()), "stands", false));
        table.addCell(chiffre(String.valueOf(view.joursTravailles()), "jours travaillés", false));
        return table;
    }

    private PdfPCell chiffre(String valeur, String libelle, boolean premier) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColorBottom(theme.headline());
        cell.setBorderWidthBottom(1f);
        cell.setPaddingBottom(10f);
        cell.setPaddingLeft(premier ? 0f : 12f);
        Paragraph nombre = new Paragraph(valeur, theme.chiffreFont());
        nombre.setSpacingAfter(1f);
        cell.addElement(nombre);
        cell.addElement(new Paragraph(libelle, theme.chiffreLabelFont()));
        return cell;
    }

    /**
     * « Vos 16 jours en un coup d'œil » — one row per day of the event, rest
     * days included, on a shared hour axis. This is the page that answers
     * « which days are heavy, which are light, when do I rest » without
     * reading twenty cards.
     */
    private PdfPTable frise(AnimateurPlanningView view) {
        int debut = view.amplitudeDebutMinutes();
        int fin = view.amplitudeFinMinutes();
        int pas = pasHoraire(debut, fin);

        PdfPTable table = new PdfPTable(new float[] {76f, 400f, 34f});
        table.setTotalWidth(LARGEUR);
        table.setLockedWidth(true);
        table.setSpacingBefore(4f);

        PdfPCell titre = new PdfPCell();
        titre.setBorder(Rectangle.NO_BORDER);
        titre.setColspan(3);
        titre.setPaddingBottom(6f);
        titre.addElement(new Paragraph("Vos " + view.joursTotal() + " jours en un coup d'œil", theme.sectionFont()));
        titre.addElement(new Paragraph("Le détail de chaque journée suit, dans l'ordre", theme.sousTitreFont()));
        table.addCell(titre);

        table.addCell(vide());
        PdfPCell axe = new PdfPCell();
        axe.setBorder(Rectangle.NO_BORDER);
        axe.setFixedHeight(9f);
        axe.setCellEvent(new PdfTheme.FriseAxeEvent(debut, fin, pas, theme.friseLabelFont()));
        table.addCell(axe);
        PdfPCell total = new PdfPCell(new Phrase("Total", theme.friseLabelFont()));
        total.setBorder(Rectangle.NO_BORDER);
        total.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.addCell(total);

        for (AnimateurPlanningView.Jour jour : view.jours()) {
            table.addCell(etiquetteJour(jour));
            table.addCell(jour.repos() ? ligneRepos() : ligneJour(jour, debut, fin, pas));
            PdfPCell heures = new PdfPCell(new Phrase(
                    jour.repos() ? "—" : heures(jour.minutesTravaillees()) + " h",
                    jour.repos() ? theme.friseLabelFont() : theme.friseTotalFont()));
            heures.setBorder(Rectangle.NO_BORDER);
            heures.setHorizontalAlignment(Element.ALIGN_RIGHT);
            heures.setVerticalAlignment(Element.ALIGN_MIDDLE);
            table.addCell(heures);
        }
        return table;
    }

    private PdfPCell etiquetteJour(AnimateurPlanningView.Jour jour) {
        Phrase phrase = new Phrase();
        phrase.add(new Chunk("J" + jour.numero() + "  ", theme.friseLabelFont()));
        phrase.add(new Chunk(AnimateurPlanningView.jourCourt(jour.date()), theme.friseJourFont()));
        PdfPCell cell = new PdfPCell(phrase);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setFixedHeight(11.5f);
        cell.setPaddingLeft(0f);
        if (jour.sousConsigne()) {
            // A day an authority moved is marked on the overview too, not only
            // in its own block: the hours drawn beside it are not the usual ones.
            cell.setCellEvent(new PdfTheme.IconeEvent(PdfTheme.Icone.SOLEIL, theme.accent(), true));
        }
        return cell;
    }

    private PdfPCell ligneJour(AnimateurPlanningView.Jour jour, int debut, int fin, int pas) {
        List<PdfTheme.Barre> barres = new ArrayList<>();
        for (AnimateurPlanningView.Vacation vacation : jour.vacations()) {
            barres.add(new PdfTheme.Barre(
                    vacation.debutMinutes(), vacation.finMinutes(), vacation.couleur(), vacation.standNom(), false));
        }
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setFixedHeight(11.5f);
        cell.setCellEvent(new PdfTheme.FriseEvent(
                barres,
                debut,
                fin,
                pas,
                theme.pill(),
                jour.sousConsigne() ? theme.highlight() : null,
                theme.friseBarreFont()));
        return cell;
    }

    private PdfPCell ligneRepos() {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setFixedHeight(11.5f);
        cell.setCellEvent(new PdfTheme.FriseTextEvent("Repos", theme.friseLabelFont(), theme.voile()));
        return cell;
    }

    /** An axis tick every hour, or every two or three when the event's days are long. */
    private static int pasHoraire(int debut, int fin) {
        int heures = Math.max(1, (fin - debut) / 60);
        if (heures <= 13) {
            return 60;
        }
        return heures <= 24 ? 120 : 180;
    }

    /**
     * What the colours mean: one entry per typologie actually drawn above, then
     * the two marks that are not stands — the meal break and the legal one.
     */
    private PdfPTable legende(AnimateurPlanningView view) {
        PdfPTable table = new PdfPTable(4);
        table.setTotalWidth(LARGEUR);
        table.setLockedWidth(true);
        table.setSpacingBefore(6f);
        table.setSpacingAfter(16f);
        for (TypologiePalette.Entree entree : view.legende()) {
            PdfPCell cell = new PdfPCell(new Phrase(entree.libelle(), theme.legendeFont()));
            cell.setBorder(Rectangle.NO_BORDER);
            cell.setPaddingLeft(11f);
            cell.setPaddingBottom(3f);
            cell.setCellEvent(new PdfTheme.PastilleEvent(entree.couleur(), 6f));
            table.addCell(cell);
        }
        int reste = view.legende().size() % 4;
        for (int i = 0; reste != 0 && i < 4 - reste; i++) {
            table.addCell(vide());
        }
        PdfPCell note = new PdfPCell(new Phrase(
                "La couleur d'une barre est la catégorie de jeu du stand. Repas, pauses et jours de repos sont "
                        + "écrits dans le détail de la journée ; un soleil marque une journée dont les horaires "
                        + "ont été modifiés par une consigne.",
                theme.sousTitreFont()));
        note.setColspan(4);
        note.setBorder(Rectangle.NO_BORDER);
        note.setPaddingTop(4f);
        table.addCell(note);
        return table;
    }

    /**
     * « Vos 12 stands, par lieu » — the answer to « where do I have to go »,
     * which a chronological list never gives: nine of the thirteen days at the
     * same place is a fact about the whole event.
     */
    private PdfPTable standsByLieu(AnimateurPlanningView view) {
        PdfPTable table = new PdfPTable(new float[] {250f, 160f, 100f});
        table.setTotalWidth(LARGEUR);
        table.setLockedWidth(true);

        PdfPCell titre = new PdfPCell();
        titre.setBorder(Rectangle.NO_BORDER);
        titre.setColspan(3);
        titre.setPaddingBottom(6f);
        titre.addElement(new Paragraph("Vos " + view.standsDistincts() + " stands, par lieu", theme.sectionFont()));
        table.addCell(titre);

        for (AnimateurPlanningView.Lieu lieu : view.lieux()) {
            PdfPCell entete = new PdfPCell(new Phrase(lieu.nom(), theme.calloutTitleFont()));
            entete.setColspan(3);
            entete.setBorder(Rectangle.NO_BORDER);
            entete.setBackgroundColor(theme.voile());
            entete.setPadding(4f);
            entete.setPaddingLeft(14f);
            entete.setCellEvent(new PdfTheme.IconeEvent(PdfTheme.Icone.REPERE, theme.muted(), false));
            table.addCell(entete);
            for (AnimateurPlanningView.StandUsage stand : lieu.stands()) {
                PdfPCell nom = new PdfPCell(new Phrase(stand.nom(), theme.standFont()));
                nom.setBorder(Rectangle.NO_BORDER);
                nom.setPaddingLeft(13f);
                nom.setPaddingTop(4f);
                nom.setPaddingBottom(4f);
                nom.setCellEvent(new PdfTheme.PastilleEvent(stand.couleur(), 6f));
                table.addCell(nom);

                PdfPCell typologie = new PdfPCell(new Phrase(
                        stand.typologieLibelle() == null ? "" : stand.typologieLibelle(), theme.locationFont()));
                typologie.setBorder(Rectangle.NO_BORDER);
                typologie.setPaddingTop(4f);
                table.addCell(typologie);

                PdfPCell compte = new PdfPCell(
                        new Phrase(stand.creneaux() + " × · " + heures(stand.minutes()) + " h", theme.locationFont()));
                compte.setBorder(Rectangle.NO_BORDER);
                compte.setHorizontalAlignment(Element.ALIGN_RIGHT);
                compte.setPaddingTop(4f);
                table.addCell(compte);
            }
        }
        return table;
    }

    /**
     * Whether a table still fits under what the <b>overview</b> has already
     * been given. Two conditions, and the second is the one that bites: a page
     * one filled to the last line has already carried the flow onto page two,
     * where anything fits — and where the band would sit alone.
     */
    private static boolean fitsUnderTheOverview(Document document, PdfWriter writer, PdfPTable table) {
        if (writer.getPageNumber() != 1) {
            return false;
        }
        float restant = writer.getVerticalPosition(true) - document.bottomMargin();
        // Plus the band's own spacing before it, which the table height does not carry.
        return table.getTotalHeight() + 16f <= restant;
    }

    /**
     * Personal espace link (issue #165), closing the overview: click it on
     * screen, or type the printed URL — it opens their planning and the échange
     * request form, no account needed.
     */
    private PdfPTable espaceAnimateurCallout(String lien) {
        PdfPTable qr = QrCodeEspace.bloc(lien, 62f, theme.headline());
        PdfPTable card = qr == null ? new PdfPTable(1) : new PdfPTable(new float[] {LARGEUR - 78f, 78f});
        card.setTotalWidth(LARGEUR);
        card.setLockedWidth(true);
        card.setSpacingBefore(16f);
        card.getDefaultCell().setBorder(Rectangle.NO_BORDER);

        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setBackgroundColor(theme.voile());
        cell.setPadding(10f);
        Paragraph titre = new Paragraph();
        Chunk titreChunk = new Chunk("VOTRE ESPACE EN LIGNE", theme.calloutTitleFont());
        titreChunk.setCharacterSpacing(1.1f);
        titre.add(titreChunk);
        titre.setSpacingAfter(3f);
        cell.addElement(titre);
        Paragraph action =
                new Paragraph("Consulter mon planning et proposer un échange de créneau", theme.calloutTextFont());
        cell.addElement(action);
        Chunk url = new Chunk(lien, theme.footerFont());
        url.setAnchor(lien);
        Paragraph lienParagraphe = new Paragraph(url);
        lienParagraphe.setSpacingBefore(2f);
        cell.addElement(lienParagraphe);
        card.addCell(cell);

        if (qr != null) {
            // The link is the credential and it is long: nobody types it off a
            // printed sheet. The URL stays written under it all the same — for
            // a photocopy too pale to scan, and for a reader who wants to see
            // where the code leads before following it.
            PdfPCell code = new PdfPCell();
            code.setBorder(Rectangle.NO_BORDER);
            code.setBackgroundColor(theme.voile());
            code.setPadding(8f);
            code.setHorizontalAlignment(Element.ALIGN_RIGHT);
            code.addElement(qr);
            Paragraph legende = new Paragraph("Scannez", theme.lienLabelFont());
            legende.setAlignment(Element.ALIGN_CENTER);
            legende.setSpacingBefore(2f);
            code.addElement(legende);
            card.addCell(code);
        }
        return card;
    }

    // --- Pages « Vos journées » -----------------------------------------------------

    private PdfPTable titreSection(String titre, String sousTitre) {
        PdfPTable table = new PdfPTable(1);
        table.setTotalWidth(LARGEUR);
        table.setLockedWidth(true);
        table.setSpacingAfter(12f);
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColorBottom(theme.headline());
        cell.setBorderWidthBottom(1f);
        cell.setPaddingBottom(6f);
        cell.addElement(new Paragraph(titre, theme.nameFont()));
        cell.addElement(new Paragraph(sousTitre, theme.sousTitreFont()));
        table.addCell(cell);
        return table;
    }

    /**
     * One day, as one table: its date, its total, its shifts and the breaks
     * under them. Kept together so a day is never cut between two pages —
     * an animateur reading « 14:00 » at the top of a page must not have to
     * turn back to know which day it is.
     */
    private PdfPTable blocJour(AnimateurPlanningView.Jour jour) {
        PdfPTable table = new PdfPTable(new float[] {70f, 375f, 45f});
        table.setTotalWidth(LARGEUR);
        table.setLockedWidth(true);
        table.setSpacingAfter(10f);
        table.setKeepTogether(true);

        PdfPCell date = new PdfPCell();
        date.setColspan(2);
        date.setBorder(Rectangle.NO_BORDER);
        date.setPaddingBottom(4f);
        Paragraph dateLigne = new Paragraph(PdfTheme.formatFrenchDayDate(jour.date()), theme.jourTitreFont());
        date.addElement(dateLigne);
        date.addElement(new Paragraph("Jour " + jour.numero(), theme.jourNumeroFont()));
        table.addCell(date);

        PdfPCell total = new PdfPCell(
                new Phrase(jour.repos() ? "" : heures(jour.minutesTravaillees()) + " h", theme.jourTotalFont()));
        total.setBorder(Rectangle.NO_BORDER);
        total.setHorizontalAlignment(Element.ALIGN_RIGHT);
        total.setVerticalAlignment(Element.ALIGN_BOTTOM);
        total.setPaddingBottom(4f);
        table.addCell(total);

        // A day a consigne governs (issue #4) says so under its date: the hours
        // printed below are not the usual ones, and the person must read why
        // from the document itself, not only from a mail.
        if (jour.sousConsigne()) {
            PdfPCell bandeau = new PdfPCell(new Phrase(jour.note(), theme.bandeauFont()));
            bandeau.setColspan(3);
            bandeau.setBorder(Rectangle.NO_BORDER);
            bandeau.setBackgroundColor(theme.highlight());
            bandeau.setPadding(4f);
            bandeau.setPaddingLeft(14f);
            bandeau.setCellEvent(new PdfTheme.IconeEvent(PdfTheme.Icone.SOLEIL, theme.accent(), false));
            table.addCell(bandeau);
        }

        if (jour.repos()) {
            PdfPCell repos = new PdfPCell(new Phrase("Repos", theme.emptyStateFont()));
            repos.setColspan(3);
            repos.setBorder(Rectangle.NO_BORDER);
            repos.setBackgroundColor(theme.voile());
            repos.setPadding(8f);
            repos.setPaddingLeft(10f);
            table.addCell(repos);
            return table;
        }

        for (AnimateurPlanningView.Vacation vacation : jour.vacations()) {
            table.addCell(celluleHeures(vacation));
            table.addCell(celluleVacation(vacation));
            table.addCell(celluleDuree(vacation));
            for (String note : vacation.notes()) {
                PdfPCell ligne = new PdfPCell(new Phrase(note, theme.calloutTitleFont()));
                ligne.setColspan(3);
                ligne.setBorder(Rectangle.NO_BORDER);
                ligne.setPaddingLeft(10f);
                ligne.setPaddingTop(3f);
                ligne.setPaddingBottom(5f);
                table.addCell(ligne);
            }
        }
        return table;
    }

    private PdfPCell celluleHeures(AnimateurPlanningView.Vacation vacation) {
        PdfPCell cell = new PdfPCell();
        // Three cells, one card: the outline is drawn around the row and never
        // between its columns.
        cell.setBorder(Rectangle.LEFT | Rectangle.TOP | Rectangle.BOTTOM);
        cell.setBorderColor(theme.pill());
        cell.setBackgroundColor(theme.cardBackground());
        cell.setPadding(7f);
        cell.setPaddingLeft(10f);
        Paragraph debut = new Paragraph(vacation.debut().format(PdfTheme.TIME_FORMAT), theme.heureFont());
        debut.setSpacingAfter(1f);
        cell.addElement(debut);
        cell.addElement(new Paragraph("→ " + vacation.fin().format(PdfTheme.TIME_FORMAT), theme.heureFinFont()));
        return cell;
    }

    private PdfPCell celluleVacation(AnimateurPlanningView.Vacation vacation) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.TOP | Rectangle.BOTTOM);
        cell.setBorderColor(theme.pill());
        cell.setBackgroundColor(theme.cardBackground());
        cell.setPadding(7f);
        cell.setPaddingLeft(13f);
        cell.setCellEvent(new PdfTheme.PastilleEvent(vacation.couleur(), 6f));

        Paragraph stand = new Paragraph(vacation.standNom(), theme.standFont());
        stand.setSpacingAfter(1f);
        cell.addElement(stand);

        List<String> details = new ArrayList<>();
        if (vacation.typologieLibelle() != null) {
            details.add(vacation.typologieLibelle());
        }
        details.add(vacation.lieu());
        Paragraph ligneDetail = new Paragraph(String.join("  ·  ", details), theme.locationFont());
        if (vacation.urlLieu() != null) {
            // The location stays clickable to OpenStreetMap when the stand's
            // emplacement is geocoded.
            ligneDetail = new Paragraph();
            ligneDetail.add(new Chunk(
                    vacation.typologieLibelle() == null ? "" : vacation.typologieLibelle() + "  ·  ",
                    theme.locationFont()));
            Chunk lieu = new Chunk(vacation.lieu(), theme.locationFont());
            lieu.setAnchor(vacation.urlLieu());
            ligneDetail.add(lieu);
        }
        cell.addElement(ligneDetail);

        Paragraph equipe = new Paragraph(vacation.equipeTexte(), theme.teamFont());
        equipe.setSpacingBefore(2f);
        cell.addElement(equipe);
        return cell;
    }

    private PdfPCell celluleDuree(AnimateurPlanningView.Vacation vacation) {
        PdfPCell cell = new PdfPCell(new Phrase(heures(vacation.dureeMinutes()) + " h", theme.friseTotalFont()));
        cell.setBorder(Rectangle.RIGHT | Rectangle.TOP | Rectangle.BOTTOM);
        cell.setBorderColor(theme.pill());
        cell.setBackgroundColor(theme.cardBackground());
        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPadding(7f);
        return cell;
    }

    // --- Page « Avec qui, et où » ---------------------------------------------------

    /**
     * The team-mates gathered and counted, the most frequent first: « five
     * times Justine » is what the chronological pages can never say, however
     * many times they print the name.
     */
    private PdfPTable blocCoequipiers(AnimateurPlanningView view) {
        PdfPTable table = new PdfPTable(new float[] {34f, 476f});
        table.setTotalWidth(LARGEUR);
        table.setLockedWidth(true);
        table.setSpacingAfter(16f);

        PdfPCell titre = new PdfPCell();
        titre.setColspan(2);
        titre.setBorder(Rectangle.NO_BORDER);
        titre.setPaddingBottom(6f);
        titre.addElement(new Paragraph("Vos coéquipiers sur les stands", theme.sectionFont()));
        titre.addElement(new Paragraph(sousTitreCoequipiers(view), theme.sousTitreFont()));
        table.addCell(titre);

        for (AnimateurPlanningView.Coequipier coequipier : view.coequipiers()) {
            PdfPCell fois = new PdfPCell(new Phrase(coequipier.fois() + "×", theme.compteurFont()));
            fois.setBorder(Rectangle.NO_BORDER);
            fois.setPaddingTop(4f);
            table.addCell(fois);

            PdfPCell qui = new PdfPCell();
            qui.setBorder(Rectangle.NO_BORDER);
            qui.setPaddingTop(4f);
            qui.setPaddingBottom(3f);
            Paragraph nom = new Paragraph(coequipier.nom(), theme.standFont());
            nom.setSpacingAfter(1f);
            qui.addElement(nom);
            qui.addElement(new Paragraph(String.join(" · ", coequipier.moments()), theme.locationFont()));
            table.addCell(qui);
        }
        return table;
    }

    /** « 24 personnes · les plus fréquents d'abord », or that there is nobody. */
    static String sousTitreCoequipiers(AnimateurPlanningView view) {
        int nombre = view.coequipiers().size();
        if (nombre == 0) {
            return "Personne d'autre sur vos stands";
        }
        return nombre + (nombre > 1 ? " personnes" : " personne") + " · les plus fréquents d'abord";
    }

    /**
     * The set-up and the take-down: a hundred people at once, so the document
     * says how many and not who — a nominal list of a hundred names is a page
     * nobody reads, and printing it was the old layout's mistake.
     */
    private PdfPTable blocEquipesNombreuses(AnimateurPlanningView view) {
        PdfPTable table = new PdfPTable(new float[] {60f, 450f});
        table.setTotalWidth(LARGEUR);
        table.setLockedWidth(true);
        table.setSpacingAfter(16f);

        PdfPCell titre = new PdfPCell();
        titre.setColspan(2);
        titre.setBorder(Rectangle.NO_BORDER);
        titre.setPaddingBottom(6f);
        titre.addElement(new Paragraph("Les vacations en nombre", theme.sectionFont()));
        titre.addElement(new Paragraph(
                "Au-delà de " + AnimateurPlanningView.SEUIL_NOMS
                        + " personnes sur un même stand, l'effectif remplace la liste des noms",
                theme.sousTitreFont()));
        table.addCell(titre);

        for (AnimateurPlanningView.EquipeNombreuse equipe : view.equipesNombreuses()) {
            PdfPCell effectif = new PdfPCell(new Phrase(String.valueOf(equipe.effectif()), theme.effectifFont()));
            effectif.setBorder(Rectangle.NO_BORDER);
            effectif.setPaddingTop(4f);
            table.addCell(effectif);

            PdfPCell quoi = new PdfPCell();
            quoi.setBorder(Rectangle.NO_BORDER);
            quoi.setVerticalAlignment(Element.ALIGN_MIDDLE);
            quoi.setPaddingTop(6f);
            Paragraph nom = new Paragraph(equipe.standNom(), theme.standFont());
            nom.setSpacingAfter(1f);
            quoi.addElement(nom);
            quoi.addElement(new Paragraph(
                    AnimateurPlanningView.jourCourt(equipe.date()) + " · "
                            + equipe.debut().format(PdfTheme.TIME_FORMAT) + " → "
                            + equipe.fin().format(PdfTheme.TIME_FORMAT) + " · personnes présentes",
                    theme.locationFont()));
            table.addCell(quoi);
        }
        return table;
    }

    /** Each place and the days spent there — the same fact as page one, said as dates. */
    private PdfPTable lieuxBlock(AnimateurPlanningView view) {
        PdfPTable table = new PdfPTable(1);
        table.setTotalWidth(LARGEUR);
        table.setLockedWidth(true);

        PdfPCell titre = new PdfPCell();
        titre.setBorder(Rectangle.NO_BORDER);
        titre.setPaddingBottom(6f);
        titre.addElement(new Paragraph("Vos lieux, jour par jour", theme.sectionFont()));
        table.addCell(titre);

        for (AnimateurPlanningView.LieuJours lieu : view.daysByLieu()) {
            PdfPCell cell = new PdfPCell();
            cell.setBorder(Rectangle.NO_BORDER);
            cell.setPaddingTop(4f);
            cell.setPaddingBottom(3f);
            cell.setPaddingLeft(10f);
            cell.setCellEvent(new PdfTheme.IconeEvent(PdfTheme.Icone.REPERE, theme.muted(), false));
            Paragraph nom = new Paragraph(lieu.nom(), theme.standFont());
            nom.setSpacingAfter(1f);
            cell.addElement(nom);
            List<String> dates = new ArrayList<>();
            for (LocalDate date : lieu.dates()) {
                dates.add(AnimateurPlanningView.jourCourt(date));
            }
            cell.addElement(new Paragraph(
                    lieu.dates().size()
                            + (lieu.dates().size() > 1 ? " jours · " : " jour · ")
                            + String.join(", ", dates),
                    theme.locationFont()));
            table.addCell(cell);
        }
        return table;
    }

    private PdfPCell vide() {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        return cell;
    }

    /** « 7,5 » rather than « 7.5 », and « 7 » rather than « 7,0 ». */
    static String heures(int minutes) {
        double heures = minutes / 60.0;
        return heures == Math.rint(heures)
                ? String.valueOf((int) heures)
                : String.format(Locale.FRENCH, "%.1f", heures);
    }
}
