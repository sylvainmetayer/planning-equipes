package dev.sylvain.planning.service.export;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.openpdf.text.Document;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;

/**
 * The sheet a responsable de stand prints for their stand: its published shifts
 * day by day, the seats still empty, and the names only when the content says
 * so — it is given the same decision the screen is, never one of its own.
 */
@ApplicationScoped
public class StandAffichettePdf {

    private final PdfTheme theme;

    @Inject
    public StandAffichettePdf(PdfTheme theme) {
        this.theme = theme;
    }

    /**
     * @param personnes first and last names, empty unless the stand is shown by name
     */
    public record Vacation(LocalDateTime debut, LocalDateTime fin, int pourvus, int vides, List<String> personnes) {}

    public record Contenu(String standNom, String emplacementNom, boolean nominatif, List<Vacation> vacations) {}

    public byte[] render(Contenu contenu, ExportProvenance.Provenance provenance) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 40, 40, 40, 54);
        PdfWriter writer = PdfWriter.getInstance(document, output);
        PdfTheme.describe(document, writer, "Affichette — " + contenu.standNom());
        writer.setPageEvent(theme.footerEvent("affichette de stand", Instant.now(), provenance));
        document.open();

        document.add(new Paragraph("AFFICHETTE DE STAND", theme.brandLabelFont()));
        Paragraph nom = new Paragraph(contenu.standNom(), theme.nameFont());
        nom.setSpacingAfter(2f);
        document.add(nom);
        if (contenu.emplacementNom() != null) {
            document.add(new Paragraph(contenu.emplacementNom(), theme.locationFont()));
        }
        document.add(new Paragraph(provenance.editionNom(), theme.editionFont()));
        if (contenu.vacations().isEmpty()) {
            document.add(theme.emptyState());
            document.close();
            return output.toByteArray();
        }
        Map<LocalDate, List<Vacation>> parJour = new TreeMap<>();
        contenu.vacations()
                .forEach(v -> parJour.computeIfAbsent(v.debut().toLocalDate(), ignore -> new java.util.ArrayList<>())
                        .add(v));
        parJour.forEach((jour, vacations) -> {
            Paragraph titre = new Paragraph(PdfTheme.formatFrenchDayDate(jour), theme.jourTitreFont());
            titre.setSpacingBefore(14f);
            titre.setSpacingAfter(4f);
            document.add(titre);
            document.add(table(vacations, contenu.nominatif()));
        });
        document.close();
        return output.toByteArray();
    }

    private PdfPTable table(List<Vacation> vacations, boolean nominatif) {
        PdfPTable table = new PdfPTable(nominatif ? new float[] {22f, 28f, 50f} : new float[] {35f, 65f});
        table.setWidthPercentage(100f);
        for (Vacation v : vacations) {
            table.addCell(cell(
                    PdfTheme.TIME_FORMAT.format(v.debut()) + " – " + PdfTheme.TIME_FORMAT.format(v.fin()),
                    theme.heureFont()));
            int places = v.pourvus() + v.vides();
            String effectif = places
                    + (places > 1 ? " places" : " place")
                    + (v.vides() > 0 ? " — " + v.vides() + " à pourvoir" : "");
            table.addCell(cell(effectif, theme.tableBodyFont()));
            if (nominatif) {
                table.addCell(cell(String.join(", ", v.personnes()), theme.tableBodyFont()));
            }
        }
        return table;
    }

    private static PdfPCell cell(String texte, org.openpdf.text.Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(texte, font));
        cell.setBorder(Rectangle.BOTTOM);
        cell.setPadding(5f);
        return cell;
    }
}
