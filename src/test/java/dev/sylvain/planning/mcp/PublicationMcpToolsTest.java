package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.mcp.PublicationMcpTools.DestinatairePublicationView;
import dev.sylvain.planning.mcp.PublicationMcpTools.DestinataireView;
import dev.sylvain.planning.mcp.PublicationMcpTools.RapportRelanceView;
import dev.sylvain.planning.mcp.PublicationMcpTools.SyntheseConfirmationsView;
import dev.sylvain.planning.service.publication.PlanPublicationService.DestinatairePublication;
import dev.sylvain.planning.service.publication.PublicationTraceRepository.Destinataire;
import dev.sylvain.planning.service.publication.PublicationTraceRepository.StatutEnvoi;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The publication views are where the privacy rule is easiest to lose: the
 * REST ones are built for a screen that greets people by name and shows the
 * address a mail was sent to.
 */
class PublicationMcpToolsTest {

    private static final Instant ENVOYE_LE = Instant.parse("2026-06-01T08:00:00Z");

    /** One recipient, with everything the REST view carries and MCP must drop. */
    private static DestinatairePublication destinataire(
            String id, String nom, String email, boolean premiereDiffusion, List<String> changements) {
        return new DestinatairePublication(
                id, nom, email, premiereDiffusion, changements, List.of(), 1, 0, 0, false, false, null, null);
    }

    @Test
    void aRecipientComesDownToAnIdAndAnAddressBoolean() {
        DestinatairePublicationView vue = PublicationMcpTools.toView(destinataire(
                "a1", "Camille Martin", "camille@example.org", true, List.of("samedi 10:00-12:00 — Stand A")));

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
    void aFicheWithoutAnAddressShowsUpWithoutTheAddressLeaving() {
        assertThat(PublicationMcpTools.toView(destinataire("a2", "Dominique Roy", null, false, List.of()))
                        .adresseConnue())
                .isFalse();
        assertThat(PublicationMcpTools.toView(destinataire("a3", "Dominique Roy", "   ", false, List.of()))
                        .adresseConnue())
                .isFalse();
    }

    /**
     * The two flags the review table sorts and filters on travel to an
     * assistant too: « qui n'a toujours pas été prévenu ? » is a question it
     * must be able to answer without a name (issue #503).
     */
    @Test
    void theDeferredAndMinorFlagsTravelToTheAssistant() {
        DestinatairePublicationView vue = PublicationMcpTools.toView(new DestinatairePublication(
                "a4",
                "Dominique Roy",
                "d@example.org",
                false,
                List.of("lundi"),
                List.of(),
                0,
                0,
                1,
                true,
                true,
                null,
                null));

        assertThat(vue.mineur()).isTrue();
        assertThat(vue.reporte()).isTrue();
    }

    @Test
    void laTraceDitQuiAEteToucheParIdEtAvecQuelStatut() {
        List<DestinataireView> vues = PublicationMcpTools.toViews(List.of(
                new Destinataire(
                        7L,
                        "a1",
                        "Camille Martin",
                        "camille@example.org",
                        StatutEnvoi.ENVOYE,
                        ENVOYE_LE,
                        List.of("samedi 10:00-12:00 — Stand A"),
                        List.of("Votre demande d'échange a été acceptée."),
                        false),
                new Destinataire(
                        7L,
                        "a2",
                        "Dominique Roy",
                        null,
                        StatutEnvoi.SANS_EMAIL,
                        ENVOYE_LE,
                        List.of(),
                        List.of(),
                        false)));

        assertThat(vues).extracting(DestinataireView::animateurId).containsExactly("a1", "a2");
        assertThat(vues).extracting(DestinataireView::statut).containsExactly("ENVOYE", "SANS_EMAIL");
        assertThat(vues.get(0).snapshotId()).isEqualTo(7L);
        assertThat(vues.get(0).envoyeLe()).isEqualTo(ENVOYE_LE);
        // « De quoi il a été informé » is the whole message: the trace keeps
        // the two halves apart because they age differently, this view answers
        // what was sent.
        assertThat(vues.get(0).changements())
                .containsExactly("samedi 10:00-12:00 — Stand A", "Votre demande d'échange a été acceptée.");
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
        assertThat(RapportRelanceView.class.getRecordComponents())
                .extracting(composant -> composant.getName().toLowerCase())
                .doesNotContain("nom", "nomaffiche", "email");
        assertThat(SyntheseConfirmationsView.class.getRecordComponents())
                .extracting(composant -> composant.getName().toLowerCase())
                .doesNotContain("nom", "nomaffiche", "email");
    }
}
