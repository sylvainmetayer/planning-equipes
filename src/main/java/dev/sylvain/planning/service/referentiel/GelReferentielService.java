package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.domain.EditionPhase;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.edition.EditionPhaseService;
import dev.sylvain.planning.service.journal.ChampsModifies;
import dev.sylvain.planning.service.journal.CurrentAction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The freeze of the referential, which the phase of the edition decides
 * (ADR 0115, replacing the per-family freeze of ADR 0052), and the refusal
 * every write of a frozen family then meets, whatever path it takes — a form,
 * the bulk edit, a CSV or grid import, an MCP tool.
 *
 * <p>What each phase freezes:</p>
 * <ul>
 *   <li>{@link EditionPhase#PREPARATION} — nothing;</li>
 *   <li>{@link EditionPhase#CALCUL} and {@link EditionPhase#TERMINEE} — the
 *       four families;</li>
 *   <li>{@link EditionPhase#PUBLIEE} — the structure ({@link #STRUCTURE}:
 *       stands, timeslots, game categories and locations), unless the
 *       organiser unblocked it for a last-minute change; the animateurs'
 *       competences stay open.</li>
 * </ul>
 *
 * <p>Most write methods are guarded by {@link RefusedWhileFrozen}: the whole
 * method writes the family, so the interceptor refuses it before it starts.
 * A few write <em>part</em> of a family — a stand renamed is not a stand
 * whose headcount moved — and call {@link #refuseIfFrozen} themselves once
 * they have compared the fiche with its before-image, through the helpers
 * below. {@code GelReferentielStructuralTest} holds both halves: no write
 * method of a guarded service without one or the other, or an argued
 * reason.</p>
 *
 * <p>What stays open in every phase is deliberate, not forgotten: the
 * people's own data (availability, wishes, declarations, e-mail), the ad hoc
 * adjustments, the planning locks, and the consignes — ADR 0043's tool for
 * closing a band late without destroying anything.</p>
 */
@ApplicationScoped
public class GelReferentielService {

    /**
     * The stand fields the {@link ReferentialFamily#STANDS} freeze covers, as
     * {@link ChampsModifies#surStand} names them. The name, the premium flag,
     * the effort level and the location a stand sits on stay editable: none
     * of them changes which seats a solve is given.
     */
    static final Set<String> STAND_FIELDS =
            Set.of("typologiesProposees", "effectifMin", "effectifMax", "reserveMajeurs", "plages", "horaires");

    /** The structure of the event: what a published edition keeps frozen unless unblocked. */
    static final Set<ReferentialFamily> STRUCTURE = Collections.unmodifiableSet(EnumSet.of(
            ReferentialFamily.STANDS, ReferentialFamily.CRENEAUX, ReferentialFamily.TYPOLOGIES_EMPLACEMENTS));

    private final EditionPhaseService phases;

    /** Where the family named by a 3.x freeze or lift is left for the history line, which names it. */
    private final CurrentAction currentAction;

    @Inject
    public GelReferentielService(EditionPhaseService phases, CurrentAction currentAction) {
        this.phases = phases;
        this.currentAction = currentAction;
    }

    /** The families {@code phase} freezes — see the class comment. */
    static Set<ReferentialFamily> frozenFamilies(EditionPhase phase, boolean structureDebloquee) {
        return switch (phase) {
            case PREPARATION -> EnumSet.noneOf(ReferentialFamily.class);
            case CALCUL, TERMINEE -> EnumSet.allOf(ReferentialFamily.class);
            case PUBLIEE -> structureDebloquee ? EnumSet.noneOf(ReferentialFamily.class) : EnumSet.copyOf(STRUCTURE);
        };
    }

    private static Set<ReferentialFamily> frozenFamilies(Edition edition) {
        return frozenFamilies(edition.getPhase(), edition.isStructureDebloquee());
    }

    /**
     * One family and whether it is frozen — the line the État de l'édition
     * card, the Paramètres switches and {@code etat_edition} all read.
     *
     * @param libelle how the screens name the family
     * @param figeLe  since when the phase freezing it holds, {@code null} while it is open
     */
    @Schema(requiredProperties = {"famille", "fige", "libelle"})
    public record EtatGel(ReferentialFamily famille, String libelle, boolean fige, Instant figeLe) {

        static EtatGel of(ReferentialFamily famille, Edition edition) {
            boolean fige = frozenFamilies(edition).contains(famille);
            return new EtatGel(famille, famille.libelle(), fige, fige ? edition.getPhaseDepuis() : null);
        }
    }

    /** Every family, frozen or not, in declaration order. */
    public List<EtatGel> etat() {
        Edition edition = phases.current();
        return Arrays.stream(ReferentialFamily.values())
                .map(famille -> EtatGel.of(famille, edition))
                .toList();
    }

    /** One family, frozen or not. */
    public EtatGel etat(ReferentialFamily famille) {
        return EtatGel.of(famille, phases.current());
    }

    public boolean isFrozen(ReferentialFamily famille) {
        return frozenFamilies(phases.current()).contains(famille);
    }

    /**
     * The 3.x « freeze this family », kept until 5.0 (ADR 0115): in
     * {@link EditionPhase#PREPARATION} it moves the edition to
     * {@link EditionPhase#CALCUL} — which freezes the four families, not the
     * one named; in a published edition whose structure was unblocked, it
     * refreezes the structure. Anywhere else the family is already as frozen
     * as the phase allows, and nothing moves.
     */
    public void freeze(ReferentialFamily famille) {
        Objects.requireNonNull(famille, "famille");
        Edition edition = phases.current();
        if (edition.getPhase() == EditionPhase.PREPARATION) {
            phases.setPhase(EditionPhase.CALCUL);
        } else if (edition.getPhase() == EditionPhase.PUBLIEE
                && edition.isStructureDebloquee()
                && STRUCTURE.contains(famille)) {
            phases.refreezeStructure();
        }
        currentAction.champsModifies(List.of(famille.name()));
    }

    /**
     * The 3.x « lift this family », kept until 5.0 (ADR 0115): in
     * {@link EditionPhase#CALCUL} it moves the edition back to
     * {@link EditionPhase#PREPARATION} — which opens the four families; in a
     * published edition it unblocks the structure. A finished edition refuses
     * it: its phase is what to change.
     */
    public void lift(ReferentialFamily famille) {
        Objects.requireNonNull(famille, "famille");
        Edition edition = phases.current();
        switch (edition.getPhase()) {
            case CALCUL -> phases.setPhase(EditionPhase.PREPARATION);
            case PUBLIEE -> {
                if (!edition.isStructureDebloquee() && STRUCTURE.contains(famille)) {
                    phases.unblockStructure();
                }
            }
            case TERMINEE ->
                throw new BusinessError.Conflict(
                        "L'édition est terminée : changez sa phase pour modifier son" + " référentiel.");
            case PREPARATION -> {
                // Nothing is frozen: lifting an open family changes nothing.
            }
        }
        currentAction.champsModifies(List.of(famille.name()));
    }

    /**
     * Refuses when any of {@code families} is frozen — every family when none
     * is named, which is what an operation replacing the whole edition asks
     * (a scenario import, the reset). The refusal names each frozen family
     * and says how to reopen it.
     */
    public void refuseIfFrozen(ReferentialFamily... families) {
        Set<ReferentialFamily> asked =
                families.length == 0 ? EnumSet.allOf(ReferentialFamily.class) : EnumSet.copyOf(Arrays.asList(families));
        Edition edition = phases.current();
        Set<ReferentialFamily> frozen = EnumSet.noneOf(ReferentialFamily.class);
        frozen.addAll(frozenFamilies(edition));
        frozen.retainAll(asked);
        if (!frozen.isEmpty()) {
            throw refusal(edition.getPhase(), frozen);
        }
    }

    /**
     * Refuses when {@code family} is frozen and the edit moves what it covers:
     * the partial families' guard. The phase is read once, and
     * {@code movesWhatItCovers} — which may need a before-image — is asked
     * only when the family is frozen, so an open edition pays one small read
     * per write and nothing more.
     */
    public void refuseIfFrozen(ReferentialFamily family, BooleanSupplier movesWhatItCovers) {
        Edition edition = phases.current();
        if (frozenFamilies(edition).contains(family) && movesWhatItCovers.getAsBoolean()) {
            throw refusal(edition.getPhase(), EnumSet.of(family));
        }
    }

    private static BusinessError.Frozen refusal(EditionPhase phase, Set<ReferentialFamily> frozen) {
        return new BusinessError.Frozen(
                message(phase, frozen), frozen.stream().map(Enum::name).toList());
    }

    static String message(EditionPhase phase, Set<ReferentialFamily> frozen) {
        String familles = ReferentialFamily.quote(frozen);
        return switch (phase) {
            case PUBLIEE ->
                "L'édition est publiée : sa structure est figée (" + familles + "). Débloquez la structure pour un"
                        + " changement de dernière minute — les fiches des animateurs, les consignes et les"
                        + " disponibilités restent modifiables.";
            case TERMINEE ->
                "L'édition est terminée : son référentiel est figé (" + familles + "). Changez sa phase pour"
                        + " modifier ces fiches.";
            default ->
                "L'édition est en phase « " + phase.libelle() + " » : le référentiel est figé (" + familles
                        + "). Repassez-la en Préparation avant de modifier ces fiches — les consignes et les"
                        + " disponibilités restent modifiables.";
        };
    }

    /* --------------------------- Partial families --------------------------- */

    /** Whether the edit moves a field the {@link ReferentialFamily#STANDS} freeze covers. */
    static boolean changesFrozenStandFields(Stand avant, Stand apres) {
        return avant != null && ChampsModifies.surStand(avant, apres).stream().anyMatch(STAND_FIELDS::contains);
    }

    /**
     * Whether the edit moves the competences of an animateur already in the
     * roster. A new fiche is not a retouch: creating an animateur with their
     * competences stays open under the freeze.
     */
    static boolean changesCompetences(Animateur avant, Animateur apres) {
        return avant != null && !competences(avant).equals(competences(apres));
    }

    private static Map<String, NiveauCompetence> competences(Animateur animateur) {
        return animateur.getCompetences() == null ? Map.of() : Map.copyOf(animateur.getCompetences());
    }

    /**
     * Whether the edit moves what the {@link ReferentialFamily#TYPOLOGIES_EMPLACEMENTS}
     * freeze covers of a game category: its cap per animateur and whether it
     * is the polyvalent one. The label and the note stay editable.
     */
    static boolean changesFrozenTypologieFields(TypologieItem avant, TypologieItem apres) {
        return avant != null
                && (!Objects.equals(avant.maxCreneauxParAnimateur(), apres.maxCreneauxParAnimateur())
                        || avant.polyvalent() != apres.polyvalent());
    }
}
