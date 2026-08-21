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

    /**
     * Tells the targeted colleague that demandes await THEIR agreement — the
     * step that spares the admin from asking both sides. No-op without an
     * email address on the colleague's fiche (they still see the demandes in
     * their espace).
     */
    public void notifierCibleNouvellesDemandes(String emailCible, String demandeurNomComplet, int nombre) {
        if (emailCible == null || emailCible.isBlank()) {
            return;
        }
        String sujet = "Planning Équipes — " + demandeurNomComplet
                + (nombre == 1 ? " vous propose un échange de créneau" : " vous propose des échanges de créneaux");
        StringBuilder corps = new StringBuilder()
                .append(demandeurNomComplet).append(" vous propose ")
                .append(nombre == 1 ? "un échange de créneau" : nombre + " échanges de créneaux")
                .append(".\n\nAcceptez ou déclinez depuis votre espace personnel (lien imprimé sur votre ")
                .append("planning PDF), onglet Échanges : votre accord est nécessaire avant que ")
                .append("l'organisation ne tranche.\n");
        envoyer(emailCible, sujet, corps.toString());
    }

    /** Tells the demandeur their colleague declined; the admin never had to arbitrate. */
    public void notifierDeclinParCible(String emailDemandeur, String cibleNomComplet, String libelleCreneau) {
        if (emailDemandeur == null || emailDemandeur.isBlank()) {
            return;
        }
        StringBuilder corps = new StringBuilder()
                .append(cibleNomComplet).append(" a décliné votre demande d'échange");
        if (libelleCreneau != null) {
            corps.append(" (créneau ").append(libelleCreneau).append(")");
        }
        corps.append(".\nVous pouvez proposer l'échange à quelqu'un d'autre depuis votre espace.\n");
        envoyer(emailDemandeur, "Planning Équipes — votre demande d'échange a été déclinée", corps.toString());
    }

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
                .append(", déjà acceptée")
                .append(demandes.size() == 1 ? "" : "s")
                .append(" par le collègue concerné.\n\n");
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

    /**
     * Sends one animateur their individual planning: the PDF attached, the
     * espace link in the body. Unlike the notifications above, this is an
     * explicit admin action ("envoyer les plannings"), so a failure is NOT
     * swallowed here — the caller reports who could not be reached.
     */
    public void envoyerPlanningIndividuel(String emailAnimateur, String prenom, String lienEspace,
            byte[] pdf, String nomFichier) {
        StringBuilder corps = new StringBuilder()
                .append("Bonjour").append(prenom == null || prenom.isBlank() ? "" : " " + prenom).append(",\n\n")
                .append("Vous trouverez en pièce jointe votre planning individuel pour le festival.\n");
        if (lienEspace != null && !lienEspace.isBlank()) {
            corps.append("\nVotre espace en ligne (planning à jour, demandes d'échange) : ")
                    .append(lienEspace).append('\n');
        }
        corps.append("\nÀ bientôt,\nL'équipe d'organisation\n");
        mailer.send(Mail.withText(emailAnimateur, "Planning Équipes — votre planning individuel", corps.toString())
                .addAttachment(nomFichier, pdf, "application/pdf"));
    }

    /**
     * Sends the espace access code — the second factor of the espace
     * animateur. Like {@link #envoyerPlanningIndividuel}, a failure is NOT
     * swallowed: without the mail the animateur cannot get in, so the caller
     * must be able to say "send failed" instead of "check your inbox".
     */
    public void envoyerCodeAcces(String emailAnimateur, String prenom, String code) {
        String corps = "Bonjour" + (prenom == null || prenom.isBlank() ? "" : " " + prenom) + ",\n\n"
                + "Voici votre code d'accès à votre espace animateur : " + code + "\n\n"
                + "Il est valable 10 minutes. Si vous n'êtes pas à l'origine de cette demande, "
                + "ignorez simplement ce message.\n";
        mailer.send(Mail.withText(emailAnimateur, "Planning Équipes — votre code d'accès", corps));
    }

    /**
     * Tells the admin a solve just finished: which edition, what score, and
     * whether the plan is feasible — the three facts one waits for when a
     * multi-minute run was launched before walking away. Best-effort and
     * silent without an admin address, like every other notification here.
     *
     * @param faisable no hard constraint left broken; anything else means the
     *                 plan cannot be used as is, which is the whole point of
     *                 saying it in the subject line rather than in the body
     */
    public void notifierFinResolution(String editionNom, String score, boolean faisable) {
        if (adminEmailConfigure().isEmpty()) {
            return;
        }
        String etat = faisable ? "planning faisable" : "planning NON faisable";
        String sujet = "Planning Équipes — résolution terminée sur « " + editionNom + " » : " + etat;
        StringBuilder corps = new StringBuilder()
                .append("Édition : ").append(editionNom).append('\n')
                .append("Score : ").append(score == null ? "non mesuré" : score).append('\n')
                .append("Faisabilité : ").append(faisable
                        ? "aucune contrainte dure violée"
                        : "au moins une contrainte dure reste violée — le planning n'est pas utilisable en l'état")
                .append('\n');
        publicUrl.filter(url -> !url.isBlank()).ifPresent(url -> corps
                .append("\nDétail des contraintes en défaut : ").append(url).append("/problemes\n"));
        envoyer(adminEmailConfigure().get(), sujet, corps.toString());
    }

    /** Admin address, trimmed — empty when the "new demandes" notifications are disabled. */
    public Optional<String> adminEmailConfigure() {
        return adminEmail.map(String::trim).filter(adresse -> !adresse.isBlank());
    }

    /**
     * Sends a test mail to the admin address and PROPAGATES any failure —
     * unlike every business send, which is best-effort by design: the whole
     * point of the Débogage button is to surface a broken SMTP setup.
     */
    public String envoyerMailTest() {
        String destinataire = adminEmailConfigure()
                .orElseThrow(() -> new IllegalStateException(
                        "Aucune adresse e-mail administrateur configurée (MAIL_ADMIN)."));
        mailer.send(Mail.withText(destinataire,
                "Planning Équipes — mail de test",
                "Ce message confirme que l'envoi d'e-mails fonctionne pour cette instance.\n"
                        + "Envoyé depuis la page Débogage le " + java.time.ZonedDateTime.now() + ".\n"));
        return destinataire;
    }

    private void envoyer(String destinataire, String sujet, String corps) {
        try {
            mailer.send(Mail.withText(destinataire, sujet, corps));
        } catch (RuntimeException e) {
            Log.errorf(e, "Failed to send mail \"%s\" to %s", sujet, destinataire);
        }
    }
}
