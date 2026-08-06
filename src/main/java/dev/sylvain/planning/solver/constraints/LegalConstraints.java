package dev.sylvain.planning.solver.constraints;

import java.time.LocalDate;
import java.time.LocalTime;

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

    public Constraint[] define(ConstraintFactory constraintFactory) {
        return new Constraint[] {
                standReserveAuxMajeurs(constraintFactory),
                mineurNecessiteEncadrementMajeur(constraintFactory),
                travailDeNuitInterditPourMineur(constraintFactory),
                dureeQuotidienneMaxMineur(constraintFactory),
                reposQuotidienMineur(constraintFactory),
                dureeHebdomadaireMax(constraintFactory),
                dureeHebdomadaireMaxMineur(constraintFactory)
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
     * A minor working a night slot must get at least ~12h of rest, so a night
     * slot on day J forbids an early (before noon) slot on day J+1.
     *
     * <p>The night slot is selected first and the next day is reached through an
     * indexed joiner, rather than pairing every poste an animateur holds and
     * testing the pair. The previous formulation built ~13 500 pair tuples on
     * {@code scenario-complet.yaml} (150 animateurs × ~14 postes each) and
     * discarded virtually all of them; here the left side is restricted to the
     * handful of night slots held by minors before any pairing happens. Each
     * qualifying (night, next-morning) pair still yields exactly one match,
     * since the day joiner only ever generates it in that order.</p>
     */
    private Constraint reposQuotidienMineur(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class), "reposQuotidienMineur")
                .filter(poste -> poste.getCreneau() != null
                        && poste.getCreneau().chevaucheNuit()
                        && poste.getAnimateur().estMineurLe(poste.getCreneau().getDate()))
                .join(PosteAffectation.class,
                        Joiners.equal(PosteAffectation::getAnimateur),
                        Joiners.equal(soir -> soir.getCreneau().getJour() + 1,
                                lendemain -> lendemain.getCreneau().getJour()))
                .filter((soir, lendemain) -> lendemain.getCreneau().getHeureDebut() != null
                        && lendemain.getCreneau().getHeureDebut().isBefore(LocalTime.NOON))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("reposQuotidienMineur");
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
