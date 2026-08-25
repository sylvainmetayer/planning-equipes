package dev.sylvain.planning.service.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.DemandeEchange;
import dev.sylvain.planning.service.AdminAddress;
import dev.sylvain.planning.service.ApplicationLinks;
import dev.sylvain.planning.service.ProductName;

/**
 * The writing of the notifications (issue #165), with no {@code Mailer}, no
 * SMTP and no Quarkus context: since writing is a pure function of the
 * notification and of the configuration, every assertion on the wording is read
 * straight off the {@link MailDraft} produced.
 *
 * <p>An empty {@link Optional} means "nobody to notify" — no admin address
 * configured, or an animateur with no address on their record. That is a normal
 * result, not a failure: the person concerned sees everything in their
 * espace.</p>
 */
class NotificationWriterTest {

    private NotificationWriter redacteur;

    @BeforeEach
    void construireRedacteur() {
        redacteur = new NotificationWriter();
        redacteur.adminAddress = adminAddress("admin@example.org");
        redacteur.liens = linksTo("https://planning.example.org");
        redacteur.productName = ProductName.neutral();
    }

    private static AdminAddress adminAddress(String address) {
        return new AdminAddress(Optional.ofNullable(address));
    }

    private static ApplicationLinks linksTo(String baseUrl) {
        return new ApplicationLinks(Optional.ofNullable(baseUrl));
    }

    private static DemandeEchange demande(Boolean prevalidationOk) {
        DemandeEchange demande = new DemandeEchange();
        demande.setId("D1");
        demande.setPrevalidationOk(prevalidationOk);
        return demande;
    }

    private MailDraft rediger(Notification notification) {
        return redacteur.rediger(notification).orElseThrow();
    }

    // --- Demandes soumises (admin) -----------------------------------------

    @Test
    void sansAdresseAdminAucuneNotificationDeSoumission() {
        for (String address : new String[] { null, "  " }) {
            redacteur.adminAddress = adminAddress(address);

            assertThat(redacteur.rediger(new Notification.DemandesSoumises(
                    "Alice Dupont", List.of(demande(true))))).isEmpty();
        }
    }

    @Test
    void unLotVideNEcritRien() {
        assertThat(redacteur.rediger(new Notification.DemandesSoumises("Alice Dupont", List.of()))).isEmpty();
    }

    /** One batch = one single mail, with the count of requests and the admin link. */
    @Test
    void unLotDeDemandesDonneUnSeulCourrierAvecLeLienAdmin() {
        MailDraft courrier = rediger(new Notification.DemandesSoumises("Alice Dupont",
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
        MailDraft courrier = rediger(new Notification.DemandesSoumises("Alice Dupont", List.of(demande(true))));

        assertThat(courrier.sujet()).contains("nouvelle demande d'échange de Alice Dupont");
        assertThat(courrier.corps()).doesNotContain("Attention");
    }

    /** With no public URL configured the mail still leaves — without the link. */
    @Test
    void sansUrlPubliqueLeCourrierPartSansLien() {
        redacteur.liens = linksTo(null);

        MailDraft courrier = rediger(new Notification.DemandesSoumises("Alice Dupont", List.of(demande(true))));

        assertThat(courrier.corps()).doesNotContain("http");
    }

    /** An unfilled prevalidation is not a failed prevalidation. */
    @Test
    void unePrevalidationInconnueNAlertePas() {
        MailDraft courrier = rediger(new Notification.DemandesSoumises(
                "Alice Dupont", List.of(demande(null), demande(true))));

        assertThat(courrier.corps()).doesNotContain("Attention");
    }

    // --- Solicited and declined (targeted colleague) -----------------------

    @Test
    void leCollegueCibleEstRenvoyeVersSonEspace() {
        MailDraft courrier = rediger(new Notification.TargetSolicited(
                "bob@example.org", "Alice Dupont", 2));

        assertThat(courrier.destinataire()).isEqualTo("bob@example.org");
        assertThat(courrier.sujet()).contains("Alice Dupont").contains("des échanges de créneaux");
        assertThat(courrier.corps())
                .contains("2 échanges de créneaux")
                .contains("votre accord est nécessaire");
    }

    @Test
    void unDeclinDitAuDemandeurQuIlPeutProposerAilleurs() {
        MailDraft courrier = rediger(new Notification.DemandeDeclinee(
                "alice@example.org", "Bob Martin", "samedi 10h-12h"));

        assertThat(courrier.corps())
                .contains("Bob Martin a décliné")
                .contains("(créneau samedi 10h-12h)")
                .contains("proposer l'échange à quelqu'un d'autre");
    }

    // --- End of a solve (admin) --------------------------------------------

    @Test
    void laFinDeResolutionAnnonceLEditionLeScoreEtLaFaisabilite() {
        MailDraft courrier = rediger(new Notification.ResolutionTerminee(
                "Année 2026", "0hard/-3medium/-120soft", true));

        assertThat(courrier.destinataire()).isEqualTo("admin@example.org");
        // The state fits in the subject: that is what is read on a phone without
        // opening the message, after starting a solve and leaving.
        assertThat(courrier.sujet()).contains("Année 2026").contains("planning faisable");
        assertThat(courrier.corps())
                .contains("Édition : Année 2026")
                .contains("Score : 0hard/-3medium/-120soft")
                .contains("aucune contrainte dure violée")
                .contains("https://planning.example.org/problemes");
    }

    @Test
    void unPlanningInfaisableLeDitDesLObjetDuMessage() {
        MailDraft courrier = rediger(new Notification.ResolutionTerminee(
                "Canicule", "-4hard/0medium/0soft", false));

        assertThat(courrier.sujet()).contains("NON faisable");
        assertThat(courrier.corps()).contains("n'est pas utilisable en l'état");
    }

    @Test
    void sansAdresseAdminLaFinDeResolutionNEcritRien() {
        for (String address : new String[] { null, "   " }) {
            redacteur.adminAddress = adminAddress(address);

            assertThat(redacteur.rediger(new Notification.ResolutionTerminee(
                    "Année 2026", "0hard/0medium/0soft", true))).isEmpty();
        }
    }

    @Test
    void unScoreNonMesureNEmpechePasLaNotification() {
        // A solve cancelled very early may have no score to announce: the mail
        // still goes out, and says so.
        MailDraft courrier = rediger(new Notification.ResolutionTerminee("Année 2026", null, false));

        assertThat(courrier.corps()).contains("Score : non mesuré");
    }
}
