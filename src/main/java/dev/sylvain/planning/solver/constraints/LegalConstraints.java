package dev.sylvain.planning.solver.constraints;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.Joiners;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PosteAffectation;

/**
 * Legal / safety hard constraints. None of these may ever be violated in a
 * valid plan.
 *
 * <p>Every rule here is anchored on a specific article of the French
 * <b>Code du travail</b> — each method's javadoc cites its own. The previously
 * documented source ("cahier des charges 4.1 and 4.2") pointed at an empty
 * file and is no longer referenced. Summary of the applicable texts:</p>
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
 * créneau's date, never stored — see {@code Animateur.estMineurLe} /
 * {@code Animateur.estMoinsDe16AnsLe}.</p>
 *
 * <p>{@link #mineurNecessiteEncadrementMajeur} is the one rule here with
 * <b>no legal basis identified</b>: it is an organiser safety policy, kept
 * hard by choice and catalogued under "Sécurité (mineurs)".</p>
 */
public final class LegalConstraints {

    /** Art. L3162-1: 8 h/day for a young worker aged 16 to 18. */
    private static final int DUREE_QUOTIDIENNE_MAX_MINEUR_MINUTES = 8 * 60;

    /** Art. D4153-3: 7 h/day for a minor aged 14 to under 16 (school holidays). */
    private static final int DUREE_QUOTIDIENNE_MAX_MOINS_DE_16_ANS_MINUTES = 7 * 60;

    /** Art. L3121-18: 10 h/day of travail effectif for an adult. */
    private static final int DUREE_QUOTIDIENNE_MAX_MAJEUR_MINUTES = 10 * 60;

    /** Art. L3131-1: 11 consecutive hours of daily rest for an adult. */
    private static final int REPOS_QUOTIDIEN_MIN_MAJEUR_MINUTES = 11 * 60;

    /** Art. L3164-1: 12 consecutive hours of daily rest for a young worker. */
    private static final int REPOS_QUOTIDIEN_MIN_MINEUR_MINUTES = 12 * 60;

    /** Art. L3164-1: 14 consecutive hours of daily rest under 16. */
    private static final int REPOS_QUOTIDIEN_MIN_MOINS_DE_16_ANS_MINUTES = 14 * 60;

    /** Art. L3121-16: an adult's uninterrupted work may not exceed 6 h. */
    private static final int TRAVAIL_CONTINU_MAX_MAJEUR_MINUTES = 6 * 60;

    /** Art. L3121-16: the break that interrupts an adult's working stretch lasts at least 20 min. */
    private static final int PAUSE_MIN_MAJEUR_MINUTES = 20;

    /** Art. L3162-3: a young worker's uninterrupted work may not exceed 4 h 30. */
    private static final int TRAVAIL_CONTINU_MAX_MINEUR_MINUTES = 4 * 60 + 30;

