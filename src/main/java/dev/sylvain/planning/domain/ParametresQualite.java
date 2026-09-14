package dev.sylvain.planning.domain;

import java.time.LocalTime;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Organisational-quality thresholds a constraint needs at solve time, carried
 * as a problem fact so a rule can join them exactly like
 * {@link ParametresLegaux}. Unlike the legal ones, these are not stored per
 * edition in the database: they are read from application configuration
 * ({@code planning.contraintes.*}) by {@code PlanningService}, because they
 * tune the plan's comfort rather than the law it must obey.
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
 */
@Schema(requiredProperties = {"maxEmplacementsDistinctsParJour", "reposSouhaiteApresServiceTardifMinutes"})
public record ParametresQualite(
        int maxEmplacementsDistinctsParJour,
        LocalTime heureServiceTardif,
        LocalTime heureServiceMatinal,
        int reposSouhaiteApresServiceTardifMinutes) {

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

    public ParametresQualite() {
        this(EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT);
    }

    public ParametresQualite(int maxEmplacementsDistinctsParJour) {
        this(
                maxEmplacementsDistinctsParJour,
                HEURE_SERVICE_TARDIF_PAR_DEFAUT,
                HEURE_SERVICE_MATINAL_PAR_DEFAUT,
                REPOS_SOUHAITE_APRES_SERVICE_TARDIF_MINUTES_PAR_DEFAUT);
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
