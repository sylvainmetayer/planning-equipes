package dev.sylvain.planning.service.publication;

import dev.sylvain.planning.service.ProductName;
import dev.sylvain.planning.service.mail.MailMetrics;
import dev.sylvain.planning.service.mail.MailTemplates;
import dev.sylvain.planning.service.mail.MailTemplates.MailContent;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * The mails an administrator <b>asks for</b>, and only those: sending an
 * animateur their individual planning, sending an espace access code, inviting
 * the animateurs to declare their availability, reminding the silent ones by
 * hand from the Animateurs page, sending the test mail of the Débogage screen.
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

    /** Template value: the animateur's first name, which every mail to one of them opens with. */
    private static final String KEY_PRENOM = "prenom";

    private static final String INDIVIDUAL_PLANNING = "mail/planning-individuel";
    private static final String PUBLISHED_PLANNING = "mail/planning-publie";
    private static final String AVAILABILITY_INVITATION = "mail/invitation-declaration";
    private static final String TEST_MAIL = "mail/test";

    private final Mailer mailer;

    private final AdminAddress adminAddress;

    /**
     * Every subject opens with the product name of this deployment: the first
     * thing an animateur reads in their inbox must be the service they signed
     * up with, not the software vendor's.
     */
    private final ProductName productName;

    private final MailTemplates templates;

    /** Every send goes through it, so every send is counted by its template. */
    private final MailMetrics metrics;

    @Inject
    public MailService(
            Mailer mailer,
            AdminAddress adminAddress,
            ProductName productName,
            MailTemplates templates,
            MailMetrics metrics) {
        this.mailer = mailer;
        this.adminAddress = adminAddress;
        this.productName = productName;
        this.templates = templates;
        this.metrics = metrics;
    }

    /**
     * Sends one animateur their individual planning: the PDF attached, the
     * espace link in the body.
     */
    public void sendIndividualPlanning(
            String emailAnimateur, String prenom, String lienEspace, byte[] pdf, String fileName) {
        MailContent content = templates.render(
                INDIVIDUAL_PLANNING,
                productName.subject("votre planning individuel"),
                MailTemplates.values(KEY_PRENOM, blankToNull(prenom), "lienEspace", blankToNull(lienEspace)));
        metrics.send(
                mailer,
                INDIVIDUAL_PLANNING,
                templates.toMail(emailAnimateur, content).addAttachment(fileName, pdf, "application/pdf"));
    }

    /**
     * What the mail announcing a publication says, around the attached planning.
     *
     * @param premiereDiffusion nothing was ever published to this person, so
     *                          their whole planning is the news; the change
     *                          list is then their planning, not a list of
     *                          corrections, and is left out of the body
     * @param changements       one sentence per moved vacation, in reading order
     * @param demandes          where their échange requests stand, if any
     * @param journeesModifiees the days of their planning a consigne governs
     *                          (issue #4), each with its motif — an arrêté is
     *                          the one reason a vacation moves that the person
     *                          must hear, not just see
     */
    public record PlanningPublie(
            String prenom,
            String lienEspace,
            boolean premiereDiffusion,
            List<String> changements,
            List<String> demandes,
            List<String> journeesModifiees) {}

    private static List<String> orEmpty(List<String> lignes) {
        return lignes == null ? List.of() : lignes;
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
     * @param message what the mail says around the attached planning
     */
    public void sendPlanningPublie(String emailAnimateur, byte[] pdf, String fileName, PlanningPublie message) {
        MailContent content = templates.render(
                PUBLISHED_PLANNING,
                productName.subject(
                        message.premiereDiffusion() ? "votre planning individuel" : "votre planning a changé"),
                MailTemplates.values(
                        KEY_PRENOM,
                        blankToNull(message.prenom()),
                        "lienEspace",
                        blankToNull(message.lienEspace()),
                        "premiereDiffusion",
                        message.premiereDiffusion(),
                        "changements",
                        orEmpty(message.changements()),
                        "demandes",
                        orEmpty(message.demandes()),
                        "journeesModifiees",
                        orEmpty(message.journeesModifiees())));
        metrics.send(
                mailer,
                PUBLISHED_PLANNING,
                templates.toMail(emailAnimateur, content).addAttachment(fileName, pdf, "application/pdf"));
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
    public void sendInvitationDeclaration(
            String emailAnimateur, String prenom, String lienDeclaration, LocalDate debut, LocalDate fin) {
        MailContent content = templates.render(
                AVAILABILITY_INVITATION,
                productName.subject("vos disponibilités sont attendues"),
                MailTemplates.values(
                        KEY_PRENOM,
                        blankToNull(prenom),
                        "lienDeclaration",
                        lienDeclaration,
                        "fenetre",
                        describeFenetre(debut, fin)));
        metrics.send(mailer, AVAILABILITY_INVITATION, templates.toMail(emailAnimateur, content));
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
     * Reminds one animateur, by hand, that their published planning is still
     * waiting for their « J'ai lu et je serai là » (issue #504).
     *
     * <p>The same words as the nightly reminder — {@link RelanceConfirmationMail}
     * renders both — but not the same policy: the night is best-effort and
     * swallows a failed send, while this one is an organiser clicking
     * « Relancer maintenant » the day before the event, so a failure
     * <b>propagates</b> and {@link RelanceManuelleService} reports it by id.
     * That difference in policy is exactly why it lives here and not as a
     * {@code Notification}.</p>
     */
    public void sendRelanceConfirmation(String emailAnimateur, String prenom, String lienEspace) {
        MailContent content = RelanceConfirmationMail.render(templates, productName, prenom, lienEspace);
        metrics.send(mailer, RelanceConfirmationMail.TEMPLATE, templates.toMail(emailAnimateur, content));
    }

    /**
     * Sends a test mail to the admin address. The whole point of the Débogage
     * button is to surface a broken SMTP setup, so this propagates like the
     * two above.
     */
    public String sendTestMail() {
        String destinataire = adminAddress
                .resolue()
                .orElseThrow(() ->
                        new IllegalStateException("Aucune adresse e-mail administrateur configurée (MAIL_ADMIN)."));
        MailContent content = templates.render(
                TEST_MAIL,
                productName.subject("mail de test"),
                MailTemplates.values(
                        "horodatage", ZonedDateTime.now(ZoneId.systemDefault()).toString()));
        metrics.send(mailer, TEST_MAIL, templates.toMail(destinataire, content));
        return destinataire;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
