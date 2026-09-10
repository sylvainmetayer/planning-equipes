package dev.sylvain.planning.service.publication;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;

import dev.sylvain.planning.service.mail.MailTemplates;
import dev.sylvain.planning.service.mail.MailTemplates.MailContent;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import dev.sylvain.planning.service.ProductName;

/**
 * The mails an administrator <b>asks for</b>, and only those: sending an
 * animateur their individual planning, sending an espace access code, inviting
 * the animateurs to declare their availability, sending the test mail of the
 * Débogage screen.
 *
 * <p>What these have in common — and what separates them from every
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
 *
 * <p>The wording lives in {@code resources/templates/mail/}, one text and one
 * HTML template per mail (see {@link MailTemplates}); this class names the
 * template, hands it its values, and attaches what goes with it.</p>
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

    @Inject
    MailTemplates templates;

    /**
     * Sends one animateur their individual planning: the PDF attached, the
     * espace link in the body.
     */
    public void sendIndividualPlanning(String emailAnimateur, String prenom, String lienEspace,
            byte[] pdf, String fileName) {
        MailContent content = templates.render("mail/planning-individuel",
                productName.subject("votre planning individuel"),
                MailTemplates.values("prenom", blankToNull(prenom), "lienEspace", blankToNull(lienEspace)));
        mailer.send(templates.toMail(emailAnimateur, content).addAttachment(fileName, pdf, "application/pdf"));
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
        MailContent content = templates.render("mail/planning-publie",
                productName.subject(premiereDiffusion ? "votre planning individuel" : "votre planning a changé"),
                MailTemplates.values(
                        "prenom", blankToNull(prenom),
                        "lienEspace", blankToNull(lienEspace),
                        "premiereDiffusion", premiereDiffusion,
                        "changements", changements == null ? List.of() : changements,
                        "demandes", demandes == null ? List.of() : demandes));
        mailer.send(templates.toMail(emailAnimateur, content).addAttachment(fileName, pdf, "application/pdf"));
    }

    /** Sends the espace access code — the second factor of the espace animateur. */
    public void sendAccessCode(String emailAnimateur, String prenom, String code) {
        MailContent content = templates.render("mail/code-acces", productName.subject("votre code d'accès"),
                MailTemplates.values("prenom", blankToNull(prenom), "code", code));
        mailer.send(templates.toMail(emailAnimateur, content));
    }

    /**
     * Invites one animateur to declare their availability from their espace
     * (issue #291), on a collection window the admin has just opened.
     *
     * <p>An explicit administration action, ticked on the opening dialog, so it
     * belongs here rather than in the notifications: it is indispensable on the
     * first round and merely tiresome on a reopening, and the caller reports
     * who could not be reached.</p>
     *
     * @param lienDeclaration the declaration tab of their espace, built by
     *                        {@link ApplicationLinks} — never concatenated
     * @param debut           first day of the window, {@code null} when the
     *                        admin set no bound
     * @param fin             last day of the window, {@code null} likewise
     */
    public void sendInvitationDeclaration(String emailAnimateur, String prenom, String lienDeclaration,
            LocalDate debut, LocalDate fin) {
        MailContent content = templates.render("mail/invitation-declaration",
                productName.subject("vos disponibilités sont attendues"),
                MailTemplates.values(
                        "prenom", blankToNull(prenom),
                        "lienDeclaration", lienDeclaration,
                        "fenetre", describeFenetre(debut, fin)));
        mailer.send(templates.toMail(emailAnimateur, content));
    }

    /** The window in one sentence, {@code null} when the admin bounded neither end. */
    private static String describeFenetre(LocalDate debut, LocalDate fin) {
        if (debut == null && fin == null) {
            return null;
        }
        if (debut == null) {
            return "Vous avez jusqu'au " + fin + " pour répondre.";
        }
        if (fin == null) {
            return "La collecte est ouverte depuis le " + debut + ".";
        }
        return "La collecte est ouverte du " + debut + " au " + fin + ".";
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
        MailContent content = templates.render("mail/test", productName.subject("mail de test"),
                MailTemplates.values("horodatage", ZonedDateTime.now().toString()));
        mailer.send(templates.toMail(destinataire, content));
        return destinataire;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
