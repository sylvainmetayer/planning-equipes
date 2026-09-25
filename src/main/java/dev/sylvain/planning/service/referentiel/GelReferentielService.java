package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.journal.ChampsModifies;
import dev.sylvain.planning.service.journal.CurrentAction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The freeze of the referential: a family the organiser declared ready, and
 * the refusal every write of it then meets, whatever path it takes — a form,
 * the bulk edit, a CSV or grid import, an MCP tool (ADR 0052).
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
 * <p>What stays open under any freeze is deliberate, not forgotten: the
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
    static final Set<String> STAND_FIELDS = Set.of(
            "typologiesProposees",
            "effectifMin",
            "effectifMax",
            "reserveMajeurs",
            "indisponibilites",
            "ouvertures",
            "horaires");

    private final GelReferentielRepository repository;

    /** Where the family frozen or lifted is left for the history line, which names it. */
    private final CurrentAction currentAction;

    @Inject
    public GelReferentielService(GelReferentielRepository repository, CurrentAction currentAction) {
        this.repository = repository;
        this.currentAction = currentAction;
    }

    /** The frozen families of the current edition, in declaration order. */
    public List<GelReferentiel> list() {
        return repository.list().stream()
                .sorted(Comparator.comparing(GelReferentiel::famille))
                .toList();
    }

    /**
     * One family and whether it is frozen — the line the État de l'édition
     * card, the Paramètres switches and {@code etat_edition} all read.
     *
     * @param libelle how the screens name the family
     * @param figeLe  when it was frozen, {@code null} while it is open
     */
    @Schema(requiredProperties = {"famille", "fige", "libelle"})
    public record EtatGel(ReferentialFamily famille, String libelle, boolean fige, Instant figeLe) {

        static EtatGel of(ReferentialFamily famille, Optional<GelReferentiel> gel) {
            return new EtatGel(
                    famille,
                    famille.libelle(),
                    gel.isPresent(),
                    gel.map(GelReferentiel::figeLe).orElse(null));
        }
    }

    /** Every family, frozen or not, in declaration order. */
    public List<EtatGel> etat() {
        List<GelReferentiel> gels = repository.list();
        return Arrays.stream(ReferentialFamily.values())
                .map(famille -> EtatGel.of(
                        famille,
                        gels.stream().filter(gel -> gel.famille() == famille).findFirst()))
                .toList();
    }

    /** One family, frozen or not. */
    public EtatGel etat(ReferentialFamily famille) {
        return EtatGel.of(famille, find(famille));
    }

    public Optional<GelReferentiel> find(ReferentialFamily famille) {
        return repository.list().stream()
                .filter(gel -> gel.famille() == famille)
                .findFirst();
    }

    public boolean isFrozen(ReferentialFamily famille) {
        return find(famille).isPresent();
    }

    /**
     * Freezes the family and returns it as stored — with its original date
     * when it already was. Accepted while a solve runs: the running solve read
     * its problem at its start, and the freeze changes no data it reads.
     */
    public GelReferentiel freeze(ReferentialFamily famille) {
        repository.freeze(Objects.requireNonNull(famille, "famille"));
        currentAction.champsModifies(List.of(famille.name()));
        return find(famille).orElseThrow();
    }

    /** Lifts the freeze; {@code false} when there was none to lift. */
    public boolean lift(ReferentialFamily famille) {
        boolean levee = repository.lift(Objects.requireNonNull(famille, "famille"));
        currentAction.champsModifies(List.of(famille.name()));
        return levee;
    }

    /**
     * Refuses when any of {@code families} is frozen — every family when none
     * is named, which is what an operation replacing the whole edition asks
     * (a scenario import, the reset). The refusal names each frozen family, so
     * the organiser knows which freeze to lift.
     */
    public void refuseIfFrozen(ReferentialFamily... families) {
        Set<ReferentialFamily> asked =
                families.length == 0 ? EnumSet.allOf(ReferentialFamily.class) : EnumSet.copyOf(Arrays.asList(families));
        Set<ReferentialFamily> frozen = EnumSet.noneOf(ReferentialFamily.class);
        for (GelReferentiel gel : repository.list()) {
            if (asked.contains(gel.famille())) {
                frozen.add(gel.famille());
            }
        }
        if (!frozen.isEmpty()) {
            throw refusal(frozen);
        }
    }

    /**
     * Refuses when {@code family} is frozen and the edit moves what it covers:
     * the partial families' guard. The freeze is read once, and
     * {@code movesWhatItCovers} — which may need a before-image — is asked
     * only when the family is frozen, so an open edition pays one small read
     * per write and nothing more.
     */
    public void refuseIfFrozen(ReferentialFamily family, BooleanSupplier movesWhatItCovers) {
        if (isFrozen(family) && movesWhatItCovers.getAsBoolean()) {
            throw refusal(EnumSet.of(family));
        }
    }

    private static BusinessError.Frozen refusal(Set<ReferentialFamily> frozen) {
        return new BusinessError.Frozen(
                message(frozen), frozen.stream().map(Enum::name).toList());
    }

    private static String message(Set<ReferentialFamily> frozen) {
        return "Le référentiel est figé : " + ReferentialFamily.quote(frozen)
                + ". Levez le gel (État de l'édition ou Paramètres) avant de modifier ces fiches — les consignes et"
                + " les disponibilités restent modifiables.";
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
                        || avant.ninja() != apres.ninja());
    }
}
