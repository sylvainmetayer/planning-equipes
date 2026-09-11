package dev.sylvain.planning.service.publication;

import dev.sylvain.planning.service.ProductName;
import dev.sylvain.planning.service.mail.MailTemplates;
import dev.sylvain.planning.service.mail.MailTemplates.MailContent;

/**
 * The one wording of « confirmez-vous votre planning ? », whichever path
 * sends it.
 *
 * <p>The reminder leaves two ways: at night, as a best-effort
 * {@code Notification} of {@code RelanceConfirmationJob}, and by hand, as an
 * explicit administration action of {@link RelanceManuelleService} through
 * {@link MailService}. The two paths have opposite failure policies — that is
 * the whole reason they are two — but the person reading the mail must not be
 * able to tell them apart: the subject, the template and its values are
 * decided here and nowhere else, so a rewording of one cannot drift from the
 * other. {@code RelanceConfirmationMailTest} holds both renderings equal.</p>
 */
public final class RelanceConfirmationMail {

    /** Template pair under {@code resources/templates/mail/}. */
    static final String TEMPLATE = "mail/relance-confirmation";

    private RelanceConfirmationMail() {}

    /**
     * Renders both parts of the reminder.
     *
     * @param prenom     greets by first name; blank means « Bonjour, »
     * @param lienEspace the animateur's espace, {@code null} when no public URL
     *                   is configured or the fiche carries no token — the mail
     *                   then leaves without its link, never with a broken one
     */
    public static MailContent render(
            MailTemplates templates, ProductName productName, String prenom, String lienEspace) {
        return templates.render(
                TEMPLATE,
                productName.subject("confirmez-vous votre planning ?"),
                MailTemplates.values("prenom", blankToNull(prenom), "lienEspace", lienEspace));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
