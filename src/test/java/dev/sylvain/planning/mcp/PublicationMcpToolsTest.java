package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.mcp.PublicationMcpTools.DestinatairePublicationView;
import dev.sylvain.planning.mcp.PublicationMcpTools.DestinataireView;
import dev.sylvain.planning.service.PlanPublicationService.DestinatairePublication;
import dev.sylvain.planning.service.PublicationTraceRepository.Destinataire;
import dev.sylvain.planning.service.PublicationTraceRepository.StatutEnvoi;

/**
 * The publication views are where the privacy rule is easiest to lose: the
 * REST ones are built for a screen that greets people by name and shows the
 * address a mail was sent to.
 */
class PublicationMcpToolsTest {

    private static final Instant ENVOYE_LE = Instant.parse("2026-06-01T08:00:00Z");

    @Test
    void unDestinataireSeReduitAUnIdEtAUnBooleenDAdresse() {
        DestinatairePublicationView vue = PublicationMcpTools.toView(new DestinatairePublication(
                "a1", "Camille Martin", "camille@example.org", true,
                List.of("samedi 10:00-12:00 — Stand A"), List.of()));

        assertThat(vue.animateurId()).isEqualTo("a1");
        assertThat(vue.adresseConnue()).isTrue();
        assertThat(vue.premiereDiffusion()).isTrue();
        assertThat(vue.changements()).containsExactly("samedi 10:00-12:00 — Stand A");
    }

    /**
     * The boolean is what an assistant acts on — « qui ne recevra rien ? » —
     * and it must answer that without the address being read anywhere.
     */
    @Test
    void uneFicheSansAdresseSeVoitSansQueLAdresseSorte() {
        assertThat(PublicationMcpTools.toView(new DestinatairePublication(
                "a2", "Dominique Roy", null, false, List.of(), List.of())).adresseConnue()).isFalse();
        assertThat(PublicationMcpTools.toView(new DestinatairePublication(
                "a3", "Dominique Roy", "   ", false, List.of(), List.of())).adresseConnue()).isFalse();
    }

    @Test
    void laTraceDitQuiAEteToucheParIdEtAvecQuelStatut() {
        List<DestinataireView> vues = PublicationMcpTools.toViews(List.of(
                new Destinataire(7L, "a1", "Camille Martin", "camille@example.org",
                        StatutEnvoi.ENVOYE, ENVOYE_LE, List.of("samedi 10:00-12:00 — Stand A")),
                new Destinataire(7L, "a2", "Dominique Roy", null,
                        StatutEnvoi.SANS_EMAIL, ENVOYE_LE, List.of())));

        assertThat(vues).extracting(DestinataireView::animateurId).containsExactly("a1", "a2");
        assertThat(vues).extracting(DestinataireView::statut).containsExactly("ENVOYE", "SANS_EMAIL");
        assertThat(vues.get(0).snapshotId()).isEqualTo(7L);
        assertThat(vues.get(0).envoyeLe()).isEqualTo(ENVOYE_LE);
    }

    /**
     * Structural rather than textual: no component of the exposed views may
     * hold what the REST ones carry alongside the id.
     */
    @Test
    void aucuneVueDePublicationNePorteDeNomNiDAdresse() {
        assertThat(DestinatairePublicationView.class.getRecordComponents())
                .extracting(composant -> composant.getName().toLowerCase())
                .doesNotContain("nom", "nomaffiche", "email");
        assertThat(DestinataireView.class.getRecordComponents())
                .extracting(composant -> composant.getName().toLowerCase())
                .doesNotContain("nom", "nomaffiche", "email");
    }
}
