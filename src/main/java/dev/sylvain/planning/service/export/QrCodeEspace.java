package dev.sylvain.planning.service.export;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPCellEvent;
import org.openpdf.text.pdf.PdfPTable;

/**
 * The espace animateur's link as a QR code, printed beside the URL it carries.
 *
 * <p>The link is the credential and it is long: typing it off a printed sheet
 * is what nobody does, so the paper has to be scannable. The URL stays written
 * underneath — a phone with no camera left, a photocopy too pale to scan, and
 * a reader who wants to see where the code leads before following it.</p>
 *
 * <p>The modules are <b>drawn</b>, not rasterised: a QR code is a grid of
 * squares, and drawing them keeps it sharp at any zoom and at any printer's
 * resolution, where an embedded bitmap would have to guess one.</p>
 */
final class QrCodeEspace {

    /**
     * Correction level M — a quarter of the code may be lost and still read.
     * L would make the grid coarser (easier to scan, less robust); a planning
     * folded in a pocket for two weeks is exactly what M is for.
     */
    private static final ErrorCorrectionLevel CORRECTION = ErrorCorrectionLevel.M;

    /** No quiet zone from the encoder: the cell's own padding is the margin. */
    private static final int MARGE_MODULES = 0;

    private QrCodeEspace() {}

    /**
     * The code's module grid, or {@code null} when there is no link to encode
     * or the encoder refuses it — a document is still worth producing without
     * its QR code.
     */
    static BitMatrix matrice(String lien) {
        if (lien == null || lien.isBlank()) {
            return null;
        }
        try {
            return new QRCodeWriter()
                    .encode(
                            lien,
                            BarcodeFormat.QR_CODE,
                            1,
                            1,
                            Map.of(
                                    EncodeHintType.ERROR_CORRECTION, CORRECTION,
                                    EncodeHintType.MARGIN, MARGE_MODULES,
                                    EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name()));
        } catch (WriterException | IllegalArgumentException _) {
            return null;
        }
    }

    /**
     * A square cell holding the code at {@code cote} points, or {@code null}
     * when the link cannot be encoded — the caller then prints its callout
     * without a code rather than without a link.
     */
    static PdfPTable bloc(String lien, float cote, Color encre) {
        BitMatrix matrice = matrice(lien);
        if (matrice == null) {
            return null;
        }
        PdfPTable table = new PdfPTable(1);
        table.setTotalWidth(cote);
        table.setLockedWidth(true);
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setFixedHeight(cote);
        cell.setCellEvent(new QrEvent(matrice, encre));
        table.addCell(cell);
        return table;
    }

    /** Fills its cell with the code's modules, as many small squares. */
    private record QrEvent(BitMatrix matrice, Color encre) implements PdfPCellEvent {

        @Override
        public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
            int modules = matrice.getWidth();
            float cote = Math.min(position.getWidth(), position.getHeight());
            float module = cote / modules;
            float gauche = position.getLeft() + (position.getWidth() - cote) / 2f;
            float haut = position.getTop() - (position.getHeight() - cote) / 2f;

            PdfContentByte canvas = canvases[PdfPTable.BACKGROUNDCANVAS];
            canvas.saveState();
            // White under the code: printed over a tinted callout, a scanner
            // reads contrast, and a pale background is where it stops reading.
            canvas.setColorFill(Color.WHITE);
            canvas.rectangle(gauche, haut - cote, cote, cote);
            canvas.fill();
            canvas.setColorFill(encre);
            for (int ligne = 0; ligne < matrice.getHeight(); ligne++) {
                for (int colonne = 0; colonne < modules; colonne++) {
                    if (matrice.get(colonne, ligne)) {
                        // A hair of overlap: adjacent modules drawn edge to
                        // edge leave white seams on some renderers.
                        canvas.rectangle(
                                gauche + colonne * module, haut - (ligne + 1) * module, module + 0.05f, module + 0.05f);
                    }
                }
            }
            canvas.fill();
            canvas.restoreState();
        }
    }
}
