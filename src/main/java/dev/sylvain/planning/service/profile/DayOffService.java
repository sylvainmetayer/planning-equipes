package dev.sylvain.planning.service.profile;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PastHorizon;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.FrozenPast;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import dev.sylvain.planning.service.solve.PlanningService;
import dev.sylvain.planning.service.solve.SolverJobService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * « Indisponible ce jour » in one gesture, from the fiche of one animateur:
 * the day written among their off days and, when the persisted plan seats
 * them on that day, those seats emptied in the same call — so the fiche can
 * offer « Qui peut tenir ce siège ? » on each of them straight away.
 *
 * <p>Always the off day of the referential, before the event as during it:
 * it is the notion the solver, the collection of availabilities and every
 * screen already read. The Jour J screen keeps its own gesture — a forced
 * unavailability from « now » to the end of the day — for the absence that
 * starts in the middle of a shift.</p>
 *
 * <p>The day is always written; three rules decide which seats of it move
 * (ADR 0056):</p>
 * <ul>
 *   <li>a seat of a timeslot already started is kept (ADR 0044): the past is
 *       not rewritten, the answer only counts it;</li>
 *   <li>a seat held under a lock is kept too, and named in the answer: a lock
 *       is somebody saying « this one does not move », and only whoever lifts
 *       it decides otherwise — until then the plan seats a person on a day
 *       they are off, which the fiche says next to the way to the locks;</li>
 *   <li>refused while a solve holds the edition: its landing rewrites the off
 *       days and the seats it was given.</li>
 * </ul>
 *
 * <p>Undoing it makes the day available again and hands no seat back: who
 * holds a seat is a decision, and the replacement placed in between would be
 * silently undone.</p>
 */
@ApplicationScoped
public class DayOffService {

    private final ReferenceDataService referenceDataService;

    private final PlanningPersistenceService persistenceService;

    private final PlanningService planningService;

    private final SolverJobService solverJobs;

    @Inject
    public DayOffService(
            ReferenceDataService referenceDataService,
            PlanningPersistenceService persistenceService,
            PlanningService planningService,
            SolverJobService solverJobs) {
        this.referenceDataService = referenceDataService;
        this.persistenceService = persistenceService;
        this.planningService = planningService;
        this.solverJobs = solverJobs;
    }

    /**
     * Writes {@code date} among the animateur's off days and frees the seats
     * they hold on it that have neither started yet nor sit under a lock.
     *
     * @throws BusinessError.NotFound for an animateur the edition does not hold
     */
    public DayOff markDayOff(String animateurId, LocalDate date) {
        Animateur animateur = find(animateurId);
        solverJobs.refuseIfSolving();

        PlanningEvenement plan = persistenceService.loadPersistedPlanning();
        List<PosteAffectation> duJour = seatsOn(plan, animateurId, date);
        PastHorizon horizon = planningService.pastHorizon();
        List<PosteAffectation> aVenir = duJour.stream()
                .filter(poste -> !FrozenPast.isPast(poste, horizon))
                .toList();
        List<VerrouillagePlanning> verrouillages = referenceDataService.listVerrouillages();
        List<PosteAffectation> verrouilles = aVenir.stream()
                .filter(poste -> verrouillages.stream().anyMatch(verrou -> verrou.couvre(poste)))
                .toList();
        List<PosteAffectation> aLiberer =
                aVenir.stream().filter(poste -> !verrouilles.contains(poste)).toList();

        Set<LocalDate> jours = new HashSet<>(animateur.getJoursIndisponibles());
        if (jours.add(date)) {
            animateur.setJoursIndisponibles(jours);
            referenceDataService.writeAnimateur(animateurId, animateur);
        }
        if (!aLiberer.isEmpty()) {
            planningService.applyReparations(
                    plan, aLiberer.stream().map(PosteAffectation::getId).toList(), null);
        }
        return new DayOff(
                date,
                true,
                aLiberer.stream().map(DayOffService::seat).toList(),
                verrouilles.stream().map(DayOffService::seat).toList(),
                duJour.size() - aVenir.size());
    }

    /**
     * Makes {@code date} available again. Nothing else moves: the seats freed
     * when the day was marked stay with whoever holds them now.
     *
     * @throws BusinessError.NotFound for an animateur the edition does not hold
     */
    public DayOff cancelDayOff(String animateurId, LocalDate date) {
        Animateur animateur = find(animateurId);
        solverJobs.refuseIfSolving();
        Set<LocalDate> jours = new HashSet<>(animateur.getJoursIndisponibles());
        if (jours.remove(date)) {
            animateur.setJoursIndisponibles(jours);
            referenceDataService.writeAnimateur(animateurId, animateur);
        }
        return new DayOff(date, false, List.of(), List.of(), 0);
    }

    private Animateur find(String animateurId) {
        return referenceDataService.listAnimateurs().stream()
                .filter(candidat -> Objects.equals(candidat.getId(), animateurId))
                .findFirst()
                .orElseThrow(() -> new BusinessError.NotFound("Animateur inconnu : " + animateurId));
    }

    private static List<PosteAffectation> seatsOn(PlanningEvenement plan, String animateurId, LocalDate date) {
        List<PosteAffectation> postes = plan.getPostes() == null ? List.of() : plan.getPostes();
        return postes.stream()
                .filter(poste -> poste.getAnimateur() != null
                        && animateurId.equals(poste.getAnimateur().getId()))
                .filter(poste -> poste.getCreneau() != null
                        && date.equals(poste.getCreneau().getDate()))
                .sorted(Comparator.comparing(
                                PosteAffectation::heureDebutEffectif, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(PosteAffectation::getId))
                .toList();
    }

    private static DaySeat seat(PosteAffectation poste) {
        Stand stand = poste.getStand();
        Creneau creneau = poste.getCreneau();
        return new DaySeat(
                poste.getId(),
                stand == null ? null : stand.getId(),
                stand == null ? null : stand.getNom(),
                creneau.getId() == null ? 0L : creneau.getId(),
                creneau.getDate(),
                poste.heureDebutEffectif(),
                poste.heureFinEffectif());
    }

    /**
     * What one gesture on the availability strip did.
     *
     * @param date             the day named
     * @param unavailable      the state the day is left in
     * @param freedSeats       the seats emptied by marking the day, in time
     *                         order — each one a « Qui peut tenir ce siège ? »
     * @param lockedSeatsKept  the seats of that day not started yet but held
     *                         under a lock, left in place, in time order
     * @param startedSeatsKept the seats of that day already started, kept
     *                         as they are (ADR 0044)
     */
    @Schema(requiredProperties = {"date", "unavailable", "freedSeats", "lockedSeatsKept", "startedSeatsKept"})
    public record DayOff(
            LocalDate date,
            boolean unavailable,
            List<DaySeat> freedSeats,
            List<DaySeat> lockedSeatsKept,
            int startedSeatsKept) {}

    /** One seat of the day named: enough to name it and to open it in the Siège panel. */
    @Schema(requiredProperties = {"posteId", "creneauId", "date", "heureDebut", "heureFin"})
    public record DaySeat(
            String posteId,
            String standId,
            String standNom,
            long creneauId,
            LocalDate date,
            LocalTime heureDebut,
            LocalTime heureFin) {}
}
