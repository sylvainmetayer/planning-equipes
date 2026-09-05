package dev.sylvain.planning.solver.constraints;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.Joiners;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.JoursFeries;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PlafondsLegauxMajeurs;
import dev.sylvain.planning.domain.PlafondsLegauxMineurs;
import dev.sylvain.planning.domain.PosteAffectation;

/**
 * Legal / safety hard constraints. None of these may ever be violated in a
 * valid plan.
 *
 * <p>Every rule here is anchored on a specific article of the French
 * <b>Code du travail</b> — each method's javadoc cites its own.</p>
 *
 * <table>
 * <caption>Applicable articles</caption>
 * <tr><th>Sujet</th><th>Moins de 16 ans</th><th>16 à 18 ans</th><th>Majeur</th></tr>
 * <tr><td>Travail de nuit</td><td>20 h-6 h (L3163-1)</td><td>22 h-6 h (L3163-1)</td>
 *     <td>non encadré par l'outil, voir docs/contraintes.md</td></tr>
 * <tr><td>Repos quotidien</td><td>14 h (L3164-1)</td><td>12 h (L3164-1)</td><td>11 h (L3131-1)</td></tr>
 * <tr><td>Durée quotidienne</td><td>7 h (D4153-3)</td><td>8 h (L3162-1)</td><td>10 h (L3121-18)</td></tr>
 * <tr><td>Durée hebdomadaire</td><td colspan="2">35 h (L3162-1, D4153-3)</td><td>48 h (L3121-20)</td></tr>
 * <tr><td>Travail continu / pause</td><td colspan="2">4 h 30, pause 30 min (L3162-3)</td>
 *     <td>6 h, pause 20 min (L3121-16)</td></tr>
 * <tr><td>Repos hebdomadaire</td><td colspan="2">2 jours consécutifs (L3164-2)</td>
 *     <td>6 jours max (L3132-1), 35 h consécutives (L3132-2)</td></tr>
 * <tr><td>Jours fériés</td><td colspan="2">travail interdit (L3164-6)</td><td>autorisé</td></tr>
 * </table>
 *
 * <p>Age brackets are always derived from {@code dateNaissance} at the
 * créneau's date, never stored — see {@code Animateur.isMineurOn} /
 * {@code Animateur.isUnder16On}.</p>
 *
 * <p>{@link #mineurNecessiteEncadrementMajeur} is the one rule here with
 * <b>no legal basis identified</b>: it is an organiser safety policy, kept
 * hard by choice and catalogued under "Sécurité (mineurs)".</p>
 */
public final class LegalConstraints {

    /** Art. L3131-1: 11 consecutive hours of daily rest for an adult. */
    private static final int REPOS_QUOTIDIEN_MIN_MAJEUR_MINUTES = 11 * 60;

    /** Art. L3164-1: 12 consecutive hours of daily rest for a young worker. */
    private static final int REPOS_QUOTIDIEN_MIN_MINEUR_MINUTES = 12 * 60;

    /** Art. L3164-1: 14 consecutive hours of daily rest under 16. */
    private static final int REPOS_QUOTIDIEN_MIN_MOINS_DE_16_ANS_MINUTES = 14 * 60;

    /** Art. L3121-16: an adult's uninterrupted work may not exceed 6 h. */
    private static final int TRAVAIL_CONTINU_MAX_MAJEUR_MINUTES = PlafondsLegauxMajeurs.TRAVAIL_CONTINU_MAX_MINUTES;

    /** Art. L3121-16: the break that interrupts an adult's working stretch lasts at least 20 min. */
    private static final int PAUSE_MIN_MAJEUR_MINUTES = PlafondsLegauxMajeurs.PAUSE_MINIMALE_MINUTES;

    /** Art. L3162-3: the break that interrupts a young worker's stretch lasts at least 30 min. */
    private static final int PAUSE_MIN_MINEUR_MINUTES = PlafondsLegauxMineurs.PAUSE_MINIMALE_MINUTES;

    /**
     * Art. L3132-2 + L3131-1: 24 consecutive hours of weekly rest, on top of the
     * 11 h of daily rest — i.e. 35 consecutive hours.
     */
    private static final int REPOS_HEBDOMADAIRE_MIN_MINUTES = 35 * 60;

    /** Art. L3164-2: two consecutive rest days per week for young workers. */
    private static final int JOURS_REPOS_CONSECUTIFS_MINEUR = 2;

    public Constraint[] define(ConstraintFactory constraintFactory) {
        return new Constraint[] {
                standReserveAuxMajeurs(constraintFactory),
                mineurNecessiteEncadrementMajeur(constraintFactory),
                travailDeNuitInterditPourMineur(constraintFactory),
                dureeQuotidienneMaxMineur(constraintFactory),
                dureeHebdomadaireMax(constraintFactory),
                dureeHebdomadaireMaxMineur(constraintFactory),
                dureeQuotidienneMaxMajeur(constraintFactory),
                reposQuotidienMinimal(constraintFactory),
                travailContinuMaxMajeur(constraintFactory),
                travailContinuMaxMineur(constraintFactory),
                maxJoursTravaillesParSemaine(constraintFactory),
                reposHebdomadaireMinimal(constraintFactory),
                reposHebdomadaireMineur(constraintFactory),
                travailInterditJourFerieMineur(constraintFactory),
                pauseMinimaleEntreVacations(constraintFactory)
        };
    }

