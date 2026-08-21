package dev.sylvain.planning.service.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.DemandeEchange;
import dev.sylvain.planning.domain.StatutDemandeEchange;
import dev.sylvain.planning.service.AdresseAdministrateur;
import dev.sylvain.planning.service.LiensApplication;

/**
 * La rédaction des notifications (issue #165), sans {@code Mailer}, sans SMTP
 * et sans contexte Quarkus : la rédaction étant une fonction pure de la
 * notification et de la configuration, chaque assertion de formulation se lit
 * directement sur le {@link Courrier} produit.
 *
 * <p>Un {@link Optional} vide veut dire « personne à prévenir » — pas d'adresse
 * admin configurée, ou un animateur sans adresse sur sa fiche. C'est un
 * résultat normal, pas un échec : l'intéressé voit tout dans son espace.</p>
 */
class RedacteurNotificationsTest {

    private RedacteurNotifications redacteur;

    @BeforeEach
    void construireRedacteur() {
        redacteur = new RedacteurNotifications();
        redacteur.adresseAdmin = adresseAdmin("admin@example.org");
        redacteur.liens = liensVers("https://planning.example.org");
    }

    private static AdresseAdministrateur adresseAdmin(String adresse) {
        return new AdresseAdministrateur(Optional.ofNullable(adresse));
    }

    private static LiensApplication liensVers(String baseUrl) {
        return new LiensApplication(Optional.ofNullable(baseUrl));
    }

    private static DemandeEchange demande(Boolean prevalidationOk) {
        DemandeEchange demande = new DemandeEchange();
        demande.setId("D1");
        demande.setPrevalidationOk(prevalidationOk);
        return demande;
    }

    private Courrier rediger(Notification notification) {
        return redacteur.rediger(notification).orElseThrow();
    }

    // --- Demandes soumises (admin) -----------------------------------------

    @Test
    void sansAdresseAdminAucuneNotificationDeSoumission() {
        for (String adresse : new String[] { null, "  " }) {
            redacteur.adresseAdmin = adresseAdmin(adresse);

            assertThat(redacteur.rediger(new Notification.DemandesSoumises(
                    "Alice Dupont", List.of(demande(true))))).isEmpty();
        }
    }

    @Test
    void unLotVideNEcritRien() {
        assertThat(redacteur.rediger(new Notification.DemandesSoumises("Alice Dupont", List.of()))).isEmpty();
    }

    /** Un lot = un seul courrier, avec le compte des demandes et le lien admin. */
    @Test
    void unLotDeDemandesDonneUnSeulCourrierAvecLeLienAdmin() {
        Courrier courrier = rediger(new Notification.DemandesSoumises("Alice Dupont",
                List.of(demande(true), demande(true), demande(false))));

        assertThat(courrier.destinataire()).isEqualTo("admin@example.org");
        assertThat(courrier.sujet()).contains("3 nouvelles demandes").contains("Alice Dupont");
        assertThat(courrier.corps())
                .contains("3 demandes d'échange")
                .contains("Attention : 1 demande casse")
                .contains("https://planning.example.org/echanges");
    }

    @Test
    void uneDemandeUniqueEstAnnonceeAuSingulier() {
        Courrier courrier = rediger(new Notification.DemandesSoumises("Alice Dupont", List.of(demande(true))));

        assertThat(courrier.sujet()).contains("nouvelle demande d'échange de Alice Dupont");
        assertThat(courrier.corps()).doesNotContain("Attention");
    }

    /** Sans URL publique configurée, le courrier part quand même — sans lien. */
    @Test
    void sansUrlPubliqueLeCourrierPartSansLien() {
        redacteur.liens = liensVers(null);

        Courrier courrier = rediger(new Notification.DemandesSoumises("Alice Dupont", List.of(demande(true))));

        assertThat(courrier.corps()).doesNotContain("http");
    }

    /** Une prévalidation non renseignée n'est pas un échec de prévalidation. */
    @Test
    void unePrevalidationInconnueNAlertePas() {
        Courrier courrier = rediger(new Notification.DemandesSoumises(
                "Alice Dupont", List.of(demande(null), demande(true))));

        assertThat(courrier.corps()).doesNotContain("Attention");
    }

    // --- Décision (demandeur) ----------------------------------------------

