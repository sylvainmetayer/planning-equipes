package dev.sylvain.planning.service;

import java.time.ZonedDateTime;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The mails an administrator <b>asks for</b>, and only those: sending an
 * animateur their individual planning, sending an espace access code, sending
 * the test mail of the Débogage screen.
 *
 * <p>What these three have in common — and what separates them from every
 * notification of
 * {@link dev.sylvain.planning.service.notification} — is that
 * <b>a failure must propagate</b>. The mail is not decorating an operation
 * here, it <i>is</i> the operation: without it the animateur has no code and
 * cannot get in, or the admin believes a planning was delivered that never
 * left. The caller reports who could not be reached, so nothing is swallowed.
 *
 * <p>Everything that is best-effort — échange notifications, end-of-solve —
 * fires a
 * {@link dev.sylvain.planning.service.notification.Notification}
 * instead, whose delivery policy lives in one place. Adding a
 * {@code notifierXxx} method here would recreate the fork this split removed:
 * two opposite failure policies behind identically-shaped methods, with no way
 * to tell from the signature which one you were calling.</p>
 */
@ApplicationScoped
public class MailService {

    @Inject
    Mailer mailer;

    @Inject
    AdminAddress adminAddress;

    /**
     * Sends one animateur their individual planning: the PDF attached, the
     * espace link in the body.
     */
    public void sendIndividualPlanning(String emailAnimateur, String prenom, String lienEspace,
            byte[] pdf, String fileName) {
        StringBuilder corps = new StringBuilder()
                .append("Bonjour").append(prenom == null || prenom.isBlank() ? "" : " " + prenom).append(",\n\n")
                .append("Vous trouverez en pièce jointe votre planning individuel pour le festival.\n");
        if (lienEspace != null && !lienEspace.isBlank()) {
            corps.append("\nVotre espace en ligne (planning à jour, demandes d'échange) : ")
                    .append(lienEspace).append('\n');
        }
        corps.append("\nÀ bientôt,\nL'équipe d'organisation\n");
        mailer.send(Mail.withText(emailAnimateur, "Planning Équipes — votre planning individuel", corps.toString())
                .addAttachment(fileName, pdf, "application/pdf"));
    }

    /** Sends the espace access code — the second factor of the espace animateur. */
    public void sendAccessCode(String emailAnimateur, String prenom, String code) {
        String corps = "Bonjour" + (prenom == null || prenom.isBlank() ? "" : " " + prenom) + ",\n\n"
                + "Voici votre code d'accès à votre espace animateur : " + code + "\n\n"
                + "Il est valable 10 minutes. Si vous n'êtes pas à l'origine de cette demande, "
                + "ignorez simplement ce message.\n";
        mailer.send(Mail.withText(emailAnimateur, "Planning Équipes — votre code d'accès", corps));
    }

    /**
     * Sends a test mail to the admin address. The whole point of the Débogage
     * button is to surface a broken SMTP setup, so this propagates like the
     * two above.
     */
    public String sendTestMail() {
        String destinataire = adminAddress.resolue()
                .orElseThrow(() -> new IllegalStateException(
                        "Aucune adresse e-mail administrateur configurée (MAIL_ADMIN)."));
        mailer.send(Mail.withText(destinataire,
                "Planning Équipes — mail de test",
                "Ce message confirme que l'envoi d'e-mails fonctionne pour cette instance.\n"
                        + "Envoyé depuis la page Débogage le " + ZonedDateTime.now() + ".\n"));
        return destinataire;
    }
}
