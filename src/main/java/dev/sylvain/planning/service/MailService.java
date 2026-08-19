package dev.sylvain.planning.service;

import java.util.List;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import dev.sylvain.planning.domain.DemandeEchange;
import dev.sylvain.planning.domain.StatutDemandeEchange;
import io.quarkus.logging.Log;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Échange notifications (issue #165): tells the admin when demandes are
 * submitted, and the animateur when one of theirs is decided. Plain-text
 * French mails, written for the animateur — business words, no technical
 * vocabulary.
 *
 * <p>Every send is best-effort: a mail failure is logged and never fails the
 * business operation it decorates (a submission or a decision must not be
 * rolled back because SMTP is down). With {@code planning.mail.admin} blank, admin
 * notifications are silently disabled; an animateur without an email address
 * simply receives nothing.</p>
 */
@ApplicationScoped
public class MailService {

    @Inject
    Mailer mailer;

    /** Recipient of the "new demandes" notifications; blank disables them. */
    @ConfigProperty(name = "planning.mail.admin")
    Optional<String> adminEmail;

    /** Public base URL of the app, used to link the admin screen in mails. */
    @ConfigProperty(name = "planning.public-url")
    Optional<String> publicUrl;

    /** One mail to the admin per submission batch, not one per demande. */
    public void notifierNouvellesDemandes(String demandeurNomComplet, List<DemandeEchange> demandes) {
        if (adminEmail.isEmpty() || adminEmail.get().isBlank() || demandes.isEmpty()) {
            return;
        }
        String sujet = demandes.size() == 1
                ? "Planning Équipes — nouvelle demande d'échange de " + demandeurNomComplet
                : "Planning Équipes — " + demandes.size() + " nouvelles demandes d'échange de " + demandeurNomComplet;
        StringBuilder corps = new StringBuilder()
                .append(demandeurNomComplet)
                .append(" a soumis ")
                .append(demandes.size() == 1 ? "une demande d'échange de créneau" : demandes.size() + " demandes d'échange de créneaux")
                .append(".\n\n");
        long infaisables = demandes.stream()
                .filter(demande -> Boolean.FALSE.equals(demande.getPrevalidationOk()))
                .count();
        if (infaisables > 0) {
            corps.append("Attention : ").append(infaisables)
                    .append(infaisables == 1 ? " demande casse" : " demandes cassent")
                    .append(" une contrainte dure en l'état du planning.\n\n");
        }
        publicUrl.filter(url -> !url.isBlank()).ifPresent(url -> corps
                .append("À valider ou refuser depuis l'écran Échanges : ")
                .append(url).append("/echanges\n"));
        envoyer(adminEmail.get(), sujet, corps.toString());
    }

    /**
     * Tells the demandeur the outcome of one of their demandes. No-op without
     * an email address on their fiche.
     */
    public void notifierDecision(String emailAnimateur, DemandeEchange demande, String libelleCreneau) {
        if (emailAnimateur == null || emailAnimateur.isBlank()) {
            return;
        }
        boolean acceptee = demande.getStatut() == StatutDemandeEchange.ACCEPTEE;
        String sujet = acceptee
                ? "Planning Équipes — votre demande d'échange est acceptée"
                : "Planning Équipes — votre demande d'échange est refusée";
        StringBuilder corps = new StringBuilder()
                .append("Votre demande d'échange")
                .append(libelleCreneau == null || libelleCreneau.isBlank() ? "" : " (" + libelleCreneau + ")")
                .append(acceptee
                        ? " a été acceptée : le planning a été mis à jour.\n"
                        : " a été refusée : le planning reste inchangé.\n");
        if (demande.getCommentaireAdmin() != null && !demande.getCommentaireAdmin().isBlank()) {
            corps.append("\nCommentaire de l'organisation : ")
                    .append(demande.getCommentaireAdmin()).append('\n');
        }
        envoyer(emailAnimateur, sujet, corps.toString());
    }

    private void envoyer(String destinataire, String sujet, String corps) {
        try {
            mailer.send(Mail.withText(destinataire, sujet, corps));
        } catch (RuntimeException e) {
            Log.errorf(e, "Failed to send mail \"%s\" to %s", sujet, destinataire);
        }
    }
}