    @Test
    void uneDemandeAccepteeLeDitEtAnnonceLaMiseAJour() {
        DemandeEchange demande = demande(true);
        demande.setStatut(StatutDemandeEchange.ACCEPTEE);

        Courrier courrier = rediger(new Notification.DemandeTranchee(
                "alice@example.org", demande, "samedi 10h-12h"));

        assertThat(courrier.destinataire()).isEqualTo("alice@example.org");
        assertThat(courrier.sujet()).contains("acceptée");
        assertThat(courrier.corps())
                .contains("(samedi 10h-12h)")
                .contains("le planning a été mis à jour");
    }

    @Test
    void unRefusCommenteReprendLeCommentaireDeLOrganisation() {
        DemandeEchange demande = demande(true);
        demande.setStatut(StatutDemandeEchange.REFUSEE);
        demande.setCommentaireAdmin("Le stand a besoin de toi ce jour-là");

        Courrier courrier = rediger(new Notification.DemandeTranchee("alice@example.org", demande, null));

        assertThat(courrier.sujet()).contains("refusée");
        assertThat(courrier.corps())
                .contains("le planning reste inchangé")
                .contains("Commentaire de l'organisation : Le stand a besoin de toi ce jour-là");
    }

    @Test
    void sansAdresseSurLaFicheAucunCourrierNEstEcrit() {
        DemandeEchange demande = demande(true);
        demande.setStatut(StatutDemandeEchange.ACCEPTEE);

        for (String adresse : new String[] { null, "  " }) {
            assertThat(redacteur.rediger(new Notification.DemandeTranchee(adresse, demande, null))).isEmpty();
        }
    }

    // --- Sollicitation et déclin (collègue ciblé) --------------------------

    @Test
    void leCollegueCibleEstRenvoyeVersSonEspace() {
        Courrier courrier = rediger(new Notification.CibleSollicitee(
                "bob@example.org", "Alice Dupont", 2));

        assertThat(courrier.destinataire()).isEqualTo("bob@example.org");
        assertThat(courrier.sujet()).contains("Alice Dupont").contains("des échanges de créneaux");
        assertThat(courrier.corps())
                .contains("2 échanges de créneaux")
                .contains("votre accord est nécessaire");
    }

    @Test
    void unDeclinDitAuDemandeurQuIlPeutProposerAilleurs() {
        Courrier courrier = rediger(new Notification.DemandeDeclinee(
                "alice@example.org", "Bob Martin", "samedi 10h-12h"));

        assertThat(courrier.corps())
                .contains("Bob Martin a décliné")
                .contains("(créneau samedi 10h-12h)")
                .contains("proposer l'échange à quelqu'un d'autre");
    }

    // --- Fin de résolution (admin) -----------------------------------------

    @Test
    void laFinDeResolutionAnnonceLEditionLeScoreEtLaFaisabilite() {
        Courrier courrier = rediger(new Notification.ResolutionTerminee(
                "Année 2026", "0hard/-3medium/-120soft", true));

        assertThat(courrier.destinataire()).isEqualTo("admin@example.org");
        // L'état tient dans l'objet : c'est ce qu'on lit sur un téléphone sans
        // ouvrir le message, après avoir lancé un solve et être parti.
        assertThat(courrier.sujet()).contains("Année 2026").contains("planning faisable");
        assertThat(courrier.corps())
                .contains("Édition : Année 2026")
                .contains("Score : 0hard/-3medium/-120soft")
                .contains("aucune contrainte dure violée")
                .contains("https://planning.example.org/problemes");
    }

    @Test
    void unPlanningInfaisableLeDitDesLObjetDuMessage() {
        Courrier courrier = rediger(new Notification.ResolutionTerminee(
                "Canicule", "-4hard/0medium/0soft", false));

        assertThat(courrier.sujet()).contains("NON faisable");
        assertThat(courrier.corps()).contains("n'est pas utilisable en l'état");
    }

    @Test
    void sansAdresseAdminLaFinDeResolutionNEcritRien() {
        for (String adresse : new String[] { null, "   " }) {
            redacteur.adresseAdmin = adresseAdmin(adresse);

            assertThat(redacteur.rediger(new Notification.ResolutionTerminee(
                    "Année 2026", "0hard/0medium/0soft", true))).isEmpty();
        }
    }

    @Test
    void unScoreNonMesureNEmpechePasLaNotification() {
        // Un solve annulé très tôt peut n'avoir aucun score à annoncer : on
        // prévient quand même, en le disant.
        Courrier courrier = rediger(new Notification.ResolutionTerminee("Année 2026", null, false));

        assertThat(courrier.corps()).contains("Score : non mesuré");
    }
}
