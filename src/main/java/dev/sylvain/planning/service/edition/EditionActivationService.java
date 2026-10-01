package dev.sylvain.planning.service.edition;

import dev.sylvain.planning.domain.DemandeEchange;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.domain.StatutDemandeEchange;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.espace.DemandeEchangeService;
import dev.sylvain.planning.service.espace.JourJClock;
import dev.sylvain.planning.service.mural.AffichageMuralService;
import dev.sylvain.planning.service.referentiel.AnimateurRepository;
import dev.sylvain.planning.service.referentiel.CreneauRepository;
import dev.sylvain.planning.service.solve.SolverJobService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Which edition is <b>active</b> — the one allowed to reach outside: publish,
 * send mail, open the animateur espace, the ICS feed and the wall display (ADR
 * 0072) — and the switch from one to the next.
 *
 * <p>The switch is an explicit gesture, atomic, and refused while a solve runs
 * in either of the two editions it concerns: a solve's landing in the outgoing
 * edition would otherwise fire its end-of-solve mail from an edition that has
 * just been closed, and one in the incoming edition would publish into a plan
 * still being written. It is previewed first, because what it closes is not
 * visible from the Éditions page: links already printed, swap requests left
 * open, runs still queued.</p>
 */
@ApplicationScoped
public class EditionActivationService {

    /** The swap requests still waiting on someone, which the espace's closing leaves stranded. */
    private static final Set<StatutDemandeEchange> OPEN_REQUESTS =
            Set.of(StatutDemandeEchange.EN_ATTENTE_CIBLE, StatutDemandeEchange.PROPOSEE);

    private final EditionRepository repository;

    private final EditionContext editionContext;

    private final SolverJobService solverJobs;

    private final CreneauRepository creneaux;

    private final AnimateurRepository animateurs;

    private final DemandeEchangeService echanges;

    private final AffichageMuralService mural;

    private final JourJClock clock;

    private final int reminderDays;

    @Inject
    public EditionActivationService(
            EditionRepository repository,
            EditionContext editionContext,
            SolverJobService solverJobs,
            CreneauRepository creneaux,
            AnimateurRepository animateurs,
            DemandeEchangeService echanges,
            AffichageMuralService mural,
            JourJClock clock,
            @ConfigProperty(name = "planning.editions.rappel-jours", defaultValue = "7") int reminderDays) {
        this.repository = repository;
        this.editionContext = editionContext;
        this.solverJobs = solverJobs;
        this.creneaux = creneaux;
        this.animateurs = animateurs;
        this.echanges = echanges;
        this.mural = mural;
        this.clock = clock;
        this.reminderDays = reminderDays;
    }

    /**
     * What activating {@code id} would close in the edition active today.
     *
     * @param sortante            the edition active today, {@code null} when none is
     * @param jobsEnFile          solves queued or running in either edition — a running one refuses the switch
     * @param resolutionEnCours   whether a solve runs in either edition, which refuses the switch
     * @param demandesOuvertes    swap requests of the outgoing edition still waiting on someone
     * @param liensAnimateurs     animateurs of the outgoing edition whose espace link or calendar subscription stops working
     * @param liensMuraux         wall-display links of the outgoing edition that stop working
     */
    @Schema(
            requiredProperties = {
                "jobsEnFile",
                "resolutionEnCours",
                "demandesOuvertes",
                "liensAnimateurs",
                "liensMuraux"
            })
    public record ActivationPreview(
            Edition sortante,
            int jobsEnFile,
            boolean resolutionEnCours,
            int demandesOuvertes,
            int liensAnimateurs,
            int liensMuraux) {}

    public ActivationPreview preview(String id) {
        requireExisting(id);
        Optional<String> sortanteId = editionContext.activeEditionId().filter(active -> !active.equals(id));
        Edition sortante = sortanteId.flatMap(this::find).orElse(null);
        Set<String> concernees = sortanteId.map(s -> Set.of(s, id)).orElse(Set.of(id));
        int jobs = (int) solverJobs.fileAttente().stream()
                .filter(job -> concernees.contains(job.getEditionId()))
                .count();
        boolean running = solverJobs
                .findActive()
                .filter(job -> concernees.contains(job.getEditionId()))
                .isPresent();
        if (sortanteId.isEmpty()) {
            return new ActivationPreview(null, jobs, running, 0, 0, 0);
        }
        return editionContext.executeIn(
                sortanteId.get(),
                () -> new ActivationPreview(
                        sortante,
                        jobs,
                        running,
                        (int) echanges.list().stream()
                                .map(DemandeEchange::getStatut)
                                .filter(OPEN_REQUESTS::contains)
                                .count(),
                        animateurs.countWithOutsideLinks(),
                        mural.list().size()));
    }

