package dev.sylvain.planning.service.notification;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.DemandeEchange;
import dev.sylvain.planning.service.ProductName;
import dev.sylvain.planning.service.espace.ApplicationLinks;
import dev.sylvain.planning.service.mail.MailKind;
import dev.sylvain.planning.service.mail.MailTemplates;
import dev.sylvain.planning.service.publication.AdminAddress;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    void buildWriter() {
        redacteur = writer(adminAddress("admin@example.org"), linksTo("https://planning.example.org"));
    }

    private static NotificationWriter writer(AdminAddress adminAddress, ApplicationLinks liens) {
        return new NotificationWriter(
                adminAddress, liens, ProductName.neutral(), MailTemplates.standalone(ProductName.neutral()));
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
    void withoutAnAdminAddressNoSubmissionIsNotified() {
        for (String address : new String[] {null, "  "}) {
            redacteur = writer(adminAddress(address), linksTo("https://planning.example.org"));

            assertThat(redacteur.rediger(new Notification.DemandesSoumises("Alice Dupont", List.of(demande(true)))))
                    .isEmpty();
        }
    }

    @Test
    void unLotVideNEcritRien() {
        assertThat(redacteur.rediger(new Notification.DemandesSoumises("Alice Dupont", List.of())))
                .isEmpty();
    }

    /** One batch = one single mail, with the count of requests and the admin link. */
    @Test
    void unLotDeDemandesDonneUnSeulCourrierAvecLeLienAdmin() {
        MailDraft courrier = rediger(new Notification.DemandesSoumises(
                "Alice Dupont", List.of(demande(true), demande(true), demande(false))));

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
    void withoutAPublicUrlTheMailLeavesWithoutTheLink() {
        redacteur = writer(adminAddress("admin@example.org"), linksTo(null));

        MailDraft courrier = rediger(new Notification.DemandesSoumises("Alice Dupont", List.of(demande(true))));

        assertThat(courrier.corps()).doesNotContain("http");
    }

    /** An unfilled prevalidation is not a failed prevalidation. */
    @Test
    void unePrevalidationInconnueNAlertePas() {
        MailDraft courrier =
                rediger(new Notification.DemandesSoumises("Alice Dupont", List.of(demande(null), demande(true))));

        assertThat(courrier.corps()).doesNotContain("Attention");
    }

    // --- Solicited and declined (targeted colleague) -----------------------

    @Test
    void theSolicitedColleagueIsSentToTheirEspace() {
        MailDraft courrier = rediger(new Notification.TargetSolicited("B1", "bob@example.org", "Alice Dupont", 2));

        assertThat(courrier.destinataire()).isEqualTo("bob@example.org");
        assertThat(courrier.sujet()).contains("Alice Dupont").contains("des échanges de créneaux");
        assertThat(courrier.corps()).contains("2 échanges de créneaux").contains("votre accord est nécessaire");
    }

    @Test
    void aDeclineTellsTheRequesterTheyMayProposeElsewhere() {
        MailDraft courrier =
                rediger(new Notification.DemandeDeclinee("A1", "alice@example.org", "Bob Martin", "samedi 10h-12h"));

        assertThat(courrier.corps())
                .contains("Bob Martin a décliné")
                .contains("(créneau samedi 10h-12h)")
                .contains("proposer l'échange à quelqu'un d'autre");
    }

    /** Both are written to one animateur, so their outcome lands in the delivery journal by id. */
    @Test
    void bothSwapMailsNameTheAnimateurTheyAreWrittenTo() {
        Notification.ToAnimateur sollicitation =
                new Notification.TargetSolicited("B1", "bob@example.org", "Alice Dupont", 1);
        Notification.ToAnimateur declin =
                new Notification.DemandeDeclinee("A1", "alice@example.org", "Bob Martin", "samedi 10h-12h");

        assertThat(sollicitation.animateurId()).isEqualTo("B1");
        assertThat(sollicitation.kind()).isEqualTo(MailKind.ECHANGE_SOLLICITATION);
        assertThat(declin.animateurId()).isEqualTo("A1");
        assertThat(declin.kind()).isEqualTo(MailKind.ECHANGE_DECLINEE);
    }

    // --- End of a solve (admin) --------------------------------------------

    @Test
    void laFinDeResolutionAnnonceLEditionLeScoreEtLaFaisabilite() {
        MailDraft courrier =
                rediger(new Notification.ResolutionTerminee("Année 2026", "0hard/-3medium/-120soft", true));

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
        MailDraft courrier = rediger(new Notification.ResolutionTerminee("Canicule", "-4hard/0medium/0soft", false));

        assertThat(courrier.sujet()).contains("NON faisable");
        assertThat(courrier.corps()).contains("n'est pas utilisable en l'état");
    }

    @Test
    void withoutAnAdminAddressTheEndOfSolveWritesNothing() {
        for (String address : new String[] {null, "   "}) {
            redacteur = writer(adminAddress(address), linksTo("https://planning.example.org"));

            assertThat(redacteur.rediger(
                            new Notification.ResolutionTerminee("Année 2026", "0hard/0medium/0soft", true)))
                    .isEmpty();
        }
    }

    @Test
    void unScoreNonMesureNEmpechePasLaNotification() {
        // A solve cancelled very early may have no score to announce: the mail
        // still goes out, and says so.
        MailDraft courrier = rediger(new Notification.ResolutionTerminee("Année 2026", null, false));

        assertThat(courrier.corps()).contains("Score : non mesuré");
    }

    @Test
    void uneDeclarationSoumiseCompteLesJoursEtLesSouhaitsSansLesNommer() {
        MailDraft courrier = rediger(new Notification.DeclarationSoumise("Alice Dupont", 3, 2));

        assertThat(courrier.sujet()).contains("déclaration de disponibilités de Alice Dupont");
        assertThat(courrier.corps()).contains("3 jours d'indisponibilité");
        assertThat(courrier.corps()).contains("2 souhaits");
        assertThat(courrier.corps()).contains("Rien n'est appliqué");
        assertThat(courrier.corps()).contains("https://planning.example.org/disponibilites");
        // The days themselves stay out of the mailbox: the screen shows them,
        // the mail only says that something is waiting.
        assertThat(courrier.corps()).doesNotMatch("(?s).*\\d{4}-\\d{2}-\\d{2}.*");
    }

    @Test
    void uneDeclarationSansJourNiSouhaitEstAnnonceeQuandMeme() {
        // "I am available every day and have no preference" is a statement, and
        // the admin has to be told it landed.
        MailDraft courrier = rediger(new Notification.DeclarationSoumise("Bruno Petit", 0, 0));

        assertThat(courrier.corps()).contains("aucun jour d'indisponibilité");
        assertThat(courrier.corps()).contains("aucun souhait");
    }

    @Test
    void withoutAnAdminAddressNoDeclarationIsNotified() {
        redacteur = writer(adminAddress(null), linksTo("https://planning.example.org"));

        assertThat(redacteur.rediger(new Notification.DeclarationSoumise("Alice Dupont", 1, 0)))
                .isEmpty();
    }

    // --- Automatic backup (admin) ------------------------------------------

    private static final ZonedDateTime NUIT = ZonedDateTime.of(2026, 3, 8, 4, 0, 0, 0, ZoneId.of("Europe/Paris"));

    @Test
    void aFailedBackupSaysWhenWhyAndSinceWhen() {
        MailDraft courrier =
                rediger(new Notification.BackupFailed(NUIT, "No space left on device", NUIT.minusDays(1), 1));

        assertThat(courrier.destinataire()).isEqualTo("admin@example.org");
        assertThat(courrier.sujet()).contains("échec de la sauvegarde nocturne");
        assertThat(courrier.corps())
                .contains("8 mars 2026 à 04:00")
                .contains("No space left on device")
                .contains("Dernière sauvegarde réussie : 7 mars 2026 à 04:00")
                .contains("https://planning.example.org/parametres?onglet=globaux");
        assertThat(courrier.html()).contains("No space left on device");
    }

    @Test
    void aBackupThatNeverWorkedSaysSoAndCountsTheNights() {
        MailDraft courrier = rediger(new Notification.BackupFailed(NUIT, "boom", null, 3));

        assertThat(courrier.sujet()).contains("3 nuits consécutives");
        assertThat(courrier.corps())
                .contains("Aucune sauvegarde réussie n'est enregistrée")
                .contains("3e tentative");
    }

    /** The database itself was down: no count, no date, and the mail says why. */
    @Test
    void anUnknownStreakNamesNoCountAndNoDate() {
        MailDraft courrier = rediger(new Notification.BackupFailed(NUIT, "connection refused", null, 0));

        assertThat(courrier.sujet()).doesNotContain("consécutives");
        assertThat(courrier.corps()).contains("date de la dernière sauvegarde réussie est inconnue");
    }

    @Test
    void aRecoveredBackupClosesTheLoop() {
        MailDraft courrier = rediger(new Notification.BackupRecovered(NUIT, "planning-20260308-040000.dump", 2));

        assertThat(courrier.sujet()).contains("rétablie");
        assertThat(courrier.corps()).contains("planning-20260308-040000.dump").contains("2 tentatives");
    }

    @Test
    void withoutAnAdminAddressNoBackupAlertIsWritten() {
        redacteur = writer(adminAddress(null), linksTo("https://planning.example.org"));

        assertThat(redacteur.rediger(new Notification.BackupFailed(NUIT, "boom", null, 1)))
                .isEmpty();
        assertThat(redacteur.rediger(new Notification.BackupRecovered(NUIT, "f.dump", 1)))
                .isEmpty();
    }
}
