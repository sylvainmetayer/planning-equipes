package dev.sylvain.planning.service.notification;

import java.util.List;
import java.util.Optional;

import dev.sylvain.planning.domain.DemandeEchange;
import dev.sylvain.planning.domain.StatutDemandeEchange;
import dev.sylvain.planning.service.AdminAddress;
import dev.sylvain.planning.service.ApplicationLinks;
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

    @Inject
    AdminAddress adminAddress;

    @Inject
    ApplicationLinks liens;

    public Optional<MailDraft> rediger(Notification notification) {
        return switch (notification) {
            case Notification.TargetSolicited n -> targetSolicited(n);
            case Notification.DemandeDeclinee n -> demandeDeclinee(n);
            case Notification.DemandesSoumises n -> demandesSoumises(n);
            case Notification.DemandeTranchee n -> demandeTranchee(n);
            case Notification.ResolutionTerminee n -> resolutionTerminee(n);
        };
    }

    private Optional<MailDraft> targetSolicited(Notification.TargetSolicited n) {
        if (withoutRecipient(n.emailCible())) {
            return Optional.empty();
        }
        String sujet = "Planning Équipes — " + n.demandeurNomComplet()
                + (n.nombre() == 1 ? " vous propose un échange de créneau" : " vous propose des échanges de créneaux");
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
                "Planning Équipes — votre demande d'échange a été déclinée", corps.toString()));
    }

    private Optional<MailDraft> demandesSoumises(Notification.DemandesSoumises n) {
        List<DemandeEchange> demandes = n.demandes();
        Optional<String> admin = adminAddress.resolue();
        if (admin.isEmpty() || demandes.isEmpty()) {
            return Optional.empty();
        }
        String sujet = demandes.size() == 1
                ? "Planning Équipes — nouvelle demande d'échange de " + n.demandeurNomComplet()
                : "Planning Équipes — " + demandes.size() + " nouvelles demandes d'échange de " + n.demandeurNomComplet();
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

    private Optional<MailDraft> demandeTranchee(Notification.DemandeTranchee n) {
        if (withoutRecipient(n.emailDemandeur())) {
            return Optional.empty();
        }
        DemandeEchange demande = n.demande();
        boolean acceptee = demande.getStatut() == StatutDemandeEchange.ACCEPTEE;
        String sujet = acceptee
                ? "Planning Équipes — votre demande d'échange est acceptée"
                : "Planning Équipes — votre demande d'échange est refusée";
        StringBuilder corps = new StringBuilder()
                .append("Votre demande d'échange")
                .append(n.libelleCreneau() == null || n.libelleCreneau().isBlank()
                        ? ""
                        : " (" + n.libelleCreneau() + ")")
                .append(acceptee
                        ? " a été acceptée : le planning a été mis à jour.\n"
                        : " a été refusée : le planning reste inchangé.\n");
        if (demande.getCommentaireAdmin() != null && !demande.getCommentaireAdmin().isBlank()) {
            corps.append("\nCommentaire de l'organisation : ")
                    .append(demande.getCommentaireAdmin()).append('\n');
        }
        return Optional.of(new MailDraft(n.emailDemandeur(), sujet, corps.toString()));
    }

    private Optional<MailDraft> resolutionTerminee(Notification.ResolutionTerminee n) {
        Optional<String> admin = adminAddress.resolue();
        if (admin.isEmpty()) {
            return Optional.empty();
        }
        String etat = n.faisable() ? "planning faisable" : "planning NON faisable";
        String sujet = "Planning Équipes — résolution terminée sur « " + n.editionNom() + " » : " + etat;
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
