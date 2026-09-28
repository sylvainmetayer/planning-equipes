package dev.sylvain.planning.service.espace;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.SignalementAbsence;
import dev.sylvain.planning.domain.SignalementAbsence.Motif;
import dev.sylvain.planning.domain.SignalementAbsence.Portee;
import dev.sylvain.planning.domain.SignalementAbsence.Statut;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.RateLimitVerdict;
import dev.sylvain.planning.service.espace.JourJService.AbsenceMarquee;
import dev.sylvain.planning.service.notification.Notification;
import dev.sylvain.planning.service.publication.PlanPublieService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.jboss.logging.Logger;

/**
 * « Je ne pourrai pas être là » (issue #533): the second write the espace
 * opened, after the availability declaration, and open for the whole edition —
 * the foire and the collection closed included, since that is the case it
 * exists for: once the event has started, it is how an absence reaches the
 * organisation other than by phone.
 *
 * <p><b>An observed declaration, never a write to the plan.</b> Reporting
 * changes nothing in the referential nor in the seats: the organisation reads
 * it on the day's screen and either marks the absence and repairs
 * ({@link #observe}, the Jour J's own gesture) or files it ({@link #file}).
 * Until then the animateur can withdraw it ({@link #withdraw}).</p>
 *
 * <p>Bounded like the declaration: a day of the event carrying a seat of the
 * reporter in the <b>published</b> plan — what their espace shows —, not over
 * yet; one open report per person and object (a partial unique index); and a
 * ceiling of sends per window ({@code 429} + {@code Retry-After}). The reason
 * is a closed list and optional, never free text. The organisation is told at
 * once, best effort, through {@code service/notification/}, and the animateur
 * hears the outcome the same way once it is decided.</p>
 *
 * <p><b>Switched off per edition</b> ({@link #setEnabled}), on by default: an
 * organisation that holds the plan as published does not want the gesture.
 * Off, it is gone everywhere — the espace no longer offers it and refuses a
 * send, and the reports already received are hidden from the espace and from
 * the day's screen, and can no longer be withdrawn nor settled. They stay in
 * the database: switching it back on shows them as they were.</p>
 */
@ApplicationScoped
public class SignalementAbsenceService {

    private static final Logger LOG = Logger.getLogger(SignalementAbsenceService.class);

    private final SignalementAbsenceRepository repository;

    private final DeclarationRateLimiter rateLimiter;

    private final PlanPublieService planPublieService;

    private final ReferenceDataService referenceDataService;

    private final JourJClock clock;

    private final JourJService jourJService;

    private final Event<Notification> notifications;

    private final ApplicationLinks links;

    @Inject
    public SignalementAbsenceService(
            SignalementAbsenceRepository repository,
            DeclarationRateLimiter rateLimiter,
            PlanPublieService planPublieService,
            ReferenceDataService referenceDataService,
            JourJClock clock,
            JourJService jourJService,
            Event<Notification> notifications,
            ApplicationLinks links) {
        this.repository = repository;
        this.rateLimiter = rateLimiter;
        this.planPublieService = planPublieService;
        this.referenceDataService = referenceDataService;
        this.clock = clock;
        this.jourJService = jourJService;
        this.notifications = notifications;
        this.links = links;
    }

    /** What every gesture on a report answers while the edition has them switched off. */
    static final String DISABLED = "L'organisation ne reçoit pas les signalements d'empêchement pour cette édition : "
            + "prévenez-la directement.";

    /** Whether the edition offers « Je ne pourrai pas venir »: on until the organisation switches it off. */
    public boolean enabled() {
        return repository.enabled();
    }

    /** Paramètres › Édition: offers the gesture to the espaces, or withdraws it and hides what was reported. */
    public void setEnabled(boolean enabled) {
        repository.setEnabled(enabled);
    }

    private void requireEnabled() {
        if (!repository.enabled()) {
            throw new BusinessError.Invalid(DISABLED);
        }
    }

    /** Over the ceiling of reports per window: the resource answers {@code 429} with the delay left. */
    public static class TooManyRequests extends RuntimeException {

        private final long secondsBeforeNextTry;

        TooManyRequests(long secondsBeforeNextTry) {
            super("Trop de signalements envoyés coup sur coup : réessayez dans "
                    + Math.max(1, (secondsBeforeNextTry + 59) / 60) + " minute(s).");
            this.secondsBeforeNextTry = secondsBeforeNextTry;
        }

        public long secondsBeforeNextTry() {
            return secondsBeforeNextTry;
        }
    }

