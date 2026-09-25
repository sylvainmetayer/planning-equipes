package dev.sylvain.planning.service.publication;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.espace.DeclarationDisponibiliteService;
import dev.sylvain.planning.service.mail.LastDelivery;
import dev.sylvain.planning.service.mail.MailDeliveryLog;
import dev.sylvain.planning.service.mail.MailKind;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * « Renvoyer les envois en échec », from the Animateurs page: sends again,
 * to each person whose last mail failed for a <b>temporary</b> reason, the mail
 * that failed — the relay was down, it answered « try later », the
 * credentials were being rotated.
 *
 * <p>Who is concerned is read from the delivery journal, and from nothing
 * else: the people whose {@code dernierEnvoi} is an {@code ECHEC} of any
 * category <b>but</b> {@code ADRESSE_REFUSEE}. A refused address stays blocked
 * — resending would only earn the same refusal — until the address changes,
 * which hides the failure by itself.</p>
 *
 * <p>What is sent depends on what failed, and each resend goes through the
 * service that owns that mail, so it keeps that service's rules — the
 * one-reminder-per-publication rule of the confirmation reminder included —
 * and is journalled like any other: a resend that leaves turns the last line
 * of the journal into {@code ENVOYE}, which is what takes the person off the
 * screen's failures.</p>
 *
 * <table>
 *   <caption>What each failed mail gets</caption>
 *   <tr><th>Last mail</th><th>Sent again</th><th>Only when</th></tr>
 *   <tr><td>published or individual planning, day-before reminder</td>
 *       <td>the published individual planning</td>
 *       <td>they hold a seat in the published plan</td></tr>
 *   <tr><td>manual or nightly reminder</td><td>the confirmation reminder</td>
 *       <td>they have not confirmed yet</td></tr>
 *   <tr><td>availability invitation</td><td>the invitation</td>
 *       <td>the collection still invites</td></tr>
 *   <tr><td>access code, swap notifications</td><td>nothing</td>
 *       <td>— the code is asked for again from the espace, and a swap
 *       notification announces a moment that has passed</td></tr>
 * </table>
 */
@ApplicationScoped
public class FailedMailResendService {

    /** Why a failed mail was not sent again. */
    public enum ResendRefusal {
        /** An access code or a swap notification: nothing to resend. */
        TYPE_NON_RENVOYABLE,
        /** Nothing was ever published: there is no planning to resend nor to confirm. */
        JAMAIS_PUBLIE,
        /** No seat in the published plan: nothing to send them. */
        SANS_POSTE,
        /** The reminder is moot: they confirmed in the meantime. */
        DEJA_CONFIRME,
        /** The reminder of this publication already reached them. */
        DEJA_RELANCE,
        /** The collection is switched off or its window is over. */
        COLLECTE_FERMEE,
        /** The address was removed from the fiche since. */
        SANS_ADRESSE,
        /** The relay refused the address in the meantime. */
        ADRESSE_REFUSEE
    }

    /** One person left alone, by id, and why. */
    @Schema(requiredProperties = {"animateurId", "motif"})
    public record NotResent(String animateurId, ResendRefusal motif) {}

    /**
     * Ids only in every list, like the reminder's report: the screen turns
     * them into names, and nothing nominative travels.
     *
     * @param renvoyes       the mail left again
     * @param echecs         it failed again, and is journalled as such
     * @param nonRenvoyables left alone, each with its reason
     */
    @Schema(requiredProperties = {"renvoyes", "echecs", "nonRenvoyables"})
    public record ResendReport(List<String> renvoyes, List<String> echecs, List<NotResent> nonRenvoyables) {}

    private final MailDeliveryLog deliveries;

    private final PlanPublieService planPublieService;

    private final ConfirmationPlanningService confirmationService;

    private final PlanningDeliveryService deliveryService;

    private final RelanceManuelleService relanceService;

    private final DeclarationDisponibiliteService declarationService;

    @Inject
    public FailedMailResendService(
            MailDeliveryLog deliveries,
            PlanPublieService planPublieService,
            ConfirmationPlanningService confirmationService,
            PlanningDeliveryService deliveryService,
            RelanceManuelleService relanceService,
            DeclarationDisponibiliteService declarationService) {
        this.deliveries = deliveries;
        this.planPublieService = planPublieService;
        this.confirmationService = confirmationService;
        this.deliveryService = deliveryService;
        this.relanceService = relanceService;
        this.declarationService = declarationService;
    }

