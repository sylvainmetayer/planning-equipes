package dev.sylvain.planning.service.notification;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import dev.sylvain.planning.domain.DemandeEchange;
import dev.sylvain.planning.domain.StatutDemandeEchange;
import dev.sylvain.planning.service.AdminAddress;
import dev.sylvain.planning.service.ApplicationLinks;
import dev.sylvain.planning.service.ProductName;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Turns a {@link Notification} into the {@link MailDraft} that says it — the
 * only place that knows the wording, and a pure function of the notification
 * plus the configuration: no transport, no I/O, no exception handling.
 *
 * <p>An empty result means "nobody to tell": no admin address configured, or
 * an animateur with no address on their fiche. That is a normal outcome, not a
 * failure — they still see everything in their espace.</p>
 *
 * <p>The dispatch is an exhaustive {@code switch} over the sealed
 * {@link Notification} hierarchy, deliberately without a {@code default}: a new
 * notification does not compile until its wording exists.</p>
 */
@ApplicationScoped
public class NotificationWriter {

    /** « samedi 11 juillet » — the same way a planning is read aloud. */
    private static final DateTimeFormatter JOUR =
            DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH);

    @Inject
    AdminAddress adminAddress;

    @Inject
    ApplicationLinks liens;

    /** Every subject opens with the deployment's own name, never the vendor's. */
    @Inject
    ProductName productName;

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
        String sujet = productName.subject(
                "déclaration de disponibilités de " + n.animateurNomComplet());
        StringBuilder corps = new StringBuilder()
                .append(n.animateurNomComplet())
                .append(" a déclaré ses disponibilités depuis son espace : ")
                .append(n.joursIndisponibles() == 0
                        ? "aucun jour d'indisponibilité"
                        : n.joursIndisponibles() + (n.joursIndisponibles() == 1
                                ? " jour d'indisponibilité"
                                : " jours d'indisponibilité"))
                .append(", ")
                .append(n.souhaits() == 0
                        ? "aucun souhait"
                        : n.souhaits() + (n.souhaits() == 1 ? " souhait" : " souhaits"))
                .append(".\n\nRien n'est appliqué tant que vous ne l'avez pas validé.\n");
        liens.disponibilitesScreen().ifPresent(lien -> corps
                .append("\nÀ valider ou refuser depuis l'écran Disponibilités : ")
                .append(lien).append('\n'));
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
        StringBuilder corps = new StringBuilder();
        if (n.prenom() != null && !n.prenom().isBlank()) {
            corps.append("Bonjour ").append(n.prenom()).append(",\n\n");
        }
        corps.append("Petit rappel : vous êtes attendu·e demain, ").append(JOUR.format(n.date()))
                .append(".\n\n");
        for (String poste : n.postes()) {
            corps.append("- ").append(poste).append('\n');
        }
        corps.append("\nC'est le planning qui vous a été communiqué ; s'il a changé depuis, "
                + "vous auriez reçu un message le disant.\n");
        if (n.lienEspace() != null) {
            corps.append("\nVotre espace personnel : ").append(n.lienEspace()).append('\n');
        }
        return Optional.of(new MailDraft(n.email(), sujet, corps.toString()));
    }

    /** One reminder, never a series: the RELANCE status is what makes it the last. */
    private Optional<MailDraft> relanceConfirmation(Notification.RelanceConfirmation n) {
        if (withoutRecipient(n.email())) {
            return Optional.empty();
        }
        StringBuilder corps = new StringBuilder();
        if (n.prenom() != null && !n.prenom().isBlank()) {
            corps.append("Bonjour ").append(n.prenom()).append(",\n\n");
        }
        corps.append("Votre planning a été publié et nous n'avons pas encore votre confirmation.\n\n")
                .append("Un clic suffit, depuis votre espace personnel : « J'ai lu et je serai là ».\n");
        if (n.lienEspace() != null) {
            corps.append('\n').append(n.lienEspace()).append('\n');
        }
        corps.append("\nSi quelque chose ne va pas sur ce planning, c'est le moment de le dire.\n");
        return Optional.of(new MailDraft(n.email(),
                productName.subject("confirmez-vous votre planning ?"), corps.toString()));
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
        StringBuilder corps = new StringBuilder()
                .append(n.nombre() == 1 ? "Une demande d'échange attend" : n.nombre() + " demandes d'échange attendent")
                .append(" depuis plus longtemps que le délai que vous avez fixé — la plus ancienne depuis ")
                .append(n.joursMax()).append(n.joursMax() > 1 ? " jours" : " jour").append(".\n\n")
                .append("Chacune n'est signalée qu'une fois : ce message ne reviendra pas chaque nuit.\n");
        liens.echangesScreen().ifPresent(lien -> corps
                .append("\nÀ trancher depuis l'écran Échanges : ").append(lien).append('\n'));
        return Optional.of(new MailDraft(admin.get(), sujet, corps.toString()));
    }

    private Optional<MailDraft> targetSolicited(Notification.TargetSolicited n) {
        if (withoutRecipient(n.emailCible())) {
            return Optional.empty();
        }
        String sujet = productName.subject(n.demandeurNomComplet()
                + (n.nombre() == 1 ? " vous propose un échange de créneau" : " vous propose des échanges de créneaux"));
        String corps = new StringBuilder()
                .append(n.demandeurNomComplet()).append(" vous propose ")
                .append(n.nombre() == 1 ? "un échange de créneau" : n.nombre() + " échanges de créneaux")
                .append(".\n\nAcceptez ou déclinez depuis votre espace personnel (lien imprimé sur votre ")
                .append("planning PDF), onglet Échanges : votre accord est nécessaire avant que ")
                .append("l'organisation ne tranche.\n")
                .toString();
        return Optional.of(new MailDraft(n.emailCible(), sujet, corps));
    }

    private Optional<MailDraft> demandeDeclinee(Notification.DemandeDeclinee n) {
        if (withoutRecipient(n.emailDemandeur())) {
            return Optional.empty();
        }
        StringBuilder corps = new StringBuilder()
                .append(n.cibleNomComplet()).append(" a décliné votre demande d'échange");
        if (n.libelleCreneau() != null) {
            corps.append(" (créneau ").append(n.libelleCreneau()).append(")");
        }
        corps.append(".\nVous pouvez proposer l'échange à quelqu'un d'autre depuis votre espace.\n");
        return Optional.of(new MailDraft(n.emailDemandeur(),
                productName.subject("votre demande d'échange a été déclinée"), corps.toString()));
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
        StringBuilder corps = new StringBuilder()
                .append(n.demandeurNomComplet())
                .append(" a soumis ")
                .append(demandes.size() == 1
                        ? "une demande d'échange de créneau"
                        : demandes.size() + " demandes d'échange de créneaux")
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
        liens.echangesScreen().ifPresent(lien -> corps
                .append("À valider ou refuser depuis l'écran Échanges : ")
                .append(lien).append('\n'));
        return Optional.of(new MailDraft(admin.get(), sujet, corps.toString()));
    }

    private Optional<MailDraft> resolutionTerminee(Notification.ResolutionTerminee n) {
        Optional<String> admin = adminAddress.resolue();
        if (admin.isEmpty()) {
            return Optional.empty();
        }
        String etat = n.faisable() ? "planning faisable" : "planning NON faisable";
        String sujet = productName.subject("résolution terminée sur « " + n.editionNom() + " » : " + etat);
        StringBuilder corps = new StringBuilder()
                .append("Édition : ").append(n.editionNom()).append('\n')
                .append("Score : ").append(n.score() == null ? "non mesuré" : n.score()).append('\n')
                .append("Faisabilité : ").append(n.faisable()
                        ? "aucune contrainte dure violée"
                        : "au moins une contrainte dure reste violée — le planning n'est pas utilisable en l'état")
                .append('\n');
        liens.problemesScreen().ifPresent(lien -> corps
                .append("\nDétail des contraintes en défaut : ").append(lien).append('\n'));
        return Optional.of(new MailDraft(admin.get(), sujet, corps.toString()));
    }

    private static boolean withoutRecipient(String email) {
        return email == null || email.isBlank();
    }
}