    /**
     * No minor on a stand flagged {@code reserveMajeurs}.
     *
     * <p>{@code reserveMajeurs} is a <b>business flag</b>, not in itself a
     * legal statement: the restriction may come from the "travaux réglementés"
     * forbidden to under-18s (art. L4153-8, D4153-15 et suivants
     * <b>[non vérifié — à faire validate]</b>) or from a purely internal policy
     * (bar stand, adult-only games…). The model does not distinguish the two;
     * see docs/contraintes.md.</p>
     */
    private Constraint standReserveAuxMajeurs(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class), "standReserveAuxMajeurs")
                .filter(poste -> poste.getAnimateur() != null
                        && poste.getStand().isReserveMajeurs()
                        && poste.getAnimateur().isMineurOn(poste.getCreneau().getDate()))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("standReserveAuxMajeurs");
    }

    /**
     * At least one adult on the same stand and slot as a minor.
     *
     * <p><b>No article of the Code du travail was identified</b> that imposes
     * this. Supervision duties for minors come either from the Code de l'action
     * sociale et des families (where minors are the audience, not the staff) or
     * from the derogation regime for "travaux réglementés" (art. R4153-40 et
     * suivants <b>[non vérifié — à faire validate]</b>). This is therefore an
     * organiser <b>safety policy</b>, deliberately kept as a hard constraint
     * but catalogued under "Sécurité (mineurs)", not "Légal (mineurs)", so
     * nobody disables it believing the whole minors' framework is optional.</p>
     */
    private Constraint mineurNecessiteEncadrementMajeur(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class),
                "mineurNecessiteEncadrementMajeur")
                .filter(poste -> poste.getAnimateur() != null
                        && poste.getAnimateur().isMineurOn(poste.getCreneau().getDate()))
                .ifNotExists(PosteAffectation.class,
                        Joiners.equal(PosteAffectation::getStand),
                        Joiners.equal(PosteAffectation::getCreneau),
                        Joiners.filtering((posteMineur, autrePoste) -> autrePoste.getAnimateur() != null
                                && autrePoste.getAnimateur().isMajeurOn(autrePoste.getCreneau().getDate())))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("mineurNecessiteEncadrementMajeur");
    }

    /**
     * No minor works during their legal night window.
     *
     * <p>Code du travail art. <b>L3163-1</b>: night work is <i>« tout travail
     * entre 22 heures et 6 heures »</i> for young workers aged 16 to 18, and
     * <i>« tout travail entre 20 heures et 6 heures »</i> for those under 16.
     * The window is therefore picked per animateur and per créneau date, from
     * {@code dateNaissance} — see {@code Creneau.chevaucheNuit(LocalTime)}.</p>
     *
     * <p>Until this fix the 20:00 window was applied to every minor, which is
     * more protective than the law but excluded 16-to-18-year-olds from the
     * 20:00-22:00 band the law allows them, needlessly shrinking the pool on a
     * event evening.</p>
     *
     * <p>Deliberately checked against the créneau's <i>full</i> window, not
     * {@link PosteAffectation#getHeureDebutEffective()}: unlike the duration
     * and rest-window helpers below, narrowing this check to a partial-closure
     * poste's actual sub-window would only ever make the constraint <i>more
     * permissive</i>, and a safety rule for minors should never become laxer
     * as an incidental side effect of an unrelated stand-availability
     * feature. Conservative in the same spirit as {@code semaineIso()}'s
     * "attributed to start date" simplification.</p>
     */
    private Constraint travailDeNuitInterditPourMineur(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class),
                "travailDeNuitInterditPourMineur")
                .filter(poste -> poste.getAnimateur() != null
                        && poste.getCreneau() != null
                        && poste.getAnimateur().isMineurOn(poste.getCreneau().getDate())
                        && poste.getCreneau().chevaucheNuit(
                                debutNuit(poste.getAnimateur(), poste.getCreneau().getDate())))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("travailDeNuitInterditPourMineur");
    }

    /**
     * Daily working-time cap for minors: 8 h, lowered to 7 h under 16.
     *
     * <p>Code du travail art. <b>L3162-1</b>: <i>« Les jeunes travailleurs ne
     * peuvent être employés à un travail effectif excédant huit heures par
     * jour […] »</i>. Art. <b>D4153-3</b>, for a minor aged 14 to under 16
     * employed during school holidays: <i>« La durée du travail du mineur ne
     * peut excéder trente-cinq heures par semaine ni sept heures par
     * jour. »</i></p>
     *
     * <p>The 7 h cap is applied to every under-16 rather than only during
     * school holidays: employing an under-16 is forbidden outside school
     * holidays anyway (art. L4153-1), so the holiday regime is the only one
     * that can legitimately occur here, and applying it unconditionally is the
     * protective default. The application has no school-calendar data.</p>
     *
     * <p>Note that {@code Creneau.getDureeMinutes()} measures amplitude, which
     * equals travail effectif only because no break is modelled inside a
     * créneau — see {@code docs/domaine.md}.</p>
     */
    private Constraint dureeQuotidienneMaxMineur(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class),
                "dureeQuotidienneMaxMineur")
                .filter(poste -> poste.getAnimateur() != null
                        && poste.getCreneau() != null
                        && poste.getAnimateur().isMineurOn(poste.getCreneau().getDate()))
                .groupBy(PosteAffectation::getAnimateur,
                        poste -> poste.getCreneau().getDate(),
                        ConstraintCollectors.toList())
                .join(ParametresLegaux.class)
                .filter((animateur, date, postes, parametres) -> effectiveWorkMineurMinutes(postes, parametres)
                        > dailyCapForMineur(animateur, date))
                .penalize(HardMediumSoftScore.ONE_HARD,
                        (animateur, date, postes, parametres) -> effectiveWorkMineurMinutes(postes, parametres)
                                - dailyCapForMineur(animateur, date))
                .asConstraint("dureeQuotidienneMaxMineur");
    }

    /** Night window start applicable to this minor on this date (art. L3163-1). */
    private static LocalTime debutNuit(Animateur animateur, LocalDate date) {
        return animateur.isUnder16On(date)
                ? Creneau.DEBUT_NUIT_MOINS_DE_16_ANS
                : Creneau.DEBUT_NUIT_16_A_18_ANS;
    }

    /** Daily working-time cap applicable to this minor on this date (art. L3162-1 / D4153-3). */
    private static int dailyCapForMineur(Animateur animateur, LocalDate date) {
        return animateur.isUnder16On(date)
                ? PlafondsLegauxMineurs.DUREE_QUOTIDIENNE_MAX_MOINS_DE_16_ANS_MINUTES
                : PlafondsLegauxMineurs.DUREE_QUOTIDIENNE_MAX_MINUTES;
    }

    /**
     * Minimum consecutive daily rest between two working days, for every
     * animateur, graduated by age bracket.
     *
     * <ul>
     * <li>Adults — art. <b>L3131-1</b>: <i>« Tout salarié bénéficie d'un repos
     * quotidien d'une durée minimale de onze heures consécutives […] »</i></li>
     * <li>Minors — art. <b>L3164-1</b>: <i>« La durée minimale du repos
     * quotidien des jeunes travailleurs ne peut être inférieure à douze heures
     * consécutives. Cette durée minimale est portée à quatorze heures
     * consécutives s'ils ont moins de seize ans. »</i></li>
     * </ul>
     *
     * <p>Replaces the former {@code reposQuotidienMineur}, which only fired
     * after a minor held a <i>night</i> slot — a situation
     * {@link #travailDeNuitInterditPourMineur} already forbids outright, so in
     * any valid plan it scored zero: a structurally dead hard constraint that
     * nonetheless was the only thing the catalogue presented as covering daily
     * rest. It also forbade nothing but a "before noon" restart, i.e. it never
     * measured an actual rest duration.</p>
     *
     * <p>Implemented as a <b>directional</b> {@code .join()} on the day-
     * adjacency key ({@code jourA + 1 == jourB}), not {@code forEachUniquePair}:
     * the key is asymmetric, and {@code forEachUniquePair} would test only one
     * arbitrary ordering per unordered pair, silently missing every violation
     * where the earlier day landed on the right-hand side. {@code .join()}
     * enumerates both orderings, and only the one where A is the earlier day
     * can match, so there is no double count. It is also the cheap form: a hash
     * join on the exact adjacency key rather than a full same-animateur scan.</p>
     *
     * <p>Every (day J, day J+1) pair is checked, not just the last/first poste
     * of each day: the binding pair is the one with the smallest gap, and every
     * other pair has a larger gap, so the result is identical.</p>
     *
     * <p>{@code Creneau.assignerJours} guarantees calendar-consecutive dates get
     * consecutive {@code jour} numbers, even across a créneau-less gap day —
     * this constraint depends on that invariant.</p>
     */
    private Constraint reposQuotidienMinimal(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class)
                .filter(LegalConstraints::horaireConnu)
                .join(PosteAffectation.class,
                        Joiners.equal(PosteAffectation::getAnimateur),
                        Joiners.equal(veille -> veille.getCreneau().getJour() + 1,
                                lendemain -> lendemain.getCreneau().getJour())),
                "reposQuotidienMinimal")
                .filter((veille, lendemain) -> horaireConnu(lendemain)
                        && gapMinutes(veille, lendemain) < reposQuotidienMinimal(veille))
                .penalize(HardMediumSoftScore.ONE_HARD,
                        (veille, lendemain) -> reposQuotidienMinimal(veille) - gapMinutes(veille, lendemain))
                .asConstraint("reposQuotidienMinimal");
    }

    /**
     * Daily working-time cap for adult animateurs: 10 h.
     *
     * <p>Code du travail art. <b>L3121-18</b>: <i>« La durée quotidienne de
     * travail effectif par salarié ne peut excéder dix heures, sauf : »</i>
     * (derogations by the labour inspectorate, emergency, art. L3121-19). None
     * of those derogations is data the application holds, so the cap is applied
     * unconditionally. The CCN ÉCLAT also retains 10 h of travail effectif per
     * day <b>[non vérifié — à faire validate sur le text conventionnel]</b>.</p>
     *
     * <p>Until this constraint existed, the only daily cap in the referential
     * was the minors' one: on {@code scenario-complet.yaml} an adult could hold
     * the three slots of a same day, i.e. 14 hours.</p>
     */
    private Constraint dureeQuotidienneMaxMajeur(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class),
                "dureeQuotidienneMaxMajeur")
                .filter(poste -> poste.getAnimateur() != null
                        && poste.getCreneau() != null
                        && poste.getAnimateur().isMajeurOn(poste.getCreneau().getDate()))
                .groupBy(PosteAffectation::getAnimateur,
                        poste -> poste.getCreneau().getDate(),
                        ConstraintCollectors.toList())
                .join(ParametresLegaux.class)
                .filter((animateur, date, postes, parametres) -> effectiveWorkMajeurMinutes(postes, parametres)
                        > PlafondsLegauxMajeurs.DUREE_QUOTIDIENNE_MAX_MINUTES)
                .penalize(HardMediumSoftScore.ONE_HARD,
                        (animateur, date, postes, parametres) -> effectiveWorkMajeurMinutes(postes, parametres)
                                - PlafondsLegauxMajeurs.DUREE_QUOTIDIENNE_MAX_MINUTES)
                .asConstraint("dureeQuotidienneMaxMajeur");
    }

    /**
     * An adult's uninterrupted working stretch may not exceed 6 h without a
     * 20-minute break.
     *
     * <p>Code du travail art. <b>L3121-16</b>: <i>« Dès que le temps de travail
     * quotidien atteint six heures, le salarié bénéficie d'un temps de pause
     * d'une durée minimale de vingt minutes consécutives. »</i></p>
     *
     * <p><b>Interpretation choice</b>: the break is treated as due <i>at the
     * latest</i> at the sixth hour, so a stretch may <i>reach</i> 6 h but not
     * exceed it. A stricter reading — any day reaching 6 h of work requires a
     * 20-minute break to exist, which would make a lone 6 h créneau
     * non-compliant — is defensible and <b>[à faire validate par un juriste]</b>.
     * Switching to it means comparing with {@code >=} here.</p>
     *
     * <p>By default the model has no break inside a créneau (see
     * {@code docs/domaine.md}): a break is the <i>gap between two créneaux of
     * the same animateur</i>. Consecutive créneaux separated by less than 20
     * minutes are therefore merged into a single stretch, whose length is
     * measured from the first start to the last end — counting the sub-legal
     * gaps as worked time, which is the protective reading.</p>
     *
     * <p>The Code requires the break to be real, not to be scheduled. When the
     * organiser declares {@link ParametresLegaux#isPauseSurPoste()} — the
     * break is taken on the post, by relay between colleagues — the stretch is
     * read as containing its break at the latest at the sixth hour, and this
     * constraint no longer fires; the daily cap then deducts that break from
     * the amplitude, see {@link #effectiveWorkMajeurMinutes(List, ParametresLegaux)}.</p>
     *
     * <p>Grouped per animateur <i>and date</i> so each group holds a handful of
     * postes. A stretch chained across midnight is caught by
     * {@link #reposQuotidienMinimal} instead, which sees a near-zero gap
     * between day J and day J+1.</p>
     */
    private Constraint travailContinuMaxMajeur(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class),
                "travailContinuMaxMajeur")
                .filter(poste -> poste.getAnimateur() != null
                        && horaireConnu(poste)
                        && poste.getAnimateur().isMajeurOn(poste.getCreneau().getDate()))
                .groupBy(PosteAffectation::getAnimateur,
                        poste -> poste.getCreneau().getDate(),
                        ConstraintCollectors.toList())
                .join(ParametresLegaux.class)
                .filter((animateur, date, postes, parametres) -> !parametres.isPauseSurPoste()
                        && longestSequenceMinutes(postes, PAUSE_MIN_MAJEUR_MINUTES)
                                > TRAVAIL_CONTINU_MAX_MAJEUR_MINUTES)
                .penalize(HardMediumSoftScore.ONE_HARD,
                        (animateur, date, postes, parametres) -> longestSequenceMinutes(postes, PAUSE_MIN_MAJEUR_MINUTES)
                                - TRAVAIL_CONTINU_MAX_MAJEUR_MINUTES)
                .asConstraint("travailContinuMaxMajeur");
    }

    /**
     * A young worker's uninterrupted working stretch may not exceed 4 h 30
     * without a 30-minute break.
     *
     * <p>Code du travail art. <b>L3162-3</b>: <i>« Aucune période de travail
     * effectif ininterrompue ne peut excéder, pour les jeunes travailleurs, une
     * durée maximale de quatre heures et demie. Lorsque le temps de travail
     * quotidien est supérieur à quatre heures et demie, les jeunes travailleurs
     * bénéficient d'un temps de pause d'au moins trente minutes
     * consécutives. »</i></p>
     *
     * <p>Both halves of the article collapse into a single rule once breaks are
     * modelled as gaps between créneaux: gaps shorter than 30 minutes are not
     * breaks, so the surrounding créneaux form one stretch, and that stretch
     * must not exceed 4 h 30. On {@code scenario-complet.yaml} the 14:00-20:00
     * slot is 6 h of continuous work — a minor assigned to it exceeded the
     * maximum by 1 h 30, with nothing to stop it.</p>
     *
     * <p>Same grouping and merging rationale as
     * {@link #travailContinuMaxMajeur}.</p>
     */
    private Constraint travailContinuMaxMineur(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class),
                "travailContinuMaxMineur")
                .filter(poste -> poste.getAnimateur() != null
                        && horaireConnu(poste)
                        && poste.getAnimateur().isMineurOn(poste.getCreneau().getDate()))
                .groupBy(PosteAffectation::getAnimateur,
                        poste -> poste.getCreneau().getDate(),
                        ConstraintCollectors.toList())
                .join(ParametresLegaux.class)
                .filter((animateur, date, postes, parametres) -> !parametres.isPauseSurPoste()
                        && longestSequenceMinutes(postes, PAUSE_MIN_MINEUR_MINUTES)
                                > PlafondsLegauxMineurs.TRAVAIL_CONTINU_MAX_MINUTES)
                .penalize(HardMediumSoftScore.ONE_HARD,
                        (animateur, date, postes, parametres) -> longestSequenceMinutes(postes, PAUSE_MIN_MINEUR_MINUTES)
                                - PlafondsLegauxMineurs.TRAVAIL_CONTINU_MAX_MINUTES)
                .asConstraint("travailContinuMaxMineur");
    }

    /**
     * No animateur works more than six days in the same ISO week.
     *
     * <p>Code du travail art. <b>L3132-1</b>: <i>« Il est interdit de faire
     * travailler un même salarié plus de six jours par semaine. »</i></p>
     *
     * <p>The event runs 15 days, i.e. more than two calendar weeks: without
     * this rule an animateur could be scheduled every single day. The weekly
     * hours cap does not stand in the way — 7 days × 6 h 45 = 47 h 15 satisfies
     * a 48 h ceiling on seven worked days.</p>
     */
    private Constraint maxJoursTravaillesParSemaine(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class),
                "maxJoursTravaillesParSemaine")
                .filter(poste -> poste.getAnimateur() != null && horaireConnu(poste))
                .groupBy(PosteAffectation::getAnimateur,
                        poste -> poste.getCreneau().semaineIso(),
                        ConstraintCollectors.countDistinct(poste -> poste.getCreneau().getDate()))
                .filter((animateur, semaine, jours) -> jours > PlafondsLegauxMajeurs.JOURS_TRAVAILLES_MAX_PAR_SEMAINE)
                .penalize(HardMediumSoftScore.ONE_HARD,
                        (animateur, semaine, jours) -> jours - PlafondsLegauxMajeurs.JOURS_TRAVAILLES_MAX_PAR_SEMAINE)
                .asConstraint("maxJoursTravaillesParSemaine");
    }

    /**
     * Every animateur gets 35 consecutive hours of rest credited to each ISO
     * week in which they work.
     *
     * <p>Code du travail art. <b>L3132-2</b>: <i>« Le repos hebdomadaire a une
     * durée minimale de vingt-quatre heures consécutives auxquelles s'ajoutent
     * les heures consécutives de repos quotidien prévu au chapitre Ier. »</i>
     * The daily rest of art. L3131-1 being 11 h, the floor is 24 + 11 = 35
     * consecutive hours.</p>
     *
     * <p>The week is the ISO week (Monday 00:00 → next Monday 00:00), the same
     * window as {@code Creneau.semaineIso()} and the weekly hour caps. A rest
     * period is <b>not</b> truncated at that boundary: the law's own structure
     * — 24 h of the week plus the daily rest that adjoins them — is what gets
     * credited, see {@link #deficitReposHebdomadaireMinutes(List)}. So a
     * Sunday or a Monday off counts like any other day off, which matters on an
     * event open seven days a week, and a Sunday off squeezed between a shift
     * ending at midnight and a 10:00 start still falls one hour short, as the
     * law says.</p>
     *
     * <p><b>Why the group is the whole animateur, and not one week of them</b>:
     * a rest straddling the Monday belongs to both weeks, and its real length
     * is only visible with the seats of both. Grouping per week and joining the
     * neighbouring week's occupations would keep the incrementality, at the
     * price of a join this rule is not hot enough to warrant — an animateur
     * holds a couple of dozen seats over a whole event, so the sweep is a few
     * dozen operations; it is merely re-run when any of their seats moves
     * instead of when one of that week's does. Revisit if a profile ever shows
     * this rule high.</p>
     */
    private Constraint reposHebdomadaireMinimal(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class),
                "reposHebdomadaireMinimal")
                .filter(poste -> poste.getAnimateur() != null && horaireConnu(poste))
                .groupBy(PosteAffectation::getAnimateur, ConstraintCollectors.toList())
                .filter((animateur, postes) -> deficitReposHebdomadaireMinutes(postes) > 0)
                .penalize(HardMediumSoftScore.ONE_HARD,
                        (animateur, postes) -> deficitReposHebdomadaireMinutes(postes))
                .asConstraint("reposHebdomadaireMinimal");
    }

    /**
     * A minor gets two <i>consecutive</i> rest days inside each ISO week.
     *
     * <p>Code du travail art. <b>L3164-2</b>: <i>« Les jeunes travailleurs ont
     * droit à deux jours de repos consécutifs par semaine. »</i></p>
     *
     * <p>Conventional derogations exist (weekly rest reduced to 36 consecutive
     * hours for young workers released from compulsory schooling), but they
     * require an extended collective agreement or a labour-inspectorate
     * authorisation — facts the application does not hold and must not presume.
     * The safe default (two consecutive days) is therefore applied
     * unconditionally; any derogation must become entered data before it can be
     * coded.</p>
     *
     * <p>Counted in <b>calendar days</b> inside the civil week, Monday 00:00 to
     * Sunday 24:00 (art. L3121-35, the week {@code Creneau.semaineIso()}
     * already uses): days of that week the event does not cover are free days
     * like any other. That does not make a partly covered week free of the
     * rule — the two free days must adjoin, so a week the event opens on
     * scattered days (Monday, Wednesday, Friday, Sunday) still falls short if
     * the minor works them all. A Sunday and the Monday after it are
     * <em>not</em> two days of one week: the right is « par semaine », the week
     * must <em>comporter</em> the rest (Cass. soc. 13 nov. 2025,
     * n° 24-10.733, on L3132-2), and each of the two weeks then holds one free
     * day only.</p>
     *
     * <p>Deliberately different from {@link #reposHebdomadaireMinimal}, which
     * <em>does</em> credit one rest straddling the Monday to both weeks — but
     * only what it spends in each: reaching the 35 h floor takes 24 h
     * <em>inside</em> the week, and only the adjoining daily rest is allowed to
     * overflow, as L3132-2 is written. Two consecutive days cannot be split
     * that way. See {@code docs/contraintes.md}, « La frontière de semaine ».</p>
     */
    private Constraint reposHebdomadaireMineur(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class),
                "reposHebdomadaireMineur")
                .filter(poste -> poste.getAnimateur() != null && horaireConnu(poste)
                        && poste.getAnimateur().isMineurOn(poste.getCreneau().getDate()))
                .groupBy(PosteAffectation::getAnimateur,
                        poste -> poste.getCreneau().semaineIso(),
                        ConstraintCollectors.toSet(poste -> poste.getCreneau().getDate()))
                .filter((animateur, semaine, jours) -> longestRunOfFreeDays(jours)
                        < JOURS_REPOS_CONSECUTIFS_MINEUR)
                .penalize(HardMediumSoftScore.ONE_HARD,
                        (animateur, semaine, jours) -> JOURS_REPOS_CONSECUTIFS_MINEUR
                                - longestRunOfFreeDays(jours))
                .asConstraint("reposHebdomadaireMineur");
    }

    /**
     * No minor works on a public holiday.
     *
     * <p>Code du travail art. <b>L3164-6</b>: <i>« Les jeunes travailleurs ne
     * peuvent travailler les jours de fête reconnus par la loi. »</i> The list
     * of those days is art. L3133-1, computed by {@link JoursFeries}.</p>
     *
     * <p><b>No derogation is implemented.</b> Art. R3164-2 opens sectoral
     * derogations set by decree; whether event management / animation is among
     * them is <b>[non vérifié — à faire validate par un juriste]</b>. The most
     * protective default therefore applies — a plain ban — and the derogation
     * is deliberately left uncoded and unconfigurable until instructed. See
     * {@link JoursFeries} for the other scope decisions (Alsace-Moselle,
     * outre-mer).</p>
     *
     * <p>Not hypothetical on the shipped data: the scenarios run through July
     * 2026 and cover 14 July.</p>
     */
    private Constraint travailInterditJourFerieMineur(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class),
                "travailInterditJourFerieMineur")
                .filter(poste -> poste.getAnimateur() != null
                        && poste.getCreneau() != null
                        && poste.getCreneau().getDate() != null
                        && poste.getAnimateur().isMineurOn(poste.getCreneau().getDate())
                        && JoursFeries.isFerieInFrance(poste.getCreneau().getDate()))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("travailInterditJourFerieMineur");
    }

    /* ------------------------------ helpers ------------------------------- */

    private static boolean horaireConnu(PosteAffectation poste) {
        return poste.getCreneau() != null
                && poste.getCreneau().getDate() != null
                && poste.getCreneau().getHeureDebut() != null;
    }

    /**
     * Start instant of the time this poste actually covers — the créneau's
     * own start, narrowed by {@link PosteAffectation#getHeureDebutEffective()}
     * when the stand is only partially closed on this créneau (see
     * {@code Creneau#segmentsOuvertsMinutes}). The date always comes from the
     * créneau: a partial closure narrows the clock time, never the day.
     */
    private static LocalDateTime debut(PosteAffectation poste) {
        return LocalDateTime.of(poste.getCreneau().getDate(), poste.heureDebutEffectif());
    }

    /** End instant, derived from the effective duration so a window crossing midnight ends the next day. */
    private static LocalDateTime fin(PosteAffectation poste) {
        return debut(poste).plusMinutes(poste.getDureeEffectiveMinutes());
    }

    /** Minimum consecutive daily rest applicable to this animateur (art. L3131-1 / L3164-1). */
    private static int reposQuotidienMinimal(PosteAffectation poste) {
        Animateur animateur = poste.getAnimateur();
        LocalDate date = poste.getCreneau().getDate();
        if (animateur.isUnder16On(date)) {
            return REPOS_QUOTIDIEN_MIN_MOINS_DE_16_ANS_MINUTES;
        }
        return animateur.isMineurOn(date)
                ? REPOS_QUOTIDIEN_MIN_MINEUR_MINUTES
                : REPOS_QUOTIDIEN_MIN_MAJEUR_MINUTES;
    }

    /** Rest, in minutes, between the end of {@code veille} and the start of {@code lendemain}. */
    private static int gapMinutes(PosteAffectation veille, PosteAffectation lendemain) {
        return (int) Duration.between(fin(veille), debut(lendemain)).toMinutes();
    }

    /** Monday 00:00 of the ISO week the date belongs to. */
    private static LocalDateTime debutSemaine(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay();
    }

    /**
     * Daily rest that the law lets adjoin the 24 h of weekly rest, on either
     * side of the week boundary (35 h − 24 h).
     */
    private static final int REPOS_QUOTIDIEN_ADJOINT_MINUTES = REPOS_HEBDOMADAIRE_MIN_MINUTES - 24 * 60;

    /**
     * Minutes missing, summed over the ISO weeks in which the animateur works,
     * to reach 35 consecutive hours of rest credited to each of those weeks.
     *
     * <p>A rest period is credited to a week for the part of it that lies
     * inside the week, extended by the 11 h of daily rest that art. L3132-2 lets
     * adjoin the 24 h — and never beyond its real length. Examples, for a
     * Sunday off:</p>
     * <ul>
     * <li>Saturday shift ending 20:00, Monday shift at 10:00: a 38 h rest, of
     *     which 28 h fall in the week → credited min(38, 28 + 11) = 38 h.</li>
     * <li>Saturday shift ending at midnight, Monday shift at 10:00: a 34 h
     *     rest, 24 h in the week → credited min(34, 24 + 11) = 34 h, one hour
     *     short, exactly as the law reads it.</li>
     * <li>Monday off after a Sunday shift ending 20:00, Tuesday shift at 09:00:
     *     37 h, of which 33 h fall in the new week → credited 37 h.</li>
     * </ul>
     * <p>The free time before the first assignment and after the last one has
     * no known end, so it is credited for its part inside the week plus the
     * adjoining 11 h — a week the event only partially covers is satisfied by
     * construction, as the animateur really is free on those days.</p>
     */
    static int deficitReposHebdomadaireMinutes(List<PosteAffectation> postes) {
        List<PosteAffectation> tries = postes.stream()
                .sorted(Comparator.comparing(LegalConstraints::debut))
                .toList();
        // Merged occupations: [start, end] pairs, non-overlapping, in order.
        List<LocalDateTime[]> occupations = new java.util.ArrayList<>();
        for (PosteAffectation poste : tries) {
            LocalDateTime debut = debut(poste);
            LocalDateTime fin = fin(poste);
            if (!occupations.isEmpty() && !debut.isAfter(occupations.get(occupations.size() - 1)[1])) {
                LocalDateTime[] derniere = occupations.get(occupations.size() - 1);
                if (fin.isAfter(derniere[1])) {
                    derniere[1] = fin;
                }
            } else {
                occupations.add(new LocalDateTime[] {debut, fin});
            }
        }
        java.util.TreeSet<LocalDateTime> semaines = new java.util.TreeSet<>();
        for (PosteAffectation poste : tries) {
            semaines.add(debutSemaine(poste.getCreneau().getDate()));
        }
        int deficit = 0;
        for (LocalDateTime debutSemaine : semaines) {
            LocalDateTime finSemaine = debutSemaine.plusDays(7);
            long meilleur = creditReposMinutes(null, occupations.get(0)[0], debutSemaine, finSemaine);
            for (int i = 1; i < occupations.size(); i++) {
                meilleur = Math.max(meilleur, creditReposMinutes(
                        occupations.get(i - 1)[1], occupations.get(i)[0], debutSemaine, finSemaine));
            }
            meilleur = Math.max(meilleur, creditReposMinutes(
                    occupations.get(occupations.size() - 1)[1], null, debutSemaine, finSemaine));
            if (meilleur < REPOS_HEBDOMADAIRE_MIN_MINUTES) {
                deficit += (int) (REPOS_HEBDOMADAIRE_MIN_MINUTES - meilleur);
            }
        }
        return deficit;
    }

    /**
     * Minutes of the rest period {@code [debutRepos, finRepos]} credited to the
     * week {@code [debutSemaine, finSemaine)}: its part inside the week plus the
     * adjoining daily rest, capped at its real length. A {@code null} bound
     * means the rest has no known end on that side.
     */
    private static long creditReposMinutes(LocalDateTime debutRepos, LocalDateTime finRepos,
            LocalDateTime debutSemaine, LocalDateTime finSemaine) {
        LocalDateTime debutDansSemaine = debutRepos == null || debutRepos.isBefore(debutSemaine)
                ? debutSemaine : debutRepos;
        LocalDateTime finDansSemaine = finRepos == null || finRepos.isAfter(finSemaine)
                ? finSemaine : finRepos;
        if (!finDansSemaine.isAfter(debutDansSemaine)) {
            return 0;
        }
        long dansSemaine = Duration.between(debutDansSemaine, finDansSemaine).toMinutes();
        long credit = dansSemaine + REPOS_QUOTIDIEN_ADJOINT_MINUTES;
        if (debutRepos != null && finRepos != null) {
            credit = Math.min(credit, Duration.between(debutRepos, finRepos).toMinutes());
        }
        return credit;
    }

    /**
     * Longest run of consecutive calendar days of the ISO week on which none of
     * the given dates falls — i.e. the longest stretch of days off.
     */
    private static int longestRunOfFreeDays(Set<LocalDate> joursTravailles) {
        LocalDate lundi = debutSemaine(joursTravailles.iterator().next()).toLocalDate();
        int longest = 0;
        int courante = 0;
        for (int i = 0; i < 7; i++) {
            if (joursTravailles.contains(lundi.plusDays(i))) {
                courante = 0;
            } else {
                courante++;
                longest = Math.max(longest, courante);
            }
        }
        return longest;
    }

    /** Effective working minutes of an adult's day, see {@link #effectiveWorkMinutes}. */
    private static int effectiveWorkMajeurMinutes(List<PosteAffectation> postes, ParametresLegaux parametres) {
        return effectiveWorkMinutes(postes, parametres, PAUSE_MIN_MAJEUR_MINUTES,
                PlafondsLegauxMajeurs::onPostBreakMinutes);
    }

    /** Effective working minutes of a minor's day, see {@link #effectiveWorkMinutes}. */
    private static int effectiveWorkMineurMinutes(List<PosteAffectation> postes, ParametresLegaux parametres) {
        return effectiveWorkMinutes(postes, parametres, PAUSE_MIN_MINEUR_MINUTES,
                PlafondsLegauxMineurs::onPostBreakMinutes);
    }

    /**
     * Effective working minutes of one animateur's day: the sum of the effective
     * durations of their postes, minus the legal breaks taken on the post when
     * the organiser declares them ({@link ParametresLegaux#isPauseSurPoste()}).
     *
     * <p>Each uninterrupted stretch — créneaux closer than
     * {@code pauseMinimaleMinutes} form one — contributes the breaks
     * {@code onPostBreak} says it needs ({@code PlafondsLegauxMajeurs} /
     * {@code PlafondsLegauxMineurs#onPostBreakMinutes}). Those breaks are real
     * rest, not <i>travail effectif</i> (art. L3121-1), hence deducted: an
     * adult's 14:00-24:00 amplitude is 9 h 40 of work, a 13:00-24:00 one 10 h 40
     * — still over the 10 h cap.</p>
     *
     * <p>Without the declaration nothing is deducted: an amplitude is then read
     * as worked in full, the protective reading.</p>
     */
    private static int effectiveWorkMinutes(List<PosteAffectation> postes, ParametresLegaux parametres,
            int pauseMinimaleMinutes, java.util.function.IntUnaryOperator onPostBreak) {
        int total = postes.stream().mapToInt(PosteAffectation::getDureeEffectiveMinutes).sum();
        if (!parametres.isPauseSurPoste()) {
            return total;
        }
        int pauses = 0;
        for (int sequence : sequencesMinutes(postes, pauseMinimaleMinutes)) {
            pauses += onPostBreak.applyAsInt(sequence);
        }
        return total - pauses;
    }

    /**
     * Longest uninterrupted working stretch, in minutes, once créneaux separated
     * by less than {@code pauseMinimaleMinutes} are merged (a gap shorter than
     * the legal break is not a break, so the work either side of it is one
     * stretch). A merged stretch is measured from its first start to its last
     * end, i.e. the sub-legal gaps count as worked time.
     */
    private static int longestSequenceMinutes(List<PosteAffectation> postes, int pauseMinimaleMinutes) {
        int longest = 0;
        for (int sequence : sequencesMinutes(postes, pauseMinimaleMinutes)) {
            longest = Math.max(longest, sequence);
        }
        return longest;
    }

    /**
     * Lengths, in minutes, of the uninterrupted working stretches of the given
     * postes, in chronological order — the decomposition behind
     * {@link #longestSequenceMinutes(List, int)}.
     */
    private static List<Integer> sequencesMinutes(List<PosteAffectation> postes, int pauseMinimaleMinutes) {
        List<PosteAffectation> tries = postes.stream()
                .sorted(Comparator.comparing(LegalConstraints::debut))
                .toList();
        List<Integer> sequences = new java.util.ArrayList<>();
        LocalDateTime debutSequence = null;
        LocalDateTime finSequence = null;
        for (PosteAffectation poste : tries) {
            LocalDateTime debut = debut(poste);
            LocalDateTime fin = fin(poste);
            if (debutSequence == null) {
                debutSequence = debut;
                finSequence = fin;
            } else if (Duration.between(finSequence, debut).toMinutes() >= pauseMinimaleMinutes) {
                sequences.add((int) Duration.between(debutSequence, finSequence).toMinutes());
                debutSequence = debut;
                finSequence = fin;
            } else if (fin.isAfter(finSequence)) {
                finSequence = fin;
            }
        }
        if (debutSequence != null) {
            sequences.add((int) Duration.between(debutSequence, finSequence).toMinutes());
        }
        return sequences;
    }

    /**
     * Weekly working-time cap for adult animateurs (all paid, manager or not).
     *
     * <p>Code du travail art. L3121-20 : <i>« Au cours d'une même semaine, la
     * durée maximale hebdomadaire de travail est de quarante-huit heures. »</i>
     * — disposition d'ordre public. Convention collective ÉCLAT (IDCC 1518)
     * art. 5.2 retains the same 48 h high-week ceiling
     * <b>[non vérifié — à faire validate sur le text conventionnel]</b>. The
     * effective value is the admin-configurable
     * {@link ParametresLegaux#getDureeHebdomadaireMaxMinutes()}, which the
     * server refuses to set above 48 h.</p>
     *
     * <p>Restricted to adults: minors are capped at 35 h by art. L3162-1, see
     * {@link #dureeHebdomadaireMaxMineur}. Before that split, a minor was
     * allowed 48 h/week by this application, 13 h above their ordre public
     * maximum.</p>
     *
     * <p>Week attribution convention: a créneau is attributed in full to the
     * ISO week of its start date, see {@code Creneau.semaineIso()}.</p>
     */
    private Constraint dureeHebdomadaireMax(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class), "dureeHebdomadaireMax")
                .filter(poste -> poste.getAnimateur() != null && poste.getCreneau() != null
                        && poste.getAnimateur().isMajeurOn(poste.getCreneau().getDate()))
                .groupBy(PosteAffectation::getAnimateur,
                        poste -> poste.getCreneau().semaineIso(),
                        ConstraintCollectors.sum(PosteAffectation::getDureeEffectiveMinutes))
                .join(ParametresLegaux.class)
                .filter((animateur, semaine, dureeTotale, parametres) ->
                        dureeTotale > parametres.getDureeHebdomadaireMaxMinutes())
                .penalize(HardMediumSoftScore.ONE_HARD,
                        (animateur, semaine, dureeTotale, parametres) ->
                                dureeTotale - parametres.getDureeHebdomadaireMaxMinutes())
                .asConstraint("dureeHebdomadaireMax");
    }

    /**
     * Weekly working-time cap for minors: 35 h.
     *
     * <p>Code du travail art. L3162-1 : <i>« Les jeunes travailleurs ne peuvent
     * être employés à un travail effectif excédant huit heures par jour et
     * trente-cinq heures par semaine. »</i> For the 14-to-under-16 bracket
     * employed during school holidays, art. D4153-3 sets the same weekly
     * figure (<i>« La durée du travail du mineur ne peut excéder trente-cinq
     * heures par semaine ni sept heures par jour »</i>).</p>
     *
     * <p>The daily half of L3162-1 lives in {@link #dureeQuotidienneMaxMineur}.
     * The 8 h/day cap alone does not bound the week: 8 h × 6 days = 48 h, which
     * this application used to allow.</p>
     */
    private Constraint dureeHebdomadaireMaxMineur(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class),
                "dureeHebdomadaireMaxMineur")
                .filter(poste -> poste.getAnimateur() != null && poste.getCreneau() != null
                        && poste.getAnimateur().isMineurOn(poste.getCreneau().getDate()))
                .groupBy(PosteAffectation::getAnimateur,
                        poste -> poste.getCreneau().semaineIso(),
                        ConstraintCollectors.sum(PosteAffectation::getDureeEffectiveMinutes))
                .join(ParametresLegaux.class)
                .filter((animateur, semaine, dureeTotale, parametres) ->
                        dureeTotale > parametres.getDureeHebdomadaireMaxMineurMinutes())
                .penalize(HardMediumSoftScore.ONE_HARD,
                        (animateur, semaine, dureeTotale, parametres) ->
                                dureeTotale - parametres.getDureeHebdomadaireMaxMineurMinutes())
                .asConstraint("dureeHebdomadaireMaxMineur");
    }

    /**
     * Hard, all animateurs: with découpage automatic, one animateur can hold
     * several postes the same day (rotating seat-tracks, or a deliberate split
     * shift). Whatever the gap between two same-day, non-overlapping vacations,
     * it must be at least {@link ParametresLegaux#getPauseMinimaleEntreVacationsMinutes()}
     * — otherwise chaining vacations back to back would silently reconstitute
     * an unbroken working day, defeating the whole point of the découpage.
     *
     * <p>Joined on {@code animateur} AND {@code date} (a real double hash-equal
     * join, not a Java filter) — see {@link AffectationConstraints#pasDeChevauchementHoraire}
     * for why a cheap join matters here: an unindexed same-animateur scan
     * across every poste measurably slowed convergence on
     * {@code scenario-complet.yaml}.</p>
     */
    private Constraint pauseMinimaleEntreVacations(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEachUniquePair(
                PosteAffectation.class,
                Joiners.equal(PosteAffectation::getAnimateur),
                Joiners.equal(poste -> poste.getCreneau().getDate())), "pauseMinimaleEntreVacations")
                .filter((posteA, posteB) -> posteA.getAnimateur() != null)
                .join(ParametresLegaux.class)
                .filter((posteA, posteB, parametres) ->
                        symmetricGapMinutes(posteA, posteB) < parametres.getPauseMinimaleEntreVacationsMinutes())
                .penalize(HardMediumSoftScore.ONE_HARD,
                        (posteA, posteB, parametres) -> parametres.getPauseMinimaleEntreVacationsMinutes()
                                - symmetricGapMinutes(posteA, posteB))
                .asConstraint("pauseMinimaleEntreVacations");
    }

    /**
     * Gap in minutes between the effective windows of the two postes,
     * whichever comes first — i.e. {@code max(end(a) -> start(b), end(b) ->
     * start(a))}, exactly one of which is meaningful for a non-overlapping
     * pair (the other is negative). Unlike {@link #gapMinutes(PosteAffectation,
     * PosteAffectation)}, the pair here is unordered ({@code forEachUniquePair}),
     * hence the symmetric max instead of a fixed veille→lendemain direction.
     */
    private static int symmetricGapMinutes(PosteAffectation a, PosteAffectation b) {
        long gapAfterA = Duration.between(fin(a), debut(b)).toMinutes();
        long gapAfterB = Duration.between(fin(b), debut(a)).toMinutes();
        return (int) Math.max(gapAfterA, gapAfterB);
    }
}