    /**
     * What the espace sends.
     *
     * @param creneauId with {@code standId}, the seat — for {@link Portee#POSTE} only
     * @param motif     optional
     */
    public record NouveauSignalement(Portee portee, LocalDate date, Long creneauId, String standId, Motif motif) {}

    /**
     * One report as the espace shows it back: what, when, why, and where it
     * stands.
     */
    @Schema(requiredProperties = {"id", "portee", "date", "statut", "signaleLe"})
    public record SignalementView(
            long id,
            Portee portee,
            LocalDate date,
            Long creneauId,
            String standId,
            String standNom,
            LocalTime heureDebut,
            LocalTime heureFin,
            Motif motif,
            Statut statut,
            Instant signaleLe,
            Instant traiteLe) {}

    /* ------------------------------ Espace side ----------------------------- */

    /**
     * Records a report of {@code animateurId}.
     *
     * @throws BusinessError.Invalid   the edition has the gesture switched
     *                                 off; a day or a seat they do not hold in the
     *                                 published plan, or one already over
     * @throws BusinessError.Conflict  the same report is already open
     * @throws TooManyRequests         over the ceiling
     */
    public SignalementAbsence report(String animateurId, NouveauSignalement nouveau) {
        if (nouveau == null || nouveau.portee() == null || nouveau.date() == null) {
            throw new BusinessError.Invalid("Indiquez la journée, et le poste s'il ne s'agit que d'un créneau.");
        }
        // A gesture the edition switched off is refused before it is counted,
        // like a closed collection: a stale page tapping it must not spend the
        // quota of the first real report once it is switched back on.
        requireEnabled();
        // Before any other validation and any write, like the declaration:
        // the point is to stop a loop, not to describe its last payload.
        RateLimitVerdict verdict = rateLimiter.submitReport(animateurId);
        if (!verdict.autorise()) {
            throw new TooManyRequests(verdict.secondsBeforeNextTry());
        }
        LocalDateTime maintenant = clock.today().atTime(clock.now());
        if (nouveau.date().isBefore(maintenant.toLocalDate())) {
            throw new BusinessError.Invalid("Cette journée est passée : il n'y a plus rien à signaler.");
        }
        List<PosteAffectation> duJour = seatsOf(planPublieService.planPublie(), animateurId, nouveau.date());
        if (duJour.isEmpty()) {
            throw new BusinessError.Invalid(
                    "Vous n'avez aucun poste ce jour-là dans le planning communiqué : rien à signaler.");
        }
        PosteAffectation poste = null;
        if (nouveau.portee() == Portee.POSTE) {
            poste = duJour.stream()
                    .filter(candidat -> Objects.equals(candidat.getCreneau().getId(), nouveau.creneauId())
                            && candidat.getStand().getId().equals(nouveau.standId()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessError.Invalid(
                            "Ce poste n'est pas le vôtre dans le planning communiqué : rien à signaler."));
            if (isOver(poste, maintenant)) {
                throw new BusinessError.Invalid("Ce poste est terminé : il n'y a plus rien à signaler.");
            }
        } else if (duJour.stream().allMatch(candidat -> isOver(candidat, maintenant))) {
            throw new BusinessError.Invalid("Vos postes de la journée sont terminés : il n'y a plus rien à signaler.");
        }
        boolean journeeDejaSignalee = repository.listForAnimateur(animateurId).stream()
                .anyMatch(existant -> existant.ouvert()
                        && existant.portee() == Portee.JOUR
                        && existant.jour().equals(nouveau.date()));
        if (journeeDejaSignalee) {
            throw new BusinessError.Conflict("Vous avez déjà signalé toute cette journée : l'organisation l'a reçu.");
        }
        Long creneauId = poste == null ? null : poste.getCreneau().getId();
        String standId = poste == null ? null : poste.getStand().getId();
        long id = repository.insert(animateurId, nouveau.portee(), nouveau.date(), creneauId, standId, nouveau.motif());
        notifications.fire(new Notification.EmpechementSignale(
                nomComplet(animateurId),
                nouveau.date(),
                poste == null ? null : libellePoste(poste),
                motif(nouveau.motif())));
        return repository.byId(id).orElseThrow();
    }

    /**
     * Withdraws one of their own reports while nobody has settled it.
     *
     * @throws BusinessError.Invalid   the edition has the gesture switched off
     * @throws BusinessError.NotFound  no such report of theirs
     * @throws BusinessError.Conflict  already settled by the organisation
     */
    public void withdraw(String animateurId, long id) {
        requireEnabled();
        SignalementAbsence signalement = repository
                .byId(id)
                .filter(existant -> existant.animateurId().equals(animateurId))
                .orElseThrow(() -> new BusinessError.NotFound("Signalement inconnu : " + id));
        if (!repository.settle(signalement.id(), Statut.ANNULE, Instant.now())) {
            throw new BusinessError.Conflict(
                    "L'organisation a déjà traité ce signalement : rapprochez-vous d'elle pour le modifier.");
        }
    }

    /** Their reports, as the espace shows them back, by day; none while the edition has them switched off. */
    public List<SignalementView> ofAnimateur(String animateurId) {
        if (!repository.enabled()) {
            return List.of();
        }
        Map<String, Stand> stands = standsById();
        Map<Long, dev.sylvain.planning.domain.Creneau> creneaux = creneauxById();
        return repository.listForAnimateur(animateurId).stream()
                .map(signalement -> view(signalement, stands, creneaux))
                .toList();
    }

    /* ------------------------------- Admin side ----------------------------- */

    /**
     * Files a report without touching the plan — « l'organisation l'a vu, rien
     * à faire ». The animateur is told their planning was left as it was.
     *
     * @throws BusinessError.Invalid  the edition has the gesture switched off
     * @throws BusinessError.Conflict already settled, or withdrawn
     */
    public void file(long id) {
        requireEnabled();
        SignalementAbsence signalement = open(id);
        if (!repository.settle(signalement.id(), Statut.CLASSE, Instant.now())) {
            throw alreadySettled();
        }
        tellReporter(signalement, false);
    }

    /**
     * « Marquer absent et remplacer »: the Jour J's own gesture on what was
     * reported — the whole day, or the one timeslot of the seat — then the
     * report marked settled. The seats it frees come back for the
     * replacement.
     *
     * <p>The report is <b>claimed</b> — {@code SIGNALE} to {@code TRAITE} —
     * in the same transaction that records the absence: two admins deciding a
     * second apart, or the person withdrawing it meanwhile, leave one winner,
     * and the others a {@code 409} with nothing written. When recording is
     * refused — a contradiction, a lock, a solve holding the edition —, the
     * claim is rolled back with it and the report stays open for another
     * decision.</p>
     *
     * <p>The animateur is told once the absence is recorded, never before: a
     * refused recording leaves them expected where they were.</p>
     *
     * @throws BusinessError.Invalid  the edition has the gesture switched off
     * @throws BusinessError.Conflict already settled, or withdrawn
     */
    public AbsenceMarquee observe(long id) {
        requireEnabled();
        SignalementAbsence signalement = open(id);
        String raison =
                "signalé depuis l'espace" + (signalement.motif() == null ? "" : ", " + motif(signalement.motif()));
        Instant le = Instant.now();
        AbsenceMarquee marquee = jourJService.recordAbsence(
                signalement.animateurId(), raison, signalement.jour(), null, signalement.creneauId(), connection -> {
                    if (!repository.settle(connection, signalement.id(), Statut.TRAITE, le)) {
                        throw alreadySettled();
                    }
                });
        tellReporter(signalement, true);
        return marquee;
    }

    /* ------------------------------- Internals ------------------------------ */

    /**
     * The animateur hears what the organisation decided about their report:
     * withdrawn from what they reported ({@code accepted}), or filed with the
     * plan as it stood. Best effort, like every notification; a fiche the
     * referential no longer knows hears nothing.
     *
     * <p>Runs after the decision committed, so nothing here may undo it: a
     * failure to word the mail is logged and swallowed, the policy
     * {@code NotificationDispatcher} applies to the send itself.</p>
     */
    private void tellReporter(SignalementAbsence signalement, boolean accepted) {
        try {
            fireOutcome(signalement, accepted);
        } catch (RuntimeException e) {
            LOG.warnf(e, "The outcome of absence report %d could not be told to its animateur", signalement.id());
        }
    }

    private void fireOutcome(SignalementAbsence signalement, boolean accepted) {
        referenceDataService.listAnimateurs().stream()
                .filter(animateur -> animateur.getId().equals(signalement.animateurId()))
                .findFirst()
                .ifPresent(animateur -> {
                    String poste = seatLabel(signalement);
                    String lien =
                            links.espaceAnimateur(animateur.getAccessToken()).orElse(null);
                    notifications.fire(
                            accepted
                                    ? new Notification.AbsenceReportAccepted(
                                            animateur.getEmail(),
                                            animateur.getPrenom(),
                                            signalement.jour(),
                                            poste,
                                            lien)
                                    : new Notification.AbsenceReportFiled(
                                            animateur.getEmail(),
                                            animateur.getPrenom(),
                                            signalement.jour(),
                                            poste,
                                            lien));
                });
    }

    /** « Stand 07 09:00-12:00 » for a seat, {@code null} for a whole day. */
    private String seatLabel(SignalementAbsence signalement) {
        if (signalement.portee() != Portee.POSTE) {
            return null;
        }
        SignalementView vue = view(signalement, standsById(), creneauxById());
        String stand = vue.standNom() == null ? signalement.standId() : vue.standNom();
        if (vue.heureDebut() == null || vue.heureFin() == null) {
            return stand;
        }
        return stand + " " + vue.heureDebut() + "-" + vue.heureFin();
    }

    private SignalementAbsence open(long id) {
        SignalementAbsence signalement =
                repository.byId(id).orElseThrow(() -> new BusinessError.NotFound("Signalement inconnu : " + id));
        if (!signalement.ouvert()) {
            throw alreadySettled();
        }
        return signalement;
    }

    private static BusinessError alreadySettled() {
        return new BusinessError.Conflict("Ce signalement est déjà traité, classé ou annulé.");
    }

    private static List<PosteAffectation> seatsOf(PlanningEvenement plan, String animateurId, LocalDate jour) {
        if (plan == null || plan.getPostes() == null) {
            return List.of();
        }
        return plan.getPostes().stream()
                .filter(poste -> poste.getAnimateur() != null
                        && animateurId.equals(poste.getAnimateur().getId())
                        && poste.getCreneau() != null
                        && jour.equals(poste.getCreneau().getDate())
                        && poste.getStand() != null)
                .sorted(Comparator.comparing(PosteAffectation::heureDebutEffectif))
                .toList();
    }

    /**
     * Whether the seat's effective window ended at {@code maintenant}. Hours
     * earlier than the timeslot's own start lie past midnight of an overnight
     * timeslot, on the next day.
     */
    private static boolean isOver(PosteAffectation poste, LocalDateTime maintenant) {
        LocalDate date = poste.getCreneau().getDate();
        LocalTime debutCreneau = poste.getCreneau().getHeureDebut();
        if (debutCreneau != null && poste.heureDebutEffectif().isBefore(debutCreneau)) {
            date = date.plusDays(1);
        }
        LocalDateTime[] fenetre = TimeslotWindows.window(date, poste.heureDebutEffectif(), poste.heureFinEffectif());
        return !fenetre[1].isAfter(maintenant);
    }

    private static String libellePoste(PosteAffectation poste) {
        return poste.getStand().getNom() + " " + poste.heureDebutEffectif() + "-" + poste.heureFinEffectif();
    }

    /** The closed-list reason, worded as the organisation reads it. */
    static String motif(Motif motif) {
        if (motif == null) {
            return null;
        }
        return switch (motif) {
            case PERSONNEL -> "raison personnelle";
            case TRANSPORT -> "transport";
            case AUTRE -> "autre raison";
        };
    }

    private String nomComplet(String animateurId) {
        return referenceDataService.listAnimateurs().stream()
                .filter(animateur -> animateur.getId().equals(animateurId))
                .findFirst()
                .map(Animateur::nomAffiche)
                .orElse(animateurId);
    }

    private Map<String, Stand> standsById() {
        return referenceDataService.listStands().stream()
                .collect(Collectors.toMap(Stand::getId, Function.identity(), (gauche, droite) -> gauche));
    }

    private Map<Long, dev.sylvain.planning.domain.Creneau> creneauxById() {
        return referenceDataService.listCreneaux().stream()
                .filter(creneau -> creneau.getId() != null)
                .collect(Collectors.toMap(
                        dev.sylvain.planning.domain.Creneau::getId, Function.identity(), (gauche, droite) -> gauche));
    }

    static SignalementView view(
            SignalementAbsence signalement,
            Map<String, Stand> stands,
            Map<Long, dev.sylvain.planning.domain.Creneau> creneaux) {
        Stand stand = signalement.standId() == null ? null : stands.get(signalement.standId());
        dev.sylvain.planning.domain.Creneau creneau =
                signalement.creneauId() == null ? null : creneaux.get(signalement.creneauId());
        return new SignalementView(
                signalement.id(),
                signalement.portee(),
                signalement.jour(),
                signalement.creneauId(),
                signalement.standId(),
                stand == null ? null : stand.getNom(),
                creneau == null ? null : creneau.getHeureDebut(),
                creneau == null ? null : creneau.getHeureFin(),
                signalement.motif(),
                signalement.statut(),
                signalement.signaleLe(),
                signalement.traiteLe());
    }
}
