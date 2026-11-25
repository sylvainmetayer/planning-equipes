package dev.sylvain.planning.service.export;

import dev.sylvain.planning.service.analyse.RapportConformite;
import dev.sylvain.planning.service.analyse.RapportConformite.Personne;
import dev.sylvain.planning.service.edition.EtiquetteEdition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import org.openpdf.text.Document;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;

/**
 * The compliance report as the document handed to a labour inspector: the
 * ceilings it was checked against, the breaches of each legal rule, one line
 * per animateur and an explicit section for the minors. Built on
 * {@link PdfTheme}, like every other document of the application.
 *
 * <p>It carries a name and the status of minor — never a date of birth, an
 * address or a contact: it is a document that leaves the organisation.</p>
 */
@ApplicationScoped
public class RapportConformitePdf {

    private final PdfTheme theme;

    @Inject
    public RapportConformitePdf(PdfTheme theme) {
        this.theme = theme;
    }

    public byte[] render(RapportConformite rapport) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4.rotate(), 36, 36, 36, 50);
        PdfWriter writer = PdfWriter.getInstance(document, output);
        PdfTheme.describe(document, writer, "Rapport de conformité — " + rapport.editionNom());
        ExportProvenance.Provenance provenance = new ExportProvenance.Provenance(
                new EtiquetteEdition(rapport.editionNom(), null, null),
                rapport.publieLe(),
                ExportProvenance.Nature.PUBLICATION);
        writer.setPageEvent(theme.footerEvent("rapport de conformité", rapport.genereLe(), provenance));
        document.open();

        document.add(new Paragraph("RAPPORT DE CONFORMITÉ", theme.brandLabelFont()));
        Paragraph nom = new Paragraph(rapport.editionNom() == null ? "" : rapport.editionNom(), theme.nameFont());
        nom.setSpacingAfter(2f);
        document.add(nom);
        document.add(new Paragraph(
                "Plan publié le "
                        + PdfTheme.GENERATED_AT_FORMAT.format(
                                rapport.publieLe().atZone(ZoneId.of(PdfTheme.EVENT_TIMEZONE)))
                        + " — travail effectif ; dimanche, jours fériés et nuit comptés en amplitude.",
                theme.editionFont()));

        section(
                document,
                "Plafonds en vigueur au "
                        + PdfTheme.GENERATED_AT_FORMAT.format(
                                rapport.genereLe().atZone(ZoneId.of(PdfTheme.EVENT_TIMEZONE))));
        document.add(new Paragraph(
                "Les paramètres légaux de l'édition tels qu'ils sont à cette date, qui peuvent différer de ceux "
                        + "du jour de la publication.",
                theme.editionFont()));
        document.add(plafonds(rapport));
        for (String ecart : rapport.plafonds().ecartsAuxDefauts()) {
            document.add(new Paragraph("Valeur modifiée : " + ecart, theme.tableAlertFont()));
        }

        section(document, "Synthèse");
        RapportConformite.Synthese s = rapport.synthese();
        document.add(new Paragraph(
                s.personnes() + " personne(s) planifiée(s), dont " + s.mineurs() + " mineur(s). " + s.ecarts()
                        + " écart(s) aux règles légales, " + s.semainesEnDepassement()
                        + " semaine(s) au-dessus du plafond hebdomadaire. Travail effectif : " + h(s.heuresTravail())
                        + " h ; dimanche : " + h(s.heuresDimanche()) + " h ; jours fériés : " + h(s.heuresFerie())
                        + " h ; nuit après 22 h : " + h(s.heuresNuit()) + " h.",
                theme.tableBodyFont()));
        document.add(regles(rapport));

        section(document, "Personnes");
        document.add(personnes(rapport.personnes()));

        section(document, "Mineurs");
        List<Personne> mineurs =
                rapport.personnes().stream().filter(Personne::mineur).toList();
        if (mineurs.isEmpty()) {
            document.add(new Paragraph("Aucun mineur n'est planifié dans cette édition.", theme.tableBodyFont()));
        } else {
            document.add(mineursSection(mineurs));
        }

        List<Personne> avecEcarts = rapport.personnes().stream()
                .filter(p -> !p.ecartsDetail().isEmpty())
                .toList();
        if (!avecEcarts.isEmpty()) {
            section(document, "Détail des écarts");
            PdfPTable table = new PdfPTable(new float[] {22f, 28f, 50f});
            table.setWidthPercentage(100f);
            table.setHeaderRows(1);
            header(table, "Personne", "Règle", "Constat");
            for (Personne p : avecEcarts) {
                p.ecartsDetail().forEach(e -> {
                    table.addCell(cell(p.nom(), theme.tableBodyFont()));
                    table.addCell(cell(e.libelle(), theme.tableBodyFont()));
                    table.addCell(cell(e.texte(), theme.tableAlertFont()));
                });
            }
            document.add(table);
        }
        document.close();
        return output.toByteArray();
    }

    private void section(Document document, String titre) {
        Paragraph p = new Paragraph(titre, theme.sectionFont());
        p.setSpacingBefore(14f);
        p.setSpacingAfter(4f);
        document.add(p);
    }

    private PdfPTable plafonds(RapportConformite rapport) {
        PdfPTable table = new PdfPTable(new float[] {25f, 25f, 25f, 25f});
        table.setWidthPercentage(100f);
        header(table, "Semaine (majeur)", "Semaine (mineur)", "Vacation", "Repos quotidien minimal");
        var p = rapport.plafonds();
        for (double v : new double[] {
            p.hebdomadaireMajeurHeures(), p.hebdomadaireMineurHeures(), p.vacationHeures(), p.reposQuotidienHeures()
        }) {
            table.addCell(cell(RapportConformite.hoursLabel(v), theme.tableBodyFont()));
        }
        return table;
    }

    private PdfPTable regles(RapportConformite rapport) {
        PdfPTable table = new PdfPTable(new float[] {80f, 20f});
        table.setWidthPercentage(100f);
        table.setSpacingBefore(6f);
        header(table, "Règle légale", "Écarts");
        rapport.regles().forEach(r -> {
            table.addCell(cell(r.libelle(), theme.tableBodyFont()));
            table.addCell(
                    cell(String.valueOf(r.ecarts()), r.ecarts() > 0 ? theme.tableAlertFont() : theme.tableBodyFont()));
        });
        return table;
    }

    private PdfPTable personnes(List<Personne> personnes) {
        PdfPTable table = new PdfPTable(new float[] {22f, 7f, 9f, 9f, 14f, 9f, 9f, 9f, 12f});
        table.setWidthPercentage(100f);
        table.setHeaderRows(1);
        header(
                table,
                "Personne",
                "Mineur",
                "Total (h)",
                "Plafond (h/sem.)",
                "Semaines au-dessus",
                "Dim. (h)",
                "Fériés (h)",
                "Nuit (h)",
                "Repos min. (h)");
        for (Personne p : personnes) {
            boolean depasse = !p.semainesEnDepassement().isEmpty();
            table.addCell(cell(p.nom(), theme.tableBodyFont()));
            table.addCell(cell(p.mineur() ? "oui" : "non", theme.tableBodyFont()));
            table.addCell(cell(h(p.heuresTotal()), theme.tableBodyFont()));
            table.addCell(cell(h(p.plafondHebdomadaire()), theme.tableBodyFont()));
            table.addCell(cell(
                    depasse ? String.join(", ", p.semainesEnDepassement()) : "—",
                    depasse ? theme.tableAlertFont() : theme.tableBodyFont()));
            table.addCell(cell(h(p.heuresDimanche()), theme.tableBodyFont()));
            table.addCell(cell(h(p.heuresFerie()), theme.tableBodyFont()));
            table.addCell(cell(h(p.heuresNuit()), theme.tableBodyFont()));
            table.addCell(cell(
                    p.reposQuotidienMinHeures() == null ? "—" : h(p.reposQuotidienMinHeures()), theme.tableBodyFont()));
        }
        return table;
    }

    /** Each minor: either the explicit confirmation, or the breaches. */
    private PdfPTable mineursSection(List<Personne> mineurs) {
        PdfPTable table = new PdfPTable(new float[] {25f, 75f});
        table.setWidthPercentage(100f);
        header(table, "Personne", "Constat");
        for (Personne p : mineurs) {
            table.addCell(cell(p.nom(), theme.tableBodyFont()));
            if (p.ecartsDetail().isEmpty() && p.semainesEnDepassement().isEmpty()) {
                table.addCell(cell(
                        "Aucun écart relevé : pas de poste de nuit ni de jour férié, durées quotidienne et "
                                + "hebdomadaire sous les plafonds, repos respectés.",
                        theme.tableBodyFont()));
            } else {
                StringBuilder constat = new StringBuilder();
                p.semainesEnDepassement()
                        .forEach(w -> constat.append("Semaine ")
                                .append(w)
                                .append(" au-dessus du plafond de ")
                                .append(h(p.plafondHebdomadaire()))
                                .append(" h. "));
                p.ecartsDetail()
                        .forEach(e -> constat.append(e.libelle())
                                .append(" : ")
                                .append(e.texte())
                                .append(". "));
                table.addCell(cell(constat.toString().trim(), theme.tableAlertFont()));
            }
        }
        return table;
    }

    private void header(PdfPTable table, String... titres) {
        for (String titre : titres) {
            PdfPCell c = cell(titre, theme.tableHeaderFont());
            c.setBackgroundColor(theme.pill());
            table.addCell(c);
        }
    }

    private static PdfPCell cell(String texte, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(texte, font));
        cell.setBorder(Rectangle.BOTTOM);
        cell.setPadding(4f);
        return cell;
    }

    private static String h(double heures) {
        return String.format(Locale.FRANCE, "%.2f", heures);
    }
}
