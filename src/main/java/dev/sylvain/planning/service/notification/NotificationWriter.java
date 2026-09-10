package dev.sylvain.planning.service.notification;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import dev.sylvain.planning.domain.DemandeEchange;
import dev.sylvain.planning.service.publication.AdminAddress;
import dev.sylvain.planning.service.ApplicationLinks;
import dev.sylvain.planning.service.ProductName;
import dev.sylvain.planning.service.mail.MailTemplates;
import dev.sylvain.planning.service.mail.MailTemplates.MailContent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Turns a {@link Notification} into the {@link MailDraft} that says it — the
 * only place that decides who is told and under which subject, and a pure
 * function of the notification plus the configuration: no transport, no I/O,
 * no exception handling. The body itself is a pair of Qute templates under
 * {@code resources/templates/mail/}, rendered by {@link MailTemplates}: this
 * class names the template and hands it its values.
 *
 * <p>An empty result means "nobody to tell": no admin address configured, or
 * an animateur with no address on their fiche. That is a normal outcome, not a
 * failure — they still see everything in their espace.</p>
 *
 * <p>The dispatch is an exhaustive {@code switch} over the sealed
 * {@link Notification} hierarchy, deliberately without a {@code default}: a new
 * notification does not compile until its case — and so its template — exists.</p>
 */
@ApplicationScoped
public class NotificationWriter {