    /** Sends again every mail that failed for a temporary reason. An empty report when there is none. */
    public ResendReport resendFailed() {
        Map<String, MailKind> aRenvoyer = new LinkedHashMap<>();
        for (Map.Entry<String, LastDelivery> entree :
                deliveries.latestByAnimateur().entrySet()) {
            LastDelivery dernier = entree.getValue();
            if (dernier.failed() && !dernier.blocksAddress()) {
                aRenvoyer.put(entree.getKey(), kindOf(dernier));
            }
        }
        Sorting tri = new Sorting();
        if (aRenvoyer.isEmpty()) {
            return tri.report();
        }
        boolean jamaisPublie = planPublieService.jamaisPublie();
        Plan plan = new Plan(jamaisPublie);
        List<String> aRelancer = new ArrayList<>();
        for (Map.Entry<String, MailKind> entree : aRenvoyer.entrySet()) {
            String animateurId = entree.getKey();
            MailKind kind = entree.getValue();
            if (kind == null) {
                tri.skip(animateurId, ResendRefusal.TYPE_NON_RENVOYABLE);
                continue;
            }
            switch (kind) {
                case PLANNING_PUBLIE, PLANNING_INDIVIDUEL, RAPPEL_VEILLE -> resendPlanning(animateurId, plan, tri);
                case RELANCE_MANUELLE, RELANCE_NUIT -> {
                    if (jamaisPublie) {
                        tri.skip(animateurId, ResendRefusal.JAMAIS_PUBLIE);
                    } else {
                        aRelancer.add(animateurId);
                    }
                }
                case INVITATION_DECLARATION -> resendInvitation(animateurId, tri);
                case CODE_ACCES, ECHANGE_SOLLICITATION, ECHANGE_DECLINEE ->
                    tri.skip(animateurId, ResendRefusal.TYPE_NON_RENVOYABLE);
            }
        }
        if (!aRelancer.isEmpty()) {
            remind(aRelancer, tri);
        }
        return tri.report();
    }

    private void resendPlanning(String animateurId, Plan plan, Sorting tri) {
        if (plan.jamaisPublie) {
            tri.skip(animateurId, ResendRefusal.JAMAIS_PUBLIE);
            return;
        }
        ConfirmationPlanningService.ConfirmationView reponse = plan.reponses().get(animateurId);
        Animateur fiche = plan.fiches().get(animateurId);
        if (reponse == null || !reponse.affecte() || fiche == null) {
            tri.skip(animateurId, ResendRefusal.SANS_POSTE);
            return;
        }
        if (!PlanningDeliveryService.hasAddress(fiche)) {
            tri.skip(animateurId, ResendRefusal.SANS_ADRESSE);
            return;
        }
        if (deliveryService.resend(plan.publie(), fiche)) {
            tri.renvoyes.add(animateurId);
        } else {
            tri.echecs.add(animateurId);
        }
    }

    private void resendInvitation(String animateurId, Sorting tri) {
        if (!declarationService.invitationsAllowed()) {
            tri.skip(animateurId, ResendRefusal.COLLECTE_FERMEE);
        } else if (declarationService.inviteAgain(animateurId)) {
            tri.renvoyes.add(animateurId);
        } else {
            tri.echecs.add(animateurId);
        }
    }

    /** Through the manual reminder, whose one-reminder-per-publication rule applies unchanged. */
    private void remind(List<String> animateurIds, Sorting tri) {
        RelanceManuelleService.RapportRelance rapport = relanceService.relancer(animateurIds);
        tri.renvoyes.addAll(rapport.envoyes());
        tri.echecs.addAll(rapport.echecs());
        rapport.dejaConfirmes().forEach(id -> tri.skip(id, ResendRefusal.DEJA_CONFIRME));
        rapport.dejaRelancesPourCettePublication().forEach(id -> tri.skip(id, ResendRefusal.DEJA_RELANCE));
        rapport.sansEmail().forEach(id -> tri.skip(id, ResendRefusal.SANS_ADRESSE));
        rapport.sansPoste().forEach(id -> tri.skip(id, ResendRefusal.SANS_POSTE));
        rapport.adresseRefusee().forEach(id -> tri.skip(id, ResendRefusal.ADRESSE_REFUSEE));
    }

    /** A line written under a kind this version no longer knows reads as not resendable, never as an error. */
    private static MailKind kindOf(LastDelivery dernier) {
        try {
            return MailKind.valueOf(dernier.type());
        } catch (IllegalArgumentException | NullPointerException _) {
            return null;
        }
    }

    /** The three lists of the report, filled one person at a time. */
    private static final class Sorting {
        private final List<String> renvoyes = new ArrayList<>();
        private final List<String> echecs = new ArrayList<>();
        private final List<NotResent> nonRenvoyables = new ArrayList<>();

        void skip(String animateurId, ResendRefusal motif) {
            nonRenvoyables.add(new NotResent(animateurId, motif));
        }

        ResendReport report() {
            return new ResendReport(List.copyOf(renvoyes), List.copyOf(echecs), List.copyOf(nonRenvoyables));
        }
    }

    /**
     * The published plan and who holds a seat in it, read once and only when
     * a planning is actually resent.
     */
    private final class Plan {
        private final boolean jamaisPublie;
        private PlanningEvenement publie;
        private Map<String, Animateur> fiches;
        private Map<String, ConfirmationPlanningService.ConfirmationView> reponses;

        Plan(boolean jamaisPublie) {
            this.jamaisPublie = jamaisPublie;
        }

        PlanningEvenement publie() {
            if (publie == null) {
                publie = planPublieService.planPublie();
            }
            return publie;
        }

        Map<String, Animateur> fiches() {
            if (fiches == null) {
                fiches = new LinkedHashMap<>();
                for (Animateur animateur : publie().getAnimateurs()) {
                    fiches.put(animateur.getId(), animateur);
                }
            }
            return fiches;
        }

        Map<String, ConfirmationPlanningService.ConfirmationView> reponses() {
            if (reponses == null) {
                reponses = new LinkedHashMap<>();
                for (ConfirmationPlanningService.ConfirmationView vue : confirmationService.byAnimateur()) {
                    reponses.put(vue.animateurId(), vue);
                }
            }
            return reponses;
        }
    }
}
