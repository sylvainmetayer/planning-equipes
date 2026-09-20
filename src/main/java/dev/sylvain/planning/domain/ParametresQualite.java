package dev.sylvain.planning.domain;

import java.time.LocalTime;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Organisational-quality thresholds a constraint needs at solve time, carried
 * as a problem fact so a rule can join them exactly like
 * {@link ParametresLegaux} — and, since issue #591, stored per edition just
 * like them. What {@code planning.contraintes.*} configures is now the
 * <b>default</b>: the values an edition that never opened the screen solves
 * with. They tune the plan's comfort rather than the law it must obey, so
 * unlike the legal ones nothing here has a floor to refuse.
 *
 * <p>A record, unlike {@link ParametresLegaux}: a problem fact is immutable by
 * nature — the solver reads it thousands of times per second and never writes
 * it — and the setter this used to carry was never called from anywhere.</p>
 *
 * @param maxEmplacementsDistinctsParJour how many distinct {@link Emplacement}s
 *        an animateur may cover in one day before
 *        {@code limiterEmplacementsParJour} starts penalising. Three lets a
 *        normal day (a stand in the morning, another in the afternoon, a third
 *        in the evening) pass untouched, so the default changes nothing on an
 *        existing plan — it only catches the genuinely scattered days.
 * @param heureServiceTardif clock time from which a vacation counts as a
 *        <i>late service</i> for {@code eviterFermeturePuisOuverture} — a
 *        vacation ending at or after it on its own day, midnight crossings
 *        included. {@code null} makes that rule inert.
 * @param heureServiceMatinal clock time up to which a vacation counts as an
 *        <i>early service</i> for the same rule — a vacation starting at or
 *        before it. {@code null} makes that rule inert.
 * @param reposSouhaiteApresServiceTardifMinutes rest wished for between a late
 *        service and the early service of the next day. Only what it asks
 *        <b>beyond</b> the legal daily rest is ever penalised, so a value at or
 *        under that floor leaves the rule inert — it is a comfort, never a
 *        second legal floor in disguise.
 * @param typologiesDistinctesMax how many distinct typologies of jeu an
 *        animateur may cover over the <b>whole edition</b> before
 *        {@code limiterTypologiesDistinctesParAnimateur} starts penalising,
 *        proportionally to the excess. The scope is the edition and not the
 *        day: two typologies on one afternoon and two typologies a week apart
 *        count the same. Ninjas are exempt, versatility being what they are
 *        there for.
 * @param joursConsecutifsMax how many days in a row an animateur may
 *        work before {@code maxJoursConsecutifsTravailles} — and its hard twin
 *        {@code maxJoursConsecutifsTravaillesDur}, which the catalogue ships
 *        off — start counting the excess. Both read this one value, so the
 *        dosed form and the blocking form can never disagree on what « days in
 *        a row » means. Counted on the grid's own day numbers, not on
 *        calendar dates: a day the event does not cover breaks no run.
 */
@Schema(
        requiredProperties = {
            "maxEmplacementsDistinctsParJour",
            "reposSouhaiteApresServiceTardifMinutes",
            "typologiesDistinctesMax",
            "joursConsecutifsMax"
        })
public record ParametresQualite(
        int maxEmplacementsDistinctsParJour,
        LocalTime heureServiceTardif,
        LocalTime heureServiceMatinal,
        int reposSouhaiteApresServiceTardifMinutes,
        int typologiesDistinctesMax,
        int joursConsecutifsMax) {

    /** @see #maxEmplacementsDistinctsParJour() */
    public static final int EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT = 3;

    /**
     * 22:00 — the hour from which the Code du travail counts the night of a 16
     * to 18 year old (art. L3163-1). Borrowed as a landmark, not as a rule:
     * nothing legal follows from it here, it is simply the moment the event
     * itself already treats as late.
     */
    public static final LocalTime HEURE_SERVICE_TARDIF_PAR_DEFAUT = LocalTime.of(22, 0);

    /**
     * 10:00, the hour of the very case issue #78 was opened on: « finit à 23 h
     * et reprend à 10 h le lendemain ». Not 09:00, which would have shipped an
     * inert rule: a vacation ending at or after 22:00 and one starting at or
     * before 09:00 are at most 11 h apart, i.e. never further than the legal
     * floor this rule deliberately refuses to count — the only pair it could
     * have caught on a lawful plan is the single point 22:00 → 09:00.
     */
    public static final LocalTime HEURE_SERVICE_MATINAL_PAR_DEFAUT = LocalTime.of(10, 0);

    /**
     * 12 h. One hour above the 11 h an adult is owed (art. L3131-1), so the
     * rule has something of its own to say, and below the 12 h a minor is owed
     * anyway — for them it is the law that binds, and this rule stays silent.
     */
    public static final int REPOS_SOUHAITE_APRES_SERVICE_TARDIF_MINUTES_PAR_DEFAUT = 12 * 60;

    /**
     * 2 — one or two typologies is the ideal the organisers describe, five the
     * cited bad case. Lived in {@code QualiteConstraints} as a private constant
     * until issue #591: an organiser for whom three typologies is normal had no
     * way to say so, and could only dose the rule's weight until it stopped
     * mattering.
     */
    public static final int TYPOLOGIES_DISTINCTES_MAX_PAR_DEFAUT = 2;

    /**
     * 8. No article of the Code du travail founds a rolling count of
     * consecutive days (see ADR 0045), so this is an organiser's policy and
     * belongs here rather than with the legal parameters: nothing refuses a
     * value, where a legal floor would. The weekly rest itself stays held
     * hard, by {@code maxJoursTravaillesParSemaine} and
     * {@code reposHebdomadaireMinimal}.
     *
     * <p><b>Why 8 and not the 6 the organisation first stated.</b> The
     * threshold is not a comfort but a feasibility condition: the rule demands
     * a rest day inside <em>every</em> window of {@code max + 1} days, so one
     * day more or less changes how many rest days the grid must contain.
     * Measured on {@code festival-hivernal}, the anonymised real-world
     * fixture, with the hard form switched on: at 6 the solver leaves 22 seats
     * unfilled after twenty minutes, at 7 it still leaves seven, and at 8 it
     * reaches zero hard in 294 s. A ceiling nothing can satisfy is worse than
     * a looser one that holds, and no weight can buy what the arithmetic
     * forbids. Tightening back toward 6 is an open question for the
     * organisation, to be reopened with the roster or the grid — not with the
     * score.</p>
     */
    public static final int JOURS_CONSECUTIFS_MAX_PAR_DEFAUT = 8;

    public ParametresQualite() {
        this(EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT);
    }

    public ParametresQualite(int maxEmplacementsDistinctsParJour) {
        this(
                maxEmplacementsDistinctsParJour,
                HEURE_SERVICE_TARDIF_PAR_DEFAUT,
                HEURE_SERVICE_MATINAL_PAR_DEFAUT,
                REPOS_SOUHAITE_APRES_SERVICE_TARDIF_MINUTES_PAR_DEFAUT,
                TYPOLOGIES_DISTINCTES_MAX_PAR_DEFAUT,
                JOURS_CONSECUTIFS_MAX_PAR_DEFAUT);
    }

    /**
     * False when the deployment left one of the three thresholds out, which is
     * how {@code eviterFermeturePuisOuverture} is neutralised without touching
     * the code: no late hour, no early hour, or no rest to ask for.
     */
    public boolean penaliseFermeturePuisOuverture() {
        return heureServiceTardif != null && heureServiceMatinal != null && reposSouhaiteApresServiceTardifMinutes > 0;
    }
}