    /** Art. L3162-3: the break that interrupts a young worker's stretch lasts at least 30 min. */
    private static final int PAUSE_MIN_MINEUR_MINUTES = 30;

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
                travailContinuMaxMineur(constraintFactory)
        };
    }

    /**
     * No minor on a stand flagged {@code reserveMajeurs}.
     *
     * <p>{@code reserveMajeurs} is a <b>business flag</b>, not in itself a
     * legal statement: the restriction may come from the "travaux réglementés"
     * forbidden to under-18s (art. L4153-8, D4153-15 et suivants
     * <b>[non vérifié — à faire valider]</b>) or from a purely internal policy
     * (bar stand, adult-only games…). The model does not distinguish the two;
     * see docs/contraintes.md.</p>
     */
    private Constraint standReserveAuxMajeurs(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class), "standReserveAuxMajeurs")
                .filter(poste -> poste.getAnimateur() != null
                        && poste.getStand().isReserveMajeurs()
                        && poste.getAnimateur().estMineurLe(poste.getCreneau().getDate()))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("standReserveAuxMajeurs");
    }

    /**
     * At least one adult on the same stand and slot as a minor.
     *
     * <p><b>No article of the Code du travail was identified</b> that imposes
     * this. Supervision duties for minors come either from the Code de l'action
     * sociale et des familles (where minors are the audience, not the staff) or
     * from the derogation regime for "travaux réglementés" (art. R4153-40 et
     * suivants <b>[non vérifié — à faire valider]</b>). This is therefore an
     * organiser <b>safety policy</b>, deliberately kept as a hard constraint
     * but catalogued under "Sécurité (mineurs)", not "Légal (mineurs)", so
     * nobody disables it believing the whole minors' framework is optional.</p>
     */
    private Constraint mineurNecessiteEncadrementMajeur(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class),
                "mineurNecessiteEncadrementMajeur")
                .filter(poste -> poste.getAnimateur() != null
                        && poste.getAnimateur().estMineurLe(poste.getCreneau().getDate()))
                .ifNotExists(PosteAffectation.class,
                        Joiners.equal(PosteAffectation::getStand),
                        Joiners.equal(PosteAffectation::getCreneau),
                        Joiners.filtering((posteMineur, autrePoste) -> autrePoste.getAnimateur() != null
                                && autrePoste.getAnimateur().estMajeurLe(autrePoste.getCreneau().getDate())))
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
     * festival evening.</p>
     */
    private Constraint travailDeNuitInterditPourMineur(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class),
                "travailDeNuitInterditPourMineur")
                .filter(poste -> poste.getAnimateur() != null
                        && poste.getCreneau() != null
                        && poste.getAnimateur().estMineurLe(poste.getCreneau().getDate())
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
                        && poste.getAnimateur().estMineurLe(poste.getCreneau().getDate()))
                .groupBy(PosteAffectation::getAnimateur,
                        poste -> poste.getCreneau().getDate(),
                        ConstraintCollectors.sum(poste -> poste.getCreneau().getDureeMinutes()))
                .filter((animateur, date, dureeTotale) -> dureeTotale > plafondQuotidienMineur(animateur, date))
                .penalize(HardMediumSoftScore.ONE_HARD,
                        (animateur, date, dureeTotale) -> dureeTotale - plafondQuotidienMineur(animateur, date))
                .asConstraint("dureeQuotidienneMaxMineur");
    }

    /** Night window start applicable to this minor on this date (art. L3163-1). */
    private static LocalTime debutNuit(Animateur animateur, LocalDate date) {
        return animateur.estMoinsDe16AnsLe(date)
                ? Creneau.DEBUT_NUIT_MOINS_DE_16_ANS
                : Creneau.DEBUT_NUIT_16_A_18_ANS;
    }

    /** Daily working-time cap applicable to this minor on this date (art. L3162-1 / D4153-3). */
    private static int plafondQuotidienMineur(Animateur animateur, LocalDate date) {
        return animateur.estMoinsDe16AnsLe(date)
                ? DUREE_QUOTIDIENNE_MAX_MOINS_DE_16_ANS_MINUTES
                : DUREE_QUOTIDIENNE_MAX_MINEUR_MINUTES;
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
                        && ecartMinutes(veille, lendemain) < reposQuotidienMinimal(veille))
                .penalize(HardMediumSoftScore.ONE_HARD,
                        (veille, lendemain) -> reposQuotidienMinimal(veille) - ecartMinutes(veille, lendemain))
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
     * day <b>[non vérifié — à faire valider sur le texte conventionnel]</b>.</p>
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
                        && poste.getAnimateur().estMajeurLe(poste.getCreneau().getDate()))
                .groupBy(PosteAffectation::getAnimateur,
                        poste -> poste.getCreneau().getDate(),
                        ConstraintCollectors.sum(poste -> poste.getCreneau().getDureeMinutes()))
                .filter((animateur, date, dureeTotale) -> dureeTotale > DUREE_QUOTIDIENNE_MAX_MAJEUR_MINUTES)
                .penalize(HardMediumSoftScore.ONE_HARD,
                        (animateur, date, dureeTotale) -> dureeTotale - DUREE_QUOTIDIENNE_MAX_MAJEUR_MINUTES)
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
     * non-compliant — is defensible and <b>[à faire valider par un juriste]</b>.
     * Switching to it means comparing with {@code >=} here.</p>
     *
     * <p>The model has no break inside a créneau (see {@code docs/domaine.md}):
     * a break is the <i>gap between two créneaux of the same animateur</i>.
     * Consecutive créneaux separated by less than 20 minutes are therefore
     * merged into a single stretch, whose length is measured from the first
     * start to the last end — counting the sub-legal gaps as worked time, which
     * is the protective reading.</p>
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
                        && poste.getAnimateur().estMajeurLe(poste.getCreneau().getDate()))
                .groupBy(PosteAffectation::getAnimateur,
                        poste -> poste.getCreneau().getDate(),
                        ConstraintCollectors.toList())
                .filter((animateur, date, postes) -> plusLongueSequenceMinutes(postes, PAUSE_MIN_MAJEUR_MINUTES)
                        > TRAVAIL_CONTINU_MAX_MAJEUR_MINUTES)
                .penalize(HardMediumSoftScore.ONE_HARD,
                        (animateur, date, postes) -> plusLongueSequenceMinutes(postes, PAUSE_MIN_MAJEUR_MINUTES)
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
                        && poste.getAnimateur().estMineurLe(poste.getCreneau().getDate()))
                .groupBy(PosteAffectation::getAnimateur,
                        poste -> poste.getCreneau().getDate(),
                        ConstraintCollectors.toList())
                .filter((animateur, date, postes) -> plusLongueSequenceMinutes(postes, PAUSE_MIN_MINEUR_MINUTES)
                        > TRAVAIL_CONTINU_MAX_MINEUR_MINUTES)
                .penalize(HardMediumSoftScore.ONE_HARD,
                        (animateur, date, postes) -> plusLongueSequenceMinutes(postes, PAUSE_MIN_MINEUR_MINUTES)
                                - TRAVAIL_CONTINU_MAX_MINEUR_MINUTES)
                .asConstraint("travailContinuMaxMineur");
    }

    /* ------------------------------ helpers ------------------------------- */

    private static boolean horaireConnu(PosteAffectation poste) {
        return poste.getCreneau() != null
                && poste.getCreneau().getDate() != null
                && poste.getCreneau().getHeureDebut() != null;
    }

    private static LocalDateTime debut(PosteAffectation poste) {
        return LocalDateTime.of(poste.getCreneau().getDate(), poste.getCreneau().getHeureDebut());
    }

    /** End instant, derived from the duration so a slot crossing midnight ends the next day. */
    private static LocalDateTime fin(PosteAffectation poste) {
        return debut(poste).plusMinutes(poste.getCreneau().getDureeMinutes());
    }

    /** Minimum consecutive daily rest applicable to this animateur (art. L3131-1 / L3164-1). */
    private static int reposQuotidienMinimal(PosteAffectation poste) {
        Animateur animateur = poste.getAnimateur();
        LocalDate date = poste.getCreneau().getDate();
        if (animateur.estMoinsDe16AnsLe(date)) {
            return REPOS_QUOTIDIEN_MIN_MOINS_DE_16_ANS_MINUTES;
        }
        return animateur.estMineurLe(date)
                ? REPOS_QUOTIDIEN_MIN_MINEUR_MINUTES
                : REPOS_QUOTIDIEN_MIN_MAJEUR_MINUTES;
    }

    /** Rest, in minutes, between the end of {@code veille} and the start of {@code lendemain}. */
    private static int ecartMinutes(PosteAffectation veille, PosteAffectation lendemain) {
        return (int) Duration.between(fin(veille), debut(lendemain)).toMinutes();
    }

    /**
     * Longest uninterrupted working stretch, in minutes, once créneaux separated
     * by less than {@code pauseMinimaleMinutes} are merged (a gap shorter than
     * the legal break is not a break, so the work either side of it is one
     * stretch). A merged stretch is measured from its first start to its last
     * end, i.e. the sub-legal gaps count as worked time.
     */
    private static int plusLongueSequenceMinutes(List<PosteAffectation> postes, int pauseMinimaleMinutes) {
        List<PosteAffectation> tries = postes.stream()
                .sorted(Comparator.comparing(LegalConstraints::debut))
                .toList();
        int plusLongue = 0;
        LocalDateTime debutSequence = null;
        LocalDateTime finSequence = null;
        for (PosteAffectation poste : tries) {
            LocalDateTime debut = debut(poste);
            LocalDateTime fin = fin(poste);
            if (debutSequence == null) {
                debutSequence = debut;
                finSequence = fin;
            } else if (Duration.between(finSequence, debut).toMinutes() >= pauseMinimaleMinutes) {
                plusLongue = Math.max(plusLongue, (int) Duration.between(debutSequence, finSequence).toMinutes());
                debutSequence = debut;
                finSequence = fin;
            } else if (fin.isAfter(finSequence)) {
                finSequence = fin;
            }
        }
        if (debutSequence != null) {
            plusLongue = Math.max(plusLongue, (int) Duration.between(debutSequence, finSequence).toMinutes());
        }
        return plusLongue;
    }

    /**
     * Weekly working-time cap for adult animateurs (all paid, manager or not).
     *
     * <p>Code du travail art. L3121-20 : <i>« Au cours d'une même semaine, la
     * durée maximale hebdomadaire de travail est de quarante-huit heures. »</i>
     * — disposition d'ordre public. Convention collective ÉCLAT (IDCC 1518)
     * art. 5.2 retains the same 48 h high-week ceiling
     * <b>[non vérifié — à faire valider sur le texte conventionnel]</b>. The
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
                        && poste.getAnimateur().estMajeurLe(poste.getCreneau().getDate()))
                .groupBy(PosteAffectation::getAnimateur,
                        poste -> poste.getCreneau().semaineIso(),
                        ConstraintCollectors.sum(poste -> poste.getCreneau().getDureeMinutes()))
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
                        && poste.getAnimateur().estMineurLe(poste.getCreneau().getDate()))
                .groupBy(PosteAffectation::getAnimateur,
                        poste -> poste.getCreneau().semaineIso(),
                        ConstraintCollectors.sum(poste -> poste.getCreneau().getDureeMinutes()))
                .join(ParametresLegaux.class)
                .filter((animateur, semaine, dureeTotale, parametres) ->
                        dureeTotale > parametres.getDureeHebdomadaireMaxMineurMinutes())
                .penalize(HardMediumSoftScore.ONE_HARD,
                        (animateur, semaine, dureeTotale, parametres) ->
                                dureeTotale - parametres.getDureeHebdomadaireMaxMineurMinutes())
                .asConstraint("dureeHebdomadaireMaxMineur");
    }
}
