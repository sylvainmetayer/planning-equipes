package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The deployment's name, and the two promises made about it.
 *
 * <p>It is <b>never blank</b>: an operator who sets the variable to an empty
 * string, or to a stray space, would otherwise send mails whose subject starts
 * with a dash and dump a SQL file headed by nothing. And it is <b>the single
 * place</b> a subject line is assembled, so every mail of the application
 * groups together in an inbox instead of drifting apart one caller at a time.
 */
class ProductNameTest {

    private static final String NEUTRE = "Planning Équipes";

    @Test
    void unNomConfigureEstCeluiQuOnRend() {
        assertThat(new ProductName("Festival Machin").value()).isEqualTo("Festival Machin");
    }

    @Test
    void lesEspacesDeBordSontRetires() {
        // A hand-copied environment variable readily drags a space along; it
        // would land in the middle of "Name — subject".
        assertThat(new ProductName("  Festival Machin  ").value()).isEqualTo("Festival Machin");
    }

    @Test
    void unNomVideOuBlancRetombeSurLIdentiteNeutre() {
        assertThat(new ProductName("").value()).isEqualTo(NEUTRE);
        assertThat(new ProductName("   ").value()).isEqualTo(NEUTRE);
        assertThat(new ProductName((String) null).value()).isEqualTo(NEUTRE);
    }

    @Test
    void leRepliNeutreEstLeMemeQueCeluiDuConstructeur() {
        assertThat(ProductName.neutral().value()).isEqualTo(new ProductName("").value());
    }

    @Test
    void unSujetPorteLeNomDuProduitPuisSonObjet() {
        assertThat(new ProductName("Festival Machin").subject("votre planning individuel"))
                .isEqualTo("Festival Machin — votre planning individuel");
    }

    @Test
    void unSujetConstruitSurUnNomVideNeCommencePasParUnTiret() {
        // The very case this fallback exists to prevent.
        assertThat(new ProductName("").subject("mail de test"))
                .isEqualTo(NEUTRE + " — mail de test")
                .doesNotStartWith("—");
    }
}
