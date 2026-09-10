package dev.sylvain.planning.service.espace;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.DeclarationDisponibilite;
import dev.sylvain.planning.domain.StatutDeclaration;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.espace.DeclarationDisponibiliteRepository.FenetreCollecte;
import dev.sylvain.planning.service.notification.Notification;
import dev.sylvain.planning.service.publication.MailService;
import dev.sylvain.planning.service.referentiel.AnimateurService;
import dev.sylvain.planning.service.referentiel.JoursEvenement;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.referentiel.TypologieService;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The self-service declaration lifecycle (issue #291): an animateur proposes,
 * from their espace, the days they cannot come and the game categories they
 * would like — and the admin applies or refuses the proposal <b>as a whole</b>.
 *
 * <p>Three rules hold the whole feature, and each one is enforced here rather
 * than merely shown by the interface.</p>
 *
 * <ol>
 *   <li><b>Nothing is written outside the collection window.</b> Modelled on
 *       {@link DemandeEchangeService#isFoireOpen()}, with one deliberate
 *       difference: the window is <b>closed</b> while nobody has decided
 *       anything. The foire could stay open by default because it predates the
 *       switch; this route writes from the public Internet, and such a route
 *       does not open by omission.</li>
 *   <li><b>A new declaration replaces the pending one.</b> One proposal in
 *       flight per animateur, always the latest, so the admin never arbitrates
 *       two contradictory versions of the same person — and so a token holder
 *       can never grow the database beyond one row, whatever they resend.</li>
 *   <li><b>Nothing reaches the referential before an explicit application.</b>
 *       Applying writes through {@link AnimateurService}, exactly as the CRUD
 *       screen does, which is also what makes {@code dataStale} fire: a
 *       declaration that was applied really did change the solver's input.</li>
 * </ol>
 */
@ApplicationScoped
public class DeclarationDisponibiliteService {

    /**
     * The single wording of a closed-window refusal — the espace hides the
     * form, and this is what somebody bypassing the interface reads.
     */
    public static final String COLLECTE_FERMEE =
            "La collecte des disponibilités est fermée : votre déclaration ne peut plus être envoyée";

    /**
     * How many days and wishes one declaration may carry. Not a business
     * limit — an event has tens of days and a referential tens of categories —
     * but the ceiling that keeps a hand-crafted payload from storing a novel
     * in a row anybody holding a token can write.
     */
    static final int MAX_VALEURS = 400;

    /** Same reasoning for the free word: a sentence to the organisation, not a file. */
    static final int MAX_COMMENTAIRE = 2000;

    @Inject
    DeclarationDisponibiliteRepository repository;

    @Inject
    DeclarationRateLimiter rateLimiter;

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    AnimateurService animateurService;

    @Inject
    TypologieService typologieService;

    @Inject
    MailService mailService;

    @Inject
    ApplicationLinks liens;

    /**
     * Fired as a fact, never sent from here: the best-effort delivery policy
     * lives in {@code NotificationDispatcher}, so a broken SMTP server cannot
     * roll back a declaration that was really submitted.
     */
    @Inject
    Event<Notification> notifications;

    /**
     * Too many declarations in the window (see {@link DeclarationRateLimiter}).
     * Not a {@link BusinessError}: that hierarchy maps to 400/404/409, and a
     * caller told to slow down needs a 429 and a {@code Retry-After} — the same
     * shape {@code EspaceAccesService.TooManyRequests} already carries.
     */
    public static class TooManyRequests extends RuntimeException {

        private final long secondsBeforeNextTry;

        TooManyRequests(long secondsBeforeNextTry) {
            super("Trop de déclarations envoyées coup sur coup : réessayez dans "
                    + Math.max(1, (secondsBeforeNextTry + 59) / 60) + " minute(s).");
            this.secondsBeforeNextTry = secondsBeforeNextTry;
        }

        public long secondsBeforeNextTry() {
            return secondsBeforeNextTry;
        }
    }

    /** One declaration as typed in the espace, before any validation. */
    public record NouvelleDeclaration(List<LocalDate> joursIndisponibles, List<String> souhaits, String commentaire) {}

    /* --------------------------- Collection window --------------------------- */

    /** The window as the admin configured it, closed while nobody has. */
    public FenetreCollecte fenetre() {
        return repository.fenetre();
    }

    /** True when animateurs may submit a declaration right now. */
    public boolean isCollecteOuverte() {
        return repository.fenetre().openOn(LocalDate.now());
    }

    /**
     * What one configuration call did: the window as it now stands, and the
     * invitation report when one was asked for ({@code null} otherwise).
     */
    public record ConfigurationAppliquee(FenetreCollecte fenetre, InvitationReport invitation) {}

    /**
     * Admin decision: opens or closes the collection window for the current
     * edition, and — only when {@code prevenirAnimateurs} — mails every
     * animateur the link to their espace. Dates are optional bounds; the
     * boolean stays the master switch, so a dated but closed window collects
     * nothing.
     *
     * <p><b>Everything that can refuse the call refuses before anything is
     * written.</b> The two steps used to be sequential in the resource, and a
     * deployment with no {@code planning.public-url} — a supported one, the
     * property is {@code Optional} — made the second throw after the first had
     * committed: the admin read « Erreur » on a collection that was, in fact,
     * open and already taking declarations. There is no transaction spanning a
     * database write and a batch of mails, so the guarantee has to be that
     * nothing refusable is left after the write. What can still fail
     * afterwards is per-animateur, and that is reported rather than
     * thrown.</p>
     */
    public ConfigurationAppliquee configure(FenetreCollecte fenetre, boolean prevenirAnimateurs) {
        FenetreCollecte demandee = fenetre == null ? FenetreCollecte.closed() : fenetre;
        if (demandee.debut() != null
                && demandee.fin() != null
                && demandee.debut().isAfter(demandee.fin())) {
            throw new BusinessError.Invalid("La fin de la collecte précède son début");
        }
        // Nobody is invited to a window being closed, so the precondition only
        // bites when an invitation is really about to leave.
        boolean invitera = prevenirAnimateurs && demandee.ouverte();
        if (invitera && !liens.disponible()) {
            throw new BusinessError.Invalid(
                    "Aucune URL publique n'est configurée : l'invitation ne peut pas porter de lien "
                            + "d'espace. Ouvrez la collecte sans cocher l'envoi, ou renseignez PUBLIC_URL.");
        }
        repository.saveFenetre(demandee);
        FenetreCollecte enregistree = repository.fenetre();
        return new ConfigurationAppliquee(enregistree, invitera ? invite() : null);
    }

    /**
     * Outcome of the invitation mails, in the shape
     * {@link PlanningDeliveryService.DeliveryReport} already uses: names ready
     * to be shown to the admin as-is.
     */
    @Schema(requiredProperties = {"envoyes"})
    public record InvitationReport(int envoyes, List<String> sansEmail, List<String> echecs) {}

    /**
     * Mails every animateur their own espace link, inviting them to declare.
     *
     * <p>Deliberately <b>on demand</b> rather than automatic on opening: the
     * invitation is indispensable on the first round and merely tiresome when
     * the window is reopened after a correction, so the admin ticks the box
     * when they mean it. An explicit admin send, so it goes through
     * {@link MailService} and the report says who could not be reached —
     * a failure per animateur never aborts the others.</p>
     *
     * <p>Called by {@link #configure} once the window is saved, and only after
     * it has checked that a public URL exists at all: what is left to fail here
     * is per-animateur, never the whole batch.</p>
     */
    InvitationReport invite() {
        FenetreCollecte fenetre = repository.fenetre();
        int envoyes = 0;
        List<String> sansEmail = new ArrayList<>();
        List<String> echecs = new ArrayList<>();
        for (Animateur animateur : referenceDataService.listAnimateurs()) {
            if (animateur.getEmail() == null || animateur.getEmail().isBlank()) {
                sansEmail.add(animateur.nomAffiche());
                continue;
            }
            Optional<String> lien = liens.espaceDisponibilites(animateur.getAccessToken());
            if (lien.isEmpty()) {
                // NOT « sans adresse » — theirs is right there. Reported with
                // its real cause: an operator told « no address » adds an
                // address that already exists and the invitation still does not
                // leave. The fix is « Régénérer le lien » on their fiche.
                echecs.add(animateur.nomAffiche() + " (aucun lien d'espace : jeton d'accès manquant)");
                continue;
            }
            try {
                mailService.sendInvitationDeclaration(
                        animateur.getEmail(), animateur.getPrenom(), lien.get(), fenetre.debut(), fenetre.fin());
                envoyes++;
            } catch (RuntimeException e) {
                // The address is what the operator needs to act; the name is
                // enough for the report and nothing nominative is logged.
                Log.errorf(e, "Failed to mail the declaration invitation to animateur %s", animateur.getId());
                echecs.add(animateur.nomAffiche() + " (échec de l'envoi)");
            }
        }
        return new InvitationReport(envoyes, sansEmail, echecs);
    }

    /* ------------------------------- Animateur ------------------------------- */

    /**
     * Records what {@code animateurId} declares, replacing whatever they had
     * pending. Nothing of it reaches their fiche: the proposal waits for an
     * admin decision.
     *
     * @throws BusinessError.Invalid when the window is closed, or when the
     *         payload names a day outside the event or a game category the
     *         referential does not hold
     */
    public DeclarationDisponibilite submit(String animateurId, NouvelleDeclaration nouvelle) {
        if (!isCollecteOuverte()) {
            throw new BusinessError.Invalid(COLLECTE_FERMEE);
        }
        // Before any validation and any write: the point is to stop the loop,
        // not to describe what the last payload of it got wrong.
        RateLimitVerdict verdict = rateLimiter.submit(animateurId);
        if (!verdict.autorise()) {
            throw new TooManyRequests(verdict.secondsBeforeNextTry());
        }
        NouvelleDeclaration declaree =
                nouvelle == null ? new NouvelleDeclaration(List.of(), List.of(), null) : nouvelle;
        List<LocalDate> jours = checkDays(declaree.joursIndisponibles());
        List<String> souhaits = checkWishes(declaree.souhaits());
        String commentaire = checkComment(declaree.commentaire());

        DeclarationDisponibilite declaration = new DeclarationDisponibilite();
        declaration.setId(UUID.randomUUID().toString());
        declaration.setAnimateurId(animateurId);
        declaration.setJoursIndisponibles(jours);
        declaration.setSouhaits(souhaits);
        declaration.setCommentaire(commentaire);
        declaration.setStatut(StatutDeclaration.EN_ATTENTE);
        declaration.setCreeLe(Instant.now());

        boolean remplacement = repository.pendingOf(animateurId).isPresent();
        repository.replacePending(declaration);

        // One mail per proposal REACHING the desk, not per submission: a
        // correction sent five minutes later replaces a proposal the admin has
        // not treated yet, and telling them five times about one pending item
        // is how a notification stops being read.
        if (!remplacement) {
            notifications.fire(
                    new Notification.DeclarationSoumise(nomComplet(animateurId), jours.size(), souhaits.size()));
        }
        return declaration;
    }

    /** The declarations of one animateur, most recent first — what their espace shows back. */
    public List<DeclarationDisponibilite> listForAnimateur(String animateurId) {
        return repository.listForAnimateur(animateurId);
    }

    /* --------------------------------- Admin --------------------------------- */

    /** Every declaration of the edition, most recent first, whatever their statut. */
    public List<DeclarationDisponibilite> list() {
        return repository.list();
    }

    /**
     * Applies one pending declaration to the animateur's fiche, <b>whole</b>:
     * the days and the wishes it carries become theirs, replacing what the
     * referential said. The admin accepts or refuses the proposal, never one
     * line of it — a disagreement is settled outside the application, and the
     * animateur sends a corrected version.
     *
     * <p>Writes through {@link AnimateurService#update}, exactly as the CRUD
     * screen does, so the change marks the reference data as modified and
     * {@code dataStale} fires: an applied declaration really did move the
     * solver's input.</p>
     *
     * <p><b>The decision is claimed before the fiche is written</b>, and that
     * order is the whole safety of the method. Written the other way round, an
     * admin refusing the declaration while another applies it left the second
     * one overwriting somebody's days and wishes and <i>then</i> being told the
     * row had moved: the referential had changed for a declaration recorded as
     * REFUSEE, and nothing said so. Claiming first turns that race into a plain
     * 409 with the fiche untouched. The residual window is the opposite one — a
     * claimed decision whose write then fails — and it is the better failure:
     * it surfaces as a 500 the admin sees, on a declaration they can read.</p>
     */
    public DeclarationDisponibilite apply(String id) {
        DeclarationDisponibilite declaration = pendingOrFail(id);
        Animateur animateur = referenceDataService.listAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals(declaration.getAnimateurId()))
                .findFirst()
                .orElseThrow(() -> new BusinessError.Invalid(
                        "L'animateur de cette déclaration n'existe plus : " + declaration.getAnimateurId()));
        // Fails loudly when a game category was dropped between the submission
        // and the decision: the stored proposal is a snapshot, and silently
        // dropping half of what somebody declared would be worse than a refusal
        // the admin can act on. Checked before claiming, so a proposal nobody
        // can apply stays pending rather than being burnt by the attempt.
        typologieService.validateIds(new LinkedHashSet<>(declaration.getSouhaits()));

        DeclarationDisponibilite decidee = decider(declaration, StatutDeclaration.APPLIQUEE, null);

        animateur.setJoursIndisponibles(new TreeSet<>(declaration.getJoursIndisponibles()));
        animateur.setSouhaits(new LinkedHashSet<>(declaration.getSouhaits()));
        animateurService.update(animateur.getId(), animateur);
        return decidee;
    }

    /**
     * Refuses one pending declaration. The referential is untouched; the
     * comment is what the admin tells the animateur, who can send a corrected
     * version while the window is open.
     */
    public DeclarationDisponibilite refuse(String id, String commentaire) {
        return decider(pendingOrFail(id), StatutDeclaration.REFUSEE, commentaire);
    }

    private DeclarationDisponibilite decider(
            DeclarationDisponibilite declaration, StatutDeclaration statut, String commentaire) {
        Instant decideLe = Instant.now();
        int lignes = repository.decide(declaration.getId(), statut, commentaire, Timestamp.from(decideLe));
        if (lignes == 0) {
            // Somebody else decided it between the read and the write.
            throw new BusinessError.Conflict("Cette déclaration a déjà été traitée");
        }
        declaration.setStatut(statut);
        declaration.setCommentaireAdmin(commentaire);
        declaration.setDecideLe(decideLe);
        return declaration;
    }

    private DeclarationDisponibilite pendingOrFail(String id) {
        DeclarationDisponibilite declaration =
                repository.byId(id).orElseThrow(() -> new BusinessError.NotFound("Déclaration inconnue : " + id));
        if (declaration.getStatut() != StatutDeclaration.EN_ATTENTE) {
            throw new BusinessError.Invalid("Cette déclaration a déjà été traitée");
        }
        return declaration;
    }

    /* ------------------------------ Validation ------------------------------ */

    /**
     * The days of the event, as the créneaux draw them — the only days a
     * declaration may name. An edition with no créneau yet accepts any day:
     * collecting availability before the grid exists is exactly when this
     * feature is most useful.
     *
     * <p>Derived by {@link JoursEvenement}, which the write-time warnings read
     * too: the span of an edition is computed once, not once per feature.</p>
     */
    public List<LocalDate> joursEvenement() {
        return JoursEvenement.of(referenceDataService.listCreneaux()).jours();
    }

    private List<LocalDate> checkDays(List<LocalDate> jours) {
        if (jours == null || jours.isEmpty()) {
            return List.of();
        }
        if (jours.size() > MAX_VALEURS) {
            throw new BusinessError.Invalid("Trop de jours déclarés");
        }
        Set<LocalDate> connus = new LinkedHashSet<>(joursEvenement());
        List<LocalDate> retenus = jours.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (!connus.isEmpty()) {
            LocalDate horsEvenement = retenus.stream()
                    .filter(jour -> !connus.contains(jour))
                    .findFirst()
                    .orElse(null);
            if (horsEvenement != null) {
                throw new BusinessError.Invalid("Ce jour ne fait pas partie de l'événement : " + horsEvenement);
            }
        }
        return retenus;
    }

    private List<String> checkWishes(List<String> souhaits) {
        if (souhaits == null || souhaits.isEmpty()) {
            return List.of();
        }
        if (souhaits.size() > MAX_VALEURS) {
            throw new BusinessError.Invalid("Trop de souhaits déclarés");
        }
        List<String> retenus = souhaits.stream()
                .filter(souhait -> souhait != null && !souhait.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        // Same referential check the CRUD applies — an unknown id is one
        // message, written once in TypologieService.
        typologieService.validateIds(new LinkedHashSet<>(retenus));
        return retenus;
    }

    private static String checkComment(String commentaire) {
        if (commentaire == null || commentaire.isBlank()) {
            return null;
        }
        String propre = commentaire.trim();
        if (propre.length() > MAX_COMMENTAIRE) {
            throw new BusinessError.Invalid("Le commentaire est trop long");
        }
        return propre;
    }

    /** Display name of an animateur, falling back to their id when the fiche is gone. */
    String nomComplet(String animateurId) {
        return referenceDataService.listAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals(animateurId))
                .findFirst()
                .map(Animateur::nomAffiche)
                .orElse(animateurId);
    }
}