    /**
     * « samedi 11 juillet » — the same way a planning is read aloud.
     *
     * <p>Package-private rather than private: {@code RappelVeilleJob} words the
     * same day in the alert it leaves on the Notifications screen, and two
     * definitions would drift into a mail saying « samedi 11 juillet » next to
     * an alert saying « 2026-07-11 ».</p>
     */
    static final DateTimeFormatter JOUR =
            DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH);

    @Inject
    AdminAddress adminAddress;

    @Inject
    ApplicationLinks liens;

    /** Every subject opens with the deployment's own name, never the vendor's. */
    @Inject
    ProductName productName;

    @Inject
    MailTemplates templates;

    public Optional<MailDraft> rediger(Notification notification) {
        return switch (notification) {
            case Notification.TargetSolicited n -> targetSolicited(n);
            case Notification.DemandeDeclinee n -> demandeDeclinee(n);
            case Notification.DemandesSoumises n -> demandesSoumises(n);
            case Notification.DeclarationSoumise n -> declarationSoumise(n);
            case Notification.ResolutionTerminee n -> resolutionTerminee(n);
            case Notification.RappelVeille n -> rappelVeille(n);
            case Notification.RelanceConfirmation n -> relanceConfirmation(n);
            case Notification.PendingEchanges n -> pendingEchanges(n);
        };
    }

    private Optional<MailDraft> declarationSoumise(Notification.DeclarationSoumise n) {
        Optional<String> admin = adminAddress.resolue();
        if (admin.isEmpty()) {
            return Optional.empty();
        }
        String sujet = productName.subject("déclaration de disponibilités de " + n.animateurNomComplet());
        return Optional.of(draft(admin.get(), "mail/declaration-soumise", sujet, MailTemplates.values(
                "nom", n.animateurNomComplet(),
                "joursIndisponibles", n.joursIndisponibles(),
                "souhaits", n.souhaits(),
                "lien", liens.disponibilitesScreen().orElse(null))));
    }

    /**
     * The day-before reminder. Repeats the seats of the <b>published</b> plan
     * and nothing else: a reminder that announced a change would be a
     * publication in disguise, sent at night, with nobody having reviewed it.
     */
    private Optional<MailDraft> rappelVeille(Notification.RappelVeille n) {
        if (withoutRecipient(n.email()) || n.postes().isEmpty()) {
            return Optional.empty();
        }
        String sujet = productName.subject("demain, " + JOUR.format(n.date()) + " — votre planning");
        return Optional.of(draft(n.email(), "mail/rappel-veille", sujet, MailTemplates.values(
                "prenom", blankToNull(n.prenom()),
                "jour", JOUR.format(n.date()),
                "postes", n.postes(),
                "lienEspace", n.lienEspace())));
    }

    /** One reminder, never a series: the RELANCE status is what makes it the last. */
    private Optional<MailDraft> relanceConfirmation(Notification.RelanceConfirmation n) {
        if (withoutRecipient(n.email())) {
            return Optional.empty();
        }
        return Optional.of(draft(n.email(), "mail/relance-confirmation",
                productName.subject("confirmez-vous votre planning ?"), MailTemplates.values(
                        "prenom", blankToNull(n.prenom()),
                        "lienEspace", n.lienEspace())));
    }

    /**
     * Counted, never named: the admin has to know a queue is ageing, and the
     * Échanges screen — one click away — is where the people are.
     */
    private Optional<MailDraft> pendingEchanges(Notification.PendingEchanges n) {
        Optional<String> admin = adminAddress.resolue();
        if (admin.isEmpty() || n.nombre() <= 0) {
            return Optional.empty();
        }
        String sujet = productName.subject(n.nombre() == 1
                ? "une demande d'échange attend une décision"
                : n.nombre() + " demandes d'échange attendent une décision");
        return Optional.of(draft(admin.get(), "mail/echanges-en-attente", sujet, MailTemplates.values(
                "nombre", n.nombre(),
                "joursMax", n.joursMax(),
                "lien", liens.echangesScreen().orElse(null))));
    }

    private Optional<MailDraft> targetSolicited(Notification.TargetSolicited n) {
        if (withoutRecipient(n.emailCible())) {
            return Optional.empty();
        }
        String sujet = productName.subject(n.demandeurNomComplet()
                + (n.nombre() == 1 ? " vous propose un échange de créneau" : " vous propose des échanges de créneaux"));
        return Optional.of(draft(n.emailCible(), "mail/echange-propose", sujet, MailTemplates.values(
                "demandeur", n.demandeurNomComplet(),
                "nombre", n.nombre())));
    }

    private Optional<MailDraft> demandeDeclinee(Notification.DemandeDeclinee n) {
        if (withoutRecipient(n.emailDemandeur())) {
            return Optional.empty();
        }
        return Optional.of(draft(n.emailDemandeur(), "mail/echange-decline",
                productName.subject("votre demande d'échange a été déclinée"), MailTemplates.values(
                        "cible", n.cibleNomComplet(),
                        "libelleCreneau", n.libelleCreneau())));
    }

    private Optional<MailDraft> demandesSoumises(Notification.DemandesSoumises n) {
        List<DemandeEchange> demandes = n.demandes();
        Optional<String> admin = adminAddress.resolue();
        if (admin.isEmpty() || demandes.isEmpty()) {
            return Optional.empty();
        }
        String sujet = productName.subject(demandes.size() == 1
                ? "nouvelle demande d'échange de " + n.demandeurNomComplet()
                : demandes.size() + " nouvelles demandes d'échange de " + n.demandeurNomComplet());
        long infaisables = demandes.stream()
                .filter(demande -> Boolean.FALSE.equals(demande.getPrevalidationOk()))
                .count();
        return Optional.of(draft(admin.get(), "mail/demandes-soumises", sujet, MailTemplates.values(
                "demandeur", n.demandeurNomComplet(),
                "nombre", demandes.size(),
                "infaisables", infaisables,
                "lien", liens.echangesScreen().orElse(null))));
    }

    private Optional<MailDraft> resolutionTerminee(Notification.ResolutionTerminee n) {
        Optional<String> admin = adminAddress.resolue();
        if (admin.isEmpty()) {
            return Optional.empty();
        }
        String etat = n.faisable() ? "planning faisable" : "planning NON faisable";
        String sujet = productName.subject("résolution terminée sur « " + n.editionNom() + " » : " + etat);
        return Optional.of(draft(admin.get(), "mail/resolution-terminee", sujet, MailTemplates.values(
                "edition", n.editionNom(),
                "score", n.score(),
                "faisable", n.faisable(),
                "lien", liens.problemesScreen().orElse(null))));
    }

    private MailDraft draft(String destinataire, String template, String sujet, Map<String, Object> values) {
        MailContent content = templates.render(template, sujet, values);
        return new MailDraft(destinataire, content.subject(), content.text(), content.html());
    }

    private static boolean withoutRecipient(String email) {
        return email == null || email.isBlank();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
