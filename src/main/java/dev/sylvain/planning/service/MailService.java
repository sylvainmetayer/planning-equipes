package dev.sylvain.planning.service;

import java.time.ZonedDateTime;
import java.util.List;

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
     * Every subject opens with the product name of this deployment: the first
     * thing an animateur reads in their inbox must be the service they signed
     * up with, not the software vendor's.
     */
    @Inject
    ProductName productName;

    /**
     * Sends one animateur their individual planning: the PDF attached, the
     * espace link in the body.
     */
    public void sendIndividualPlanning(String emailAnimateur, String prenom, String lienEspace,
            byte[] pdf, String fileName) {
        StringBuilder corps = new StringBuilder()
                .append("Bonjour").append(prenom == null || prenom.isBlank() ? "" : " " + prenom).append(",\n\n")
                .append("Vous trouverez en pièce jointe votre planning individuel pour l'événement.\n");
        if (lienEspace != null && !lienEspace.isBlank()) {
            corps.append("\nVotre espace en ligne (planning à jour, demandes d'échange) : ")
                    .append(lienEspace).append('\n');
        }
        corps.append("\nÀ bientôt,\nL'équipe d'organisation\n");
        mailer.send(Mail.withText(emailAnimateur, productName.subject("votre planning individuel"), corps.toString())
                .addAttachment(fileName, pdf, "application/pdf"));
    }

    /**
     * Sends one animateur the planning that has just been published, and what
     * changed for <b>them</b> since the last one (issue #245).
     *
     * <p>The lines are computed and reviewed upstream: the admin saw exactly
     * these sentences on screen before clicking Publier. Nothing is worded
     * here beyond the frame around them — a mail that says what moved is worth
     * more than a PDF redelivered without a word.</p>
     *
     * @param premiereDiffusion nothing was ever published to this person, so
     *                          their whole planning is the news; the change
     *                          list is then their planning, not a list of
     *                          corrections, and is left out of the body
     * @param changements       one sentence per moved vacation, in reading order
     * @param demandes          where their échange requests stand, if any
     */
    public void sendPlanningPublie(String emailAnimateur, String prenom, String lienEspace,
            byte[] pdf, String fileName, boolean premiereDiffusion,
            List<String> changements, List<String> demandes) {
        StringBuilder corps = new StringBuilder()
                .append("Bonjour").append(prenom == null || prenom.isBlank() ? "" : " " + prenom).append(",\n\n");
        if (premiereDiffusion) {
            corps.append("Vous trouverez en pièce jointe votre planning individuel pour l'événement.\n");
        } else {
            corps.append("Votre planning a changé depuis le dernier envoi. Voici ce qui vous concerne :\n\n");
            for (String changement : changements) {
                corps.append("- ").append(changement).append('\n');
            }
            corps.append("\nLe planning à jour est en pièce jointe.\n");
        }
        if (demandes != null && !demandes.isEmpty()) {
            corps.append("\nVos demandes d'échange :\n\n");
            for (String demande : demandes) {
                corps.append("- ").append(demande).append('\n');
            }
        }
        if (lienEspace != null && !lienEspace.isBlank()) {
            corps.append("\nVotre espace en ligne (planning à jour, demandes d'échange) : ")
                    .append(lienEspace).append('\n');
        }
        corps.append("\nÀ bientôt,\nL'équipe d'organisation\n");
        mailer.send(Mail.withText(emailAnimateur, productName.subject(premiereDiffusion
                ? "votre planning individuel"
                : "votre planning a changé"), corps.toString())
                .addAttachment(fileName, pdf, "application/pdf"));
    }

    /** Sends the espace access code — the second factor of the espace animateur. */
    public void sendAccessCode(String emailAnimateur, String prenom, String code) {
        String corps = "Bonjour" + (prenom == null || prenom.isBlank() ? "" : " " + prenom) + ",\n\n"
                + "Voici votre code d'accès à votre espace animateur : " + code + "\n\n"
                + "Il est valable 10 minutes. Si vous n'êtes pas à l'origine de cette demande, "
                + "ignorez simplement ce message.\n";
        mailer.send(Mail.withText(emailAnimateur, productName.subject("votre code d'accès"), corps));
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
                productName.subject("mail de test"),
                "Ce message confirme que l'envoi d'e-mails fonctionne pour cette instance.\n"
                        + "Envoyé depuis la page Débogage le " + ZonedDateTime.now() + ".\n"));
        return destinataire;
    }
}
