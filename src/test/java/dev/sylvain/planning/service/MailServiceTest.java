package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.DemandeEchange;
import dev.sylvain.planning.domain.StatutDemandeEchange;
import io.quarkus.mailer.Mail;

/**
 * Notifications d'échange (issue #165), sur un {@code MailService} construit à
 * la main avec un {@code Mailer} de capture : ni SMTP, ni contexte Quarkus.
 * Les invariants testés sont ceux promis par la javadoc du service — envois
 * best-effort, désactivation silencieuse sans adresse, un seul mail par lot.
 */
class MailServiceTest {

    private final List<Mail> envoyes = new ArrayList<>();
    private MailService service;

    @BeforeEach
    void construireService() {
        service = new MailService();
        service.mailer = mails -> envoyes.addAll(List.of(mails));
        service.adminEmail = Optional.of("admin@example.org");
        service.publicUrl = Optional.of("https://planning.example.org");
    }

    private static DemandeEchange demande(Boolean prevalidationOk) {
        DemandeEchange demande = new DemandeEchange();
        demande.setId("D1");
        demande.setPrevalidationOk(prevalidationOk);
        return demande;
    }

    @Test
    void sansAdresseAdminAucuneNotificationDeSoumission() {
        service.adminEmail = Optional.empty();
        service.notifierNouvellesDemandes("Alice Dupont", List.of(demande(true)));

        service.adminEmail = Optional.of("  ");
        service.notifierNouvellesDemandes("Alice Dupont", List.of(demande(true)));

        assertThat(envoyes).isEmpty();
    }

    @Test
    void unLotVideNEnvoieRien() {
        service.notifierNouvellesDemandes("Alice Dupont", List.of());
        assertThat(envoyes).isEmpty();
    }

    /** Un lot = un seul mail, avec le compte des demandes et le lien admin. */
    @Test
    void unLotDeDemandesDonneUnSeulMailAvecLeLienAdmin() {
        service.notifierNouvellesDemandes("Alice Dupont",
                List.of(demande(true), demande(true), demande(false)));

        assertThat(envoyes).hasSize(1);
        Mail mail = envoyes.get(0);
        assertThat(mail.getTo()).containsExactly("admin@example.org");
        assertThat(mail.getSubject()).contains("3 nouvelles demandes").contains("Alice Dupont");
        assertThat(mail.getText())
                .contains("3 demandes d'échange")
                .contains("Attention : 1 demande casse")
                .contains("https://planning.example.org/echanges");
    }

    @Test
    void uneDemandeUniqueEstAnnonceeAuSingulier() {
        service.notifierNouvellesDemandes("Alice Dupont", List.of(demande(true)));

        assertThat(envoyes).hasSize(1);
        assertThat(envoyes.get(0).getSubject()).contains("nouvelle demande d'échange de Alice Dupont");
        assertThat(envoyes.get(0).getText()).doesNotContain("Attention");
    }

    /** Sans URL publique configurée, le mail part quand même — sans lien. */
    @Test
    void sansUrlPubliqueLeMailPartSansLien() {
        service.publicUrl = Optional.empty();
        service.notifierNouvellesDemandes("Alice Dupont", List.of(demande(true)));

        assertThat(envoyes).hasSize(1);
        assertThat(envoyes.get(0).getText()).doesNotContain("/echanges");
    }

    /**
     * La prévalidation non encore calculée ({@code null}) n'est pas comptée
     * comme infaisable : seul un {@code false} explicite déclenche l'alerte.
     */
    @Test
    void unePrevalidationInconnueNEstPasCompteeInfaisable() {
        service.notifierNouvellesDemandes("Alice Dupont", List.of(demande(null), demande(true)));

        assertThat(envoyes).hasSize(1);
        assertThat(envoyes.get(0).getText()).doesNotContain("Attention");
    }

    @Test
    void uneDecisionAccepteeEstNotifieeAvecLeCreneau() {
        DemandeEchange demande = demande(true);
        demande.setStatut(StatutDemandeEchange.ACCEPTEE);

        service.notifierDecision("alice@example.org", demande, "samedi 10h-12h");

        assertThat(envoyes).hasSize(1);
        Mail mail = envoyes.get(0);
        assertThat(mail.getTo()).containsExactly("alice@example.org");
        assertThat(mail.getSubject()).contains("acceptée");
        assertThat(mail.getText())
                .contains("(samedi 10h-12h)")
                .contains("le planning a été mis à jour");
    }

    @Test
    void unRefusTransmetLeCommentaireDeLAdmin() {
        DemandeEchange demande = demande(true);
        demande.setStatut(StatutDemandeEchange.REFUSEE);
        demande.setCommentaireAdmin("Le repos de la cible serait cassé");

        service.notifierDecision("alice@example.org", demande, null);

        assertThat(envoyes).hasSize(1);
        assertThat(envoyes.get(0).getSubject()).contains("refusée");
        assertThat(envoyes.get(0).getText())
                .contains("le planning reste inchangé")
                .contains("Commentaire de l'organisation : Le repos de la cible serait cassé");
    }

    @Test
    void unAnimateurSansEmailNeRecoitRien() {
        DemandeEchange demande = demande(true);
        demande.setStatut(StatutDemandeEchange.ACCEPTEE);

        service.notifierDecision(null, demande, null);
        service.notifierDecision("  ", demande, null);

        assertThat(envoyes).isEmpty();
    }

    /** Un SMTP en panne ne doit jamais faire échouer l'opération métier. */
    @Test
    void unEchecDEnvoiEstAvaleSansCasserLOperation() {
        service.mailer = mails -> {
            throw new IllegalStateException("SMTP down");
        };

        DemandeEchange demande = demande(true);
        demande.setStatut(StatutDemandeEchange.ACCEPTEE);

        assertThatCode(() -> {
            service.notifierNouvellesDemandes("Alice Dupont", List.of(demande(true)));
            service.notifierDecision("alice@example.org", demande, null);
        }).doesNotThrowAnyException();
    }
}
