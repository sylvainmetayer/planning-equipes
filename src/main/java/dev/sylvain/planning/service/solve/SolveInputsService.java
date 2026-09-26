package dev.sylvain.planning.service.solve;

import dev.sylvain.planning.domain.StatutDeclaration;
import dev.sylvain.planning.service.consigne.ConsigneService;
import dev.sylvain.planning.service.espace.DeclarationDisponibiliteService;
import dev.sylvain.planning.service.journal.JournalActionService;
import dev.sylvain.planning.service.referentiel.ContrainteAdHocService;
import dev.sylvain.planning.service.referentiel.ParametresService;
import dev.sylvain.planning.service.referentiel.VerrouillageService;
import dev.sylvain.planning.solver.ConstraintCatalog;
import dev.sylvain.planning.solver.ConstraintCatalog.ConstraintDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What the next solve will take into account, counted in one read (issues
 * #704, #719): the Solveur says « ce calcul tiendra compte de » before the
 * click rather than leaving the reader to visit five screens.
 *
 * <p>Every figure is read from the edition as it is stored — the same rows
 * the problem is built from — and none is the solver's: the locks, the hand
 * written adjustments, the consignes and their dates, the declarations of
 * availability still waiting for a decision (a solve reads the fiches, not
 * the declarations, so these are what it will <em>not</em> see), the rules
 * this edition switched off, and how many changes of the referential the
 * persisted plan predates.</p>
 */
@ApplicationScoped
public class SolveInputsService {

    private final VerrouillageService locks;
    private final ContrainteAdHocService adjustments;
    private final ConsigneService consignes;
    private final DeclarationDisponibiliteService declarations;
    private final ParametresService parametres;
    private final JournalActionService journal;
    private final PlanningPersistenceService persistence;

    @Inject
    public SolveInputsService(
            VerrouillageService locks,
            ContrainteAdHocService adjustments,
            ConsigneService consignes,
            DeclarationDisponibiliteService declarations,
            ParametresService parametres,
            JournalActionService journal,
            PlanningPersistenceService persistence) {
        this.locks = locks;
        this.adjustments = adjustments;
        this.consignes = consignes;
        this.declarations = declarations;
        this.parametres = parametres;
        this.journal = journal;
        this.persistence = persistence;
    }

    /**
     * One consigne as the summary names it: its date and its motif — the
     * words the organiser gave it, printed wherever the day is.
     */
    @Schema(requiredProperties = {"date", "motif"})
    public record ConsigneInput(LocalDate date, String motif) {}

    /**
     * The inputs of the next solve on this edition.
     *
     * @param locks               locks laid on the plan
     * @param adjustments         hand-written ad hoc constraints, covoiturages validated included
     * @param consignes           the dated consignes, chronologically
     * @param pendingDeclarations declarations of availability awaiting a decision
     * @param disabledRules       rules this edition switched off among those the catalogue ships on
     * @param changesSinceSolve   changes of the referential journalled since the persisted plan
     *                            was written; 0 without a plan
     * @param solvedAt            when that plan was written, {@code null} without one
     */
    @Schema(
            requiredProperties = {
                "locks",
                "adjustments",
                "consignes",
                "pendingDeclarations",
                "disabledRules",
                "changesSinceSolve"
            })
    public record SolveInputs(
            int locks,
            int adjustments,
            List<ConsigneInput> consignes,
            int pendingDeclarations,
            int disabledRules,
            int changesSinceSolve,
            Instant solvedAt) {}

    public SolveInputs current() {
        List<ConsigneInput> dated = consignes.byDate().values().stream()
                .map(consigne -> new ConsigneInput(consigne.date(), consigne.motif()))
                .sorted(Comparator.comparing(ConsigneInput::date))
                .toList();
        int pending = (int) declarations.list().stream()
                .filter(declaration -> declaration.getStatut() == StatutDeclaration.EN_ATTENTE)
                .count();
        int disabled = (int) ConstraintCatalog.definitions().stream()
                .filter(ConstraintDefinition::activeByDefault)
                .filter(definition -> !parametres.effectiveActive(definition.name()))
                .count();
        PlanningPersistenceService.PlanningResolution resolution = persistence.loadResolution();
        Instant solvedAt = resolution == null ? null : resolution.resoluLe();
        int changes = solvedAt == null ? 0 : journal.changesSince(solvedAt, 0).total();
        return new SolveInputs(
                locks.list().size(), adjustments.list().size(), dated, pending, disabled, changes, solvedAt);
    }
}
