package dev.sylvain.planning.service.solve;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.PastHorizon;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.service.BusinessError;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * « Le passé est figé » (ADR 0044): the seats of the timeslots already
 * started when a problem is built are re-seeded from the persisted plan and
 * pinned, whatever a solve would otherwise do with them.
 *
 * <p>Three questions, answered once here so every entry point — full solve,
 * incremental solve, the analyses of the persisted plan — reads the same
 * clock the same way:</p>
 *
 * <ul>
 *   <li><b>which seats are past</b> ({@link #isPast}): every seat of a date
 *       before today, whatever the hour; on today, a seat whose effective
 *       start is at or before the time of day. A timeslot crossing midnight
 *       belongs to its start date, so a 20:00-02:00 shift of yesterday is
 *       past at 01:00 today — its date is yesterday — and a 20:00 shift of
 *       today is ahead until 20:00;</li>
 *   <li><b>what a past seat holds</b> ({@link #freeze}): the animateur the
 *       persisted plan gave it, kept even if they have since declared the day
 *       off or a forced unavailability now covers them — it was worked, and
 *       the incremental rule that frees an invalidated seat does not apply to
 *       history. A holder the referential no longer knows leaves the seat
 *       empty, and an empty past seat is pinned all the same: nobody can staff
 *       yesterday, and {@code posteDoitEtrePourvu} does not reproach a past
 *       hole;</li>
 *   <li><b>how the score reads it</b> ({@link #mark}): the {@code passe} flag
 *       the constraint streams test, so a past seat counts — daily rest, weekly
 *       caps, consecutive days — but is never charged on its own.</li>
 * </ul>
 *
 * <p>The clock is {@code JourJClock}'s: the machine's date in production, the
 * date frozen through {@code PUT /api/debug/date-du-jour} under
 * {@code quarkus:dev} or {@code HORLOGE_SIMULEE_AUTORISEE=true} — which is
 * what makes this testable before the event. A {@code null} horizon means the
 * freeze is off ({@code planning.solver.passe-fige=false}): nothing is marked,
 * nothing is pinned, and the solver keeps rewriting the past as it did before
 * this rule existed. The horizon is read once per problem and kept on it
 * ({@link dev.sylvain.planning.domain.PlanningEvenement#getPastHorizon()}):
 * the preparation before the solve marks against the very horizon the build
 * re-seeded and pinned against, so no seat crosses its start in between and
 * ends up pinned empty without ever having been re-seeded.</p>
 */
public final class FrozenPast {

    /**
     * What every manual write on a past seat answers with (ADR 0044): the
     * drag-and-drop, an échange accepted, a repair applied or suggested. One
     * sentence, so the screen, the API and MCP refuse in the same words.
     */
    public static final String PAST_SEAT_REFUSAL = "Ce créneau est déjà commencé : le passé ne se modifie plus.";

    /**
     * What a solve answers when every seat of the edition is past: there is
     * nothing left to plan, and an « empty plan at zero hard » would say the
     * opposite of what happened.
     */
    public static final String NOTHING_AHEAD_REFUSAL = "Rien à planifier : tous les créneaux sont déjà commencés — "
            + "l'édition est terminée, ou la date simulée est après l'événement.";

    private FrozenPast() {}

    /**
     * Refuses a manual write on a seat whose timeslot has started (ADR 0044,
     * the freeze applied to the gestures): a past seat is a fact, and no hand
     * rewrites it either. A {@code null} horizon is the freeze switched off,
     * and then nothing is refused.
     *
     * @throws BusinessError.Invalid on a past seat
     */
    public static void refuseIfPast(PosteAffectation poste, PastHorizon horizon) {
        if (isPast(poste, horizon)) {
            throw new BusinessError.Invalid(PAST_SEAT_REFUSAL);
        }
    }

    /**
     * Refuses a problem whose every seat is already marked past: a solve with
     * nothing ahead would persist an empty future over a finished edition — or
     * over one whose simulated date landed after the event — and report zero
     * hard on it. A problem with no seat at all is refused earlier, on the
     * reference data.
     *
     * @throws BusinessError.Invalid when nothing is left to plan
     */
    public static void refuseIfNothingAhead(List<PosteAffectation> postes) {
        if (postes.isEmpty()) {
            return;
        }
        for (PosteAffectation poste : postes) {
            if (!poste.isPasse()) {
                return;
            }
        }
        throw new BusinessError.Invalid(NOTHING_AHEAD_REFUSAL);
    }

    /**
     * How many seats already marked past hold nobody: worked by no one the
     * plan knows of — never staffed, or staffed by somebody the referential
     * has since forgotten. Reported as a warning, never charged.
     */
    public static int countEmptyPast(List<PosteAffectation> postes) {
        int vides = 0;
        for (PosteAffectation poste : postes) {
            if (poste.isPasse() && poste.getAnimateur() == null) {
                vides++;
            }
        }
        return vides;
    }

    /**
     * Whether this seat's timeslot had started at {@code horizon}: a strictly
     * earlier date, or today's date with an effective start at or before the
     * time of day. A seat without a dated timeslot is never past — there is
     * nothing to compare — and a {@code null} horizon says the freeze is off.
     */
    public static boolean isPast(PosteAffectation poste, PastHorizon horizon) {
        if (horizon == null || poste.getCreneau() == null || poste.getCreneau().getDate() == null) {
            return false;
        }
        LocalDate date = poste.getCreneau().getDate();
        if (date.isBefore(horizon.today())) {
            return true;
        }
        if (date.isAfter(horizon.today())) {
            return false;
        }
        LocalTime debut = poste.heureDebutEffectif();
        return debut != null && !debut.isAfter(horizon.now());
    }

    /**
     * Sets the {@code passe} flag on every past seat, and clears it on the
     * others — the marking is the horizon's, never a leftover of an earlier
     * preparation of the same planning.
     *
     * @return how many seats are past
     */
    public static int mark(List<PosteAffectation> postes, PastHorizon horizon) {
        int passes = 0;
        for (PosteAffectation poste : postes) {
            boolean passe = isPast(poste, horizon);
            poste.setPasse(passe);
            if (passe) {
                passes++;
            }
        }
        return passes;
    }

    /**
     * Pins every seat already marked past, empty or not — marked by the
     * horizon the problem carries, never by a fresh clock reading. What a
     * solve on a caller-provided problem gets: the past cannot be re-seeded
     * from a plan the caller did not send, but it can at least not be moved.
     */
    public static void pin(List<PosteAffectation> postes) {
        for (PosteAffectation poste : postes) {
            if (poste.isPasse()) {
                poste.setVerrouille(true);
            }
        }
    }

    /**
     * The freeze of a full solve: every past seat is marked, re-seeded from
     * the persisted plan and pinned. Positional on stand × créneau like the
     * locks and the incremental reconciliation (see
     * {@link PlanningPersistenceService#loadAnimateursByStandCreneau()}), and
     * walking <em>every</em> seat so the places it counts are the same places
     * the other walks count.
     *
     * <p>A seat the locks already pinned is left exactly as they left it — it
     * carries its holder, and a lock and the past agree on what to do with
     * it. Runs after the locks and before the warm start, which skips pinned
     * seats: the past is therefore neither re-seeded a second time nor
     * « freed because invalid » by that rule.</p>
     *
     * @return how many seats are past
     */
    static int freeze(
            List<PosteAffectation> postes,
            List<Animateur> animateurs,
            Map<String, List<String>> animateursPersistes,
            PastHorizon horizon) {
        Map<String, Animateur> animateursById = new HashMap<>();
        for (Animateur animateur : animateurs) {
            animateursById.put(animateur.getId(), animateur);
        }
        Map<String, Integer> prochainePlace = new HashMap<>();
        int passes = 0;
        for (PosteAffectation poste : postes) {
            if (poste.getStand() == null || poste.getCreneau() == null) {
                continue;
            }
            String key = PlanningPersistenceService.standCreneauKey(
                    poste.getStand().getId(), poste.getCreneau().getId());
            List<String> tenants = animateursPersistes.getOrDefault(key, List.of());
            int place = prochainePlace.merge(key, 1, Integer::sum) - 1;
            if (!isPast(poste, horizon)) {
                continue;
            }
            poste.setPasse(true);
            passes++;
            if (poste.isVerrouille()) {
                continue;
            }
            String tenantId = place < tenants.size() ? tenants.get(place) : null;
            poste.setAnimateur(tenantId == null ? null : animateursById.get(tenantId));
            poste.setVerrouille(true);
        }
        return passes;
    }
}
