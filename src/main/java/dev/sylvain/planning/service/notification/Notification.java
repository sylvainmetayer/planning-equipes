package dev.sylvain.planning.service.notification;

import dev.sylvain.planning.domain.DemandeEchange;
import java.time.LocalDate;
import java.util.List;

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
    record TargetSolicited(String emailCible, String demandeurNomComplet, int nombre) implements Notification {}

    /** The targeted colleague declined; the admin never had to arbitrate. */
    record DemandeDeclinee(String emailDemandeur, String cibleNomComplet, String libelleCreneau)
            implements Notification {}

    /** One or more demandes reached the admin's desk — one notification per batch, not per demande. */
    record DemandesSoumises(String demandeurNomComplet, List<DemandeEchange> demandes) implements Notification {}

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
            implements Notification {}

    /**
     * A solve just finished: which edition, what score, and whether the plan is
     * feasible — the three facts one waits for when a multi-minute run was
     * launched before walking away.
     *
     * @param faisable no hard constraint left broken; anything else means the
     *                 plan cannot be used as is, which is why it is said in the
     *                 subject line rather than in the body
     */
    record ResolutionTerminee(String editionNom, String score, boolean faisable) implements Notification {}

    /**
     * The day-before reminder (issue #298): what this person holds tomorrow,
     * in the published plan and in no other.
     *
     * @param postes one line per seat, already worded — the reminder repeats
     *               what was communicated, it never announces anything new
     * @param lienEspace their espace, {@code null} when no public URL is
     *                   configured or the fiche carries no token
     */
    record RappelVeille(String email, String prenom, LocalDate date, List<String> postes, String lienEspace)
            implements Notification {}

    /**
     * A published planning nobody acknowledged (issue #299). Sent once and
     * once only: the status moves to RELANCE, which is what stops the loop.
     */
    record RelanceConfirmation(String email, String prenom, String lienEspace) implements Notification {}

    /**
     * Swap requests left waiting for a decision (issue #300), counted rather
     * than named: the admin needs to know there is a queue and how old it is,
     * and the Échanges screen — one click away — is where the names live.
     *
     * @param nombre    how many crossed the threshold in this run
     * @param joursMax  age of the oldest of them, in days
     */
    record PendingEchanges(int nombre, long joursMax) implements Notification {}
}
