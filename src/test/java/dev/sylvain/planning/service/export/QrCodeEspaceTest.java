package dev.sylvain.planning.service.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.awt.Color;
import org.junit.jupiter.api.Test;

/**
 * The QR code is the one thing on the document a machine reads, so it is
 * checked the way a phone checks it: the matrix is decoded back to the link it
 * was built from. A code that draws beautifully and decodes to nothing is the
 * failure this test exists to catch.
 */
class QrCodeEspaceTest {

    private static final String LIEN = "https://planning.example.org/animateur/6f2a1d3e-88c4-4a11-9f0b-2c7d5e1a4b90";

    @Test
    void leCodeSeRelitCommeLeLienQuilPorte() throws Exception {
        BitMatrix matrice = QrCodeEspace.matrice(LIEN);

        assertThat(matrice).isNotNull();
        assertThat(relire(matrice)).isEqualTo(LIEN);
    }

    /** No link, no code — and a document that prints its callout without one. */
    @Test
    void sansLienIlNyAPasDeCode() {
        assertThat(QrCodeEspace.matrice(null)).isNull();
        assertThat(QrCodeEspace.matrice("  ")).isNull();
        assertThat(QrCodeEspace.bloc(null, 60f, Color.BLACK)).isNull();
    }

    /** With a link, the caller gets a square block it can place in a cell. */
    @Test
    void avecUnLienLeBlocEstCarreEtALaTailleDemandee() {
        assertThat(QrCodeEspace.bloc(LIEN, 62f, Color.BLACK)).isNotNull();
        assertThat(QrCodeEspace.bloc(LIEN, 62f, Color.BLACK).getTotalWidth()).isEqualTo(62f);
    }

    /** Decodes the matrix as a scanner would: black modules on a white ground. */
    private static String relire(BitMatrix matrice)
            throws NotFoundException, com.google.zxing.FormatException, com.google.zxing.ChecksumException {
        // Two things a reader needs and the matrix does not carry: the quiet
        // zone the encoder was told to leave out — the cell's padding is that
        // margin on paper — and several pixels per module, since a scanner
        // locates the finder patterns by their proportions.
        int echelle = 4;
        int marge = 4 * echelle;
        int cote = matrice.getWidth() * echelle + 2 * marge;
        int[] pixels = new int[cote * cote];
        java.util.Arrays.fill(pixels, 0xFFFFFFFF);
        for (int ligne = 0; ligne < matrice.getHeight(); ligne++) {
            for (int colonne = 0; colonne < matrice.getWidth(); colonne++) {
                if (!matrice.get(colonne, ligne)) {
                    continue;
                }
                for (int y = 0; y < echelle; y++) {
                    for (int x = 0; x < echelle; x++) {
                        pixels[(ligne * echelle + y + marge) * cote + colonne * echelle + x + marge] = 0xFF000000;
                    }
                }
            }
        }
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(cote, cote, pixels)));
        return new QRCodeReader().decode(bitmap).getText();
    }
}