    /**
     * Makes {@code id} the active edition and every other one inactive, in one
     * transaction. Refused in {@code 409} while a solve runs in the outgoing or
     * the incoming edition.
     */
    public Edition activate(String id) {
        requireExisting(id);
        if (editionContext.isActive(id)) {
            // Idempotent, as the MCP tool declares itself: nothing to switch.
            return find(id).orElseThrow();
        }
        Set<String> concernees = editionContext
                .activeEditionId()
                .map(active -> Set.of(active, id))
                .orElse(Set.of(id));
        solverJobs
                .findActive()
                .filter(job -> concernees.contains(job.getEditionId()))
                .ifPresent(job -> {
                    throw new BusinessError.Conflict("Une résolution est en cours sur l'édition « "
                            + (job.getEditionNom() == null ? job.getEditionId() : job.getEditionNom())
                            + " » : attendez qu'elle se termine, ou arrêtez-la, avant de changer d'édition active.");
                });
        repository.activate(id);
        editionContext.invaliderCache();
        return find(id).orElseThrow();
    }

    /** Leaves no edition active — the state between two events, where nothing reaches outside. */
    public void deactivate(String id) {
        requireExisting(id);
        if (!editionContext.isActive(id)) {
            return;
        }
        solverJobs.findActive().filter(job -> job.getEditionId().equals(id)).ifPresent(job -> {
            throw new BusinessError.Conflict(
                    "Une résolution est en cours sur cette édition : attendez qu'elle se termine avant de la"
                            + " désactiver.");
        });
        repository.deactivate(id);
        editionContext.invaliderCache();
    }

    /** One situation the editions' state calls the organiser's attention to. */
    public enum SituationKind {
        /** The active edition's last day is over: time to deactivate it, or to activate the next one. */
        ACTIVE_TERMINEE,
        /** An inactive edition starts within the reminder window: it is probably the one to activate. */
        INACTIVE_IMMINENTE,
        /** No edition is active while one starts within the reminder window, or is under way. */
        AUCUNE_ACTIVE,
        /** An inactive edition is under way while another one is active: its espace is closed. */
        INACTIVE_EN_COURS
    }

    /**
     * @param type      what the situation is
     * @param edition   the edition it is about
     * @param premierJour its first day, {@code null} when it has no timeslot
     * @param dernierJour its last day, {@code null} when it has no timeslot
     */
    @Schema(requiredProperties = {"type", "edition"})
    public record Situation(SituationKind type, Edition edition, LocalDate premierJour, LocalDate dernierJour) {}

    /**
     * What the editions' state asks of the organiser, today (the simulated
     * clock's day): the active edition is over, an inactive one starts within
     * {@code planning.editions.rappel-jours} days, or is already under way.
     * Derived from the timeslots, like every edition date: an edition without
     * any timeslot raises nothing.
     */
    public List<Situation> situations() {
        LocalDate today = clock.today();
        boolean anyActive = editionContext.activeEditionId().isPresent();
        List<Situation> situations = new ArrayList<>();
        for (Edition edition : repository.listEditions()) {
            editionContext
                    .executeIn(edition.getId(), creneaux::dateBounds)
                    .ifPresent(bornes -> situationOf(edition, bornes, today, anyActive, reminderDays)
                            .ifPresent(type ->
                                    situations.add(new Situation(type, edition, bornes.first(), bornes.last()))));
        }
        return situations;
    }

    /** What one edition's bounds ask for today, if anything. Pure, for the tests. */
    static Optional<SituationKind> situationOf(
            Edition edition,
            CreneauRepository.DateBounds bornes,
            LocalDate today,
            boolean anyActive,
            int reminderDays) {
        if (edition.isActive()) {
            return bornes.last().isBefore(today) ? Optional.of(SituationKind.ACTIVE_TERMINEE) : Optional.empty();
        }
        if (bornes.last().isBefore(today) || bornes.first().isAfter(today.plusDays(reminderDays))) {
            return Optional.empty();
        }
        if (!anyActive) {
            return Optional.of(SituationKind.AUCUNE_ACTIVE);
        }
        return Optional.of(
                bornes.first().isAfter(today) ? SituationKind.INACTIVE_IMMINENTE : SituationKind.INACTIVE_EN_COURS);
    }

    private Optional<Edition> find(String id) {
        return repository.listEditions().stream()
                .filter(edition -> edition.getId().equals(id))
                .findFirst();
    }

    private void requireExisting(String id) {
        if (!repository.exists(id)) {
            throw new BusinessError.NotFound("Édition inconnue : " + id);
        }
    }
}
