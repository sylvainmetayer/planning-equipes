package dev.sylvain.planning.service.notification;

import java.util.List;

import dev.sylvain.planning.domain.DemandeEchange;

/**
 * Something worth telling someone about, as a fact rather than as an order to
 * send a mail. Business services fire these; they do not know that a mail is
 * what comes out, nor whether anything comes out at all.
 *
 * <p>The type is {@code sealed} on purpose: {@link NotificationWriter}
 * turns a notification into a message with an exhaustive {@code switch} and no
 * {@code default} branch, so adding a case here is a <b>compile error</b>
 * until someone writes what it says. A notification that silently produced no
 * mail is exactly the failure this shape removes.</p>
 *
 * @see NotificationDispatcher for the delivery policy (best-effort, in one place)
 */
public sealed interface Notification {

    /**
     * The colleague a demande targets is waiting for THEIR agreement — the
     * step that spares the admin from asking both sides.
     */
    record TargetSolicited(String emailCible, String demandeurNomComplet, int nombre) implements Notification {
    }

    /** The targeted colleague declined; the admin never had to arbitrate. */
    record DemandeDeclinee(String emailDemandeur, String cibleNomComplet, String libelleCreneau)
            implements Notification {
    }

    /** One or more demandes reached the admin's desk — one notification per batch, not per demande. */
    record DemandesSoumises(String demandeurNomComplet, List<DemandeEchange> demandes) implements Notification {
    }

    /**
     * An animateur declared their unavailable days and wishes from their
     * espace (issue #291), and the proposal is waiting on the admin's desk.
     *
     * <p>Carries counts rather than the days and the categories themselves:
     * the mail says that something is waiting and where to look at it, the
     * screen says what it contains. A mail is the least protected place these
     * data pass through, and nothing here needs them to do its job.</p>
     */
    record DeclarationSoumise(String animateurNomComplet, int joursIndisponibles, int souhaits)
            implements Notification {
    }

    /**
     * A solve just finished: which edition, what score, and whether the plan is
     * feasible — the three facts one waits for when a multi-minute run was
     * launched before walking away.
     *
     * @param faisable no hard constraint left broken; anything else means the
     *                 plan cannot be used as is, which is why it is said in the
     *                 subject line rather than in the body
     */
    record ResolutionTerminee(String editionNom, String score, boolean faisable) implements Notification {
    }
}
