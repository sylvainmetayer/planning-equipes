package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.FenetreRepas;
import dev.sylvain.planning.domain.JoursFeries;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PlafondsLegauxMajeurs;
import dev.sylvain.planning.domain.PlafondsLegauxMineurs;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.referentiel.TypologieItem;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * How many animateurs the current stands/créneaux need at a minimum, computed
 * from the very seats a solve would have to fill — the
 * {@link PosteAffectation} list of
 * {@code PlanningService#buildSeatsFromReferenceData()} — and not from a
 * stands × créneaux product.
 *
 * <p>That distinction is the whole point of this class. The estimate used to
 * be computed in the browser from {@code effectifMin} summed over the stands
 * "open" on each créneau, which silently broke on any scenario using the two
 * mechanisms this application is built around:</p>
 *
 * <ul>
 * <li><b>Recurring horaires.</b> The browser only knew about dated
 * {@code ouvertures}/{@code indisponibilites}, so a stand describing its
 * schedule as rules (the normal case since the horaires feature) counted as
 * open on every single créneau, around the clock.</li>
 * <li><b>Auto-découpage.</b> When the active group holds generated vacations,
 * consecutive vacations <em>overlap</em> during handovers. Summing a stand's
 * effectif over every such créneau counts the handover minutes twice; on a
 * real 16-day edition the workload bound came out far above the roster.</li>
 * </ul>
 *
 * <p>Working from the generated postes removes both errors by construction:
 * poste generation already resolved the horaires and already halved the
 * headcount of a meal-pause
 * coverage vacation. Whatever the seats are, they are what has to be staffed.</p>
 *
 * <p>The hour-based bounds divide by <b>planned amplitude</b> and never deduct
 * a break taken on the post, where {@code dureeHebdomadaireMax} does. That
 * makes each animateur look like they can carry slightly fewer hours than the
 * cap really allows, so the bound comes out <em>higher</em> — pessimistic, and
 * a floor that is too high is never a floor that lets an infeasible edition
 * through. See {@code docs/contraintes.md}, « Ce qui déduit la pause, et ce
 * qui compte l'amplitude ».</p>
 *
 * <h2>The six bounds</h2>
 *
 * <p>Six bounds are computed and the largest wins. Each one is a
 * <em>proof</em> that no smaller number of people can cover the seats, derived
 * from a rule {@code LegalConstraints} really enforces — so the floor and the
 * solver can never contradict each other:</p>
 * <ol>
 * <li><b>Pic simultané</b> — the largest number of seats open at the same
 * instant. A floor by definition: nobody holds two seats at once.</li>
 * <li><b>Pic avec tampon de pause</b> — the same peak, over intervals each
 * extended by a buffer between two vacations. Two vacations can be held by the
 * same person only if their extended intervals don't overlap, so this maximum
 * overlap is <em>exactly</em> the minimum number of distinct animateurs a day
 * requires — the chromatic number of an interval graph is its maximum clique.
 * The buffer used to be the « pause minimale entre vacations », a rule of its
 * own; that rule is retired (ADR 0048) — a gap shorter than the legal break is
 * worked time, not a forbidden one — so the buffer is zero and this bound now
 * coincides with the simultaneous peak, day for day. It is still computed and
 * still shown, because the proof above is written on it; what it can no longer
 * be is the bound <em>credited</em> in {@link BorneRetenue}, which would name a
 * constraint that no longer constrains.</li>
 * <li><b>Charge horaire</b> — the hours of the <em>busiest ISO week</em>,
 * divided by the amplitude one animateur may legally cover during that week.
 * The legal ceilings bound <em>travail effectif</em>, and what a grid asks for
 * is amplitude, so the ceilings are raised here by the breaks a person takes
 * under them (ADR 0048) — see {@link #maxDailyAmplitude(int)}. Dividing an
 * amplitude by a travail-effectif ceiling claimed people the plan does not
 * need, which for a bound announced as a proof is the one error that
 * matters.</li>
 * <li><b>Rotation sur les jours</b> — the person-days of the busiest ISO week,
 * divided by the number of days one animateur may work in it (art. L3132-1:
 * six).</li>
 * <li><b>Coupure repas</b> — what the meal windows force, see
 * {@link #picRepas(List, FenetreRepas)} for the proof. A day whose grid leaves
 * no room to eat cannot be staffed by the people its peak alone suggests:
 * whoever works either side of the window has to step out of it, and somebody
 * else holds the seat meanwhile.</li>
 * <li><b>Enchaînement des jours</b> — the exact cover of the day sequence by
 * {@link DaySequenceFloor}: the weekly six days and, when the edition holds
 * {@code maxJoursConsecutifsTravaillesDur}, the cap on days in a row, solved
 * together as a maximum flow over everybody's rest days. It can only confirm
 * or raise the rotation bound, which divides each week on its own and cannot
 * see a run of worked days straddling two of them.</li>
 * </ol>
 *
 * <h3>Why the last two are computed week by week</h3>
 *
 * <p>Both used to be computed over the whole event at once, against a capacity
 * of {@code nombreSemaines × 48 h}. That aggregate is not a bound on anything:
 * the hours of week 28 can only be covered by people working in week 28, and
 * spare capacity in a week the event barely touches does not staff another
 * one. On that same edition the last ISO week holds two days — capacity for
 * 20 h of work per person, not 48 — yet the global division handed it a third
 * of the total capacity and pulled the bound down. Taking the largest week
 * instead is both correct and strictly tighter.</p>
 *
 * <p>A week's capacity per animateur is likewise capped twice: by the weekly
 * ceiling, and by the days the event actually occupies in that week — at most
 * six of them (art. L3132-1), each of at most
 * {@link PlafondsLegauxMajeurs#DUREE_QUOTIDIENNE_MAX_MINUTES} (art. L3121-18).
 * A two-day week cannot yield 48 h of work per person however much the weekly
 * ceiling allows.</p>
 *
 * <p>The <b>rotation</b> bound is the one the previous version was missing
 * outright, and it is often the binding one on a long event: a day needing 100
 * distinct animateurs, repeated seven days running, cannot be staffed by 100
 * people, because none of them may work all seven days. Counting
 * {@code (animateur, jour travaillé)} pairs makes that rigorous — the week
 * needs {@code Σ besoin(jour)} such pairs, each animateur supplies at most six
 * — and it is what raised that edition's floor from 102 to 117 for an event
 * really staffed by 153.</p>
 *
 * <p>The daily {@code besoin} those pairs are counted from is itself the larger
 * of the pause-adjusted peak and of the day's hours divided by the daily
 * ceiling: a day holding 600 person-hours needs at least sixty people whatever
 * its peak looks like.</p>
 *
 * <p>All six stay optimistic: none of them accounts for competences, for the
 * daily-rest constraint, or for the fact that a real plan spreads work far
 * below the legal ceilings. They are a recruitment floor to exceed, never a
 * target — the exact answer only comes from a real solve, which
 * {@code StaffingVerificationService} runs on a made-up team of that size.</p>
 *
 * <h2>Adults and minors</h2>
 *
 * <p>{@link StaffingSummary#majeursMin()} is the most adults one day needs:
 * all of its people on a public holiday, otherwise the peak of the seats no
 * minor may hold. {@link StaffingSummary#mineursMax()} is a <em>ceiling</em>
 * on minors for the {@link StaffingSummary#effectifReference() reference
 * team}, from three necessary conditions — see {@link #mineursMax}. It replaced
 * a share « half the seats of each stand », which read a medium rule as if it
 * were the law and ignored the holidays and the minors' weekly rest.</p>
 *
 * <h2>Unavailability the team can absorb</h2>
 *
 * <p>Each day says how many of the reference team may be away
 * ({@link JourStaffing#absentsMax()}), each week how many person-days of
 * unavailability fit beyond the rest day a full week owes everybody anyway
 * ({@link SemaineStaffing#budgetIndisponibilites()}). A day off taken on that
 * rest day costs nothing, which is why the budget is not simply the slack of
 * every day added up.</p>
 *
 * <h2>Effectif projeté sur les indisponibilités déclarées</h2>
 *
 * <p>The bounds above assume everybody is available every day, which no
 * referential ever satisfies: a day needing 100 people out of a pool only 70 %
 * of which is free that day really needs a pool of 143.
 * {@link StaffingSummary#minimumAvecIndisponibilites()} applies exactly that
 * correction, day by day, from the {@code joursIndisponibles} the animateurs
 * have declared — the same data the solver reads. It is a
 * <em>projection</em>, not a bound: it assumes the people yet to be recruited
 * will be unavailable as often as the ones already known. With nothing
 * declared it equals {@code minimumTotal}, which is the honest answer — an
 * unstated availability cannot be projected.</p>
 *
 * <h2>Bottleneck per game category</h2>
 *
 * <p>The bounds above are global, so they answer "how many animateurs"
 * and never "how many of which kind" — yet a plan that misses four people
 * almost always misses four people <em>competent on one game category</em>.
 * {@link CompetenceStaffing} replays the very same bounds on the seats of a
 * single category and puts them against the animateurs who can hold them. Two
 * attribution rules make that comparison sound rather than merely plausible:</p>
 *
 * <ul>
 * <li><b>Demand is attributed exclusively.</b> A seat counts for category
 * {@code T} only when its stand proposes {@code T} and nothing else — then,
 * and only then, is {@code T} provably required to staff it. The seats of a
 * stand proposing several categories can be covered from either pool, so no
 * single category can claim them; they are reported apart, as
 * {@link CompetenceStaffing#siegesNonAttribues()}. Splitting them over every
 * category of their stand would count the same seat several times and invent
 * bottlenecks. A stand proposing <b>no</b> category is the opposite case, not
 * the same one: {@link Animateur#hasCompetenceFor(Stand)} then answers yes to
 * polyvalents only, so its seats are the tightest demand there is. They join
 * the ninja row — the very same required population — and are counted in
 * {@link CompetenceStaffing#siegesReservesAuxPolyvalents()}; with no ninja
 * category in the referential, nobody can hold them at all.</li>
 * <li><b>A ninja is a reinforcement, never a specialist.</b>
 * {@link Animateur#hasCompetenceFor(Stand)} lets a polyvalent take any stand,
 * so counting them as available in every category would add the same person to
 * every row — inflating each pool and hiding the very bottleneck this exists
 * to show. A pool therefore holds the {@code specialistes} of a category: the
 * animateurs who declared it (the ninja category included, since on the demand
 * side it is a category like any other). The polyvalents are reported once
 * more, apart, as a shared reserve — {@link CompetenceStaffing#polyvalents()},
 * dispatchable anywhere but each on one seat at a time. A
 * {@link CompetenceStaffing#manqueTotal()} larger than that reserve is a
 * signal, not a proof: two categories peaking at different hours can be served
 * by the same polyvalent. The ninja row's own shortfall is excluded from that
 * reading — {@link CompetenceStaffing#manquePolyvalents()} — because its pool
 * <em>is</em> the reserve: offering the reinforcements against their own
 * shortage would promise an absorption nobody can deliver. This is the reading
 * the decision record "le ninja est un renfort, jamais un spécialiste" settles
 * for the neighbouring fragilité screen, applied to the same question —
 * measuring the rarity of a competence never counts the polyvalents in.</li>
 * </ul>
 *
 * <p>Both approximations this leaves point the same way — demand understated
 * for a stand proposing several categories, supply overstated for an animateur
 * competent on several — so a flagged bottleneck is a real one, while the
 * absence of one proves nothing. That is the same optimism as the bounds
 * above.</p>
 */
@ApplicationScoped
public class StaffingAnalyzer {

    /**
     * The largest <b>amplitude</b> one adult may hold on one day: the ten hours
     * of travail effectif art. L3121-18 allows, plus the break that day owes
     * (ADR 0048). A 10 h 30 stretch is 10 h of work once its thirty minutes
     * come off, so dividing a day's amplitude by 600 min counted people who are
     * not needed.
     *
     * <p>The edition's own break length, never a constant. Every bound here is
     * announced as a <b>floor</b> — « il en faut au moins tant » — so it may be
     * loose but must never exceed the truth, and that fixes the direction:
     * assuming a break shorter than the edition grants under-states what one
     * person covers and therefore over-states the floor. A class constant at
     * the legal minimum of twenty minutes did exactly that on the default
     * thirty, and claimed 120 people on a day 119 can staff.</p>
     *
     * <p>One break, not more: a stretch long enough to owe a second one is well
     * past ten hours of work whatever is deducted, so no day of an adult can
     * carry two.</p>
     */
    private static int maxDailyAmplitude(int dureePauseMinutes) {
        return PlafondsLegauxMajeurs.DUREE_QUOTIDIENNE_MAX_MINUTES + dureePauseMinutes;
    }

    /** Which of the bounds ended up setting {@link StaffingSummary#minimumTotal()}. */
    public enum BorneRetenue {
        PIC_SIMULTANE,
        PIC_AVEC_PAUSE,
        CHARGE_HORAIRE,
        ROTATION_JOURS,
        COUPURE_REPAS,
        /**
         * The exact cover of the day sequence ({@link DaySequenceFloor}): the
         * weekly six days and, when the edition holds it hard, the cap on days
         * in a row, together. Credited only when strictly above every other.
         */
        ENCHAINEMENT_JOURS,
        /**
         * A game category's cap on the timeslots one animateur may hold over
         * the edition ({@code maxCreneauxParAnimateur}): its seats need at least
         * that many distinct people. Only ever retained on a category row.
         */
        PLAFOND_TYPOLOGIE
    }

    /**
     * A referential the edition has not filled in yet, named so the screen can
     * say which one is missing instead of showing a zero (issue #416). The
     * seats need the first two only: without an animateur the bounds are
     * still proven, and only the comparison against a pool is left out.
     */
    public enum ReferentielManquant {
        STANDS,
        CRENEAUX,
        ANIMATEURS
    }

    /**
     * One event day. {@code heures} are person-hours (seats × duration),
     * {@code sieges} the number of postes generated that day, and
     * {@code minimumJour} the distinct animateurs the day provably needs: the
     * pause-adjusted peak, or its hours over the daily legal ceiling when a
     * flat, long day demands more people than its peak shows.
     *
     * <p>{@code absentsMax} is how many of the {@link StaffingSummary#effectifReference()}
     * people may be away that day while the day is still held — the reference
     * team minus the day's minimum. {@code majeursRequis} is how many of the
     * day's people must be adults: all of them on a public holiday (art.
     * L3164-6), otherwise the peak of the seats a minor may not hold — those of a
     * stand reserved to adults, and those reaching into the night of a 16-17
     * year old (art. L3163-1).</p>
     */
    @Schema(
            requiredProperties = {
                "disponibles",
                "heures",
                "jour",
                "minimumJour",
                "picAvecPause",
                "picRepas",
                "picSimultane",
                "sieges",
                "standsOuverts",
                "absentsMax",
                "majeursRequis"
            })
    public record JourStaffing(
            LocalDate date,
            int jour,
            int standsOuverts,
            int sieges,
            double heures,
            int picSimultane,
            int picAvecPause,
            int picRepas,
            int minimumJour,
            int disponibles,
            int absentsMax,
            int majeursRequis) {}

    /**
     * One ISO week, the window both remaining bounds are proved inside — see
     * the class javadoc for why an event-wide aggregate proves nothing.
     *
     * @param semaine       ISO label, same format as {@code Creneau.semaineIso()}
     * @param debut         Monday of that week, so a caller can date it without
     *                      parsing the label
     * @param jours         event days the week holds
     * @param joursTravaillables how many of them one animateur may work
     *                      (art. L3132-1: six)
     * @param heures        person-hours to cover during the week
     * @param capaciteHeuresParAnimateur the amplitude one animateur may cover
     *                      that week: the weekly ceiling, capped by
     *                      {@code joursTravaillables × durée quotidienne max},
     *                      each raised by the break a day owes so a ceiling on
     *                      travail effectif is compared with the amplitude a
     *                      grid asks for (ADR 0048)
     * @param chargeTotal   {@code heures / capaciteHeuresParAnimateur}
     * @param joursPersonne sum of the days' {@code minimumJour}: the
     *                      (animateur, jour travaillé) pairs the week requires
     * @param rotationTotal {@code joursPersonne / joursTravaillables}
     * @param budgetIndisponibilites person-days of unavailability the
     *                      reference team can absorb that week beyond the rest
     *                      day a full week already owes everybody:
     *                      {@code joursTravaillables × effectifReference −
     *                      joursPersonne}. A day off that falls on the weekly
     *                      rest costs nothing; each one beyond comes out of
     *                      this budget. A necessary condition: the cap on days
     *                      in a row and the competences eat into it too
     * @param indisponibilitesAuDela the days the known animateurs declared
     *                      unavailable that week beyond that free rest day, to
     *                      compare with the budget — {@code 0} with nobody known
     */
    @Schema(
            requiredProperties = {
                "capaciteHeuresParAnimateur",
                "chargeTotal",
                "heures",
                "jours",
                "joursPersonne",
                "joursTravaillables",
                "rotationTotal",
                "budgetIndisponibilites",
                "indisponibilitesAuDela"
            })
    public record SemaineStaffing(
            String semaine,
            LocalDate debut,
            int jours,
            int joursTravaillables,
            double heures,
            double capaciteHeuresParAnimateur,
            int chargeTotal,
            int joursPersonne,
            int rotationTotal,
            int budgetIndisponibilites,
            int indisponibilitesAuDela) {

        /** The same week once the reference team is known — see the two last parameters. */
        SemaineStaffing withIndisponibilites(int budget, int auDela) {
            return new SemaineStaffing(
                    semaine,
                    debut,
                    jours,
                    joursTravaillables,
                    heures,
                    capaciteHeuresParAnimateur,
                    chargeTotal,
                    joursPersonne,
                    rotationTotal,
                    budget,
                    auDela);
        }
    }

    @Schema(
            requiredProperties = {
                "capaciteHeuresParAnimateur",
                "chargeTotal",
                "dureeHebdomadaireMaxMinutes",
                "dureeQuotidienneMaxMinutes",
                "indisponibilitesDeclarees",
                "joursTravaillesMaxParSemaine",
                "minimumAvecIndisponibilites",
                "enchainementTotal",
                "effectifReference",
                "majeursMin",
                "mineursMax",
                "minimumTotal",
                "nombreSemaines",
                "picAvecPause",
                "picRepas",
                "picSimultane",
                "referentielsManquants",
                "rotationTotal",
                "totalDemandeHeures"
            })
    public record StaffingSummary(
            List<JourStaffing> parJour,
            List<SemaineStaffing> parSemaine,
            SemaineStaffing semaineCritique,
            int picSimultane,
            int picAvecPause,
            int picRepas,
            JourStaffing jourCritique,
            double totalDemandeHeures,
            int nombreSemaines,
            double capaciteHeuresParAnimateur,
            int chargeTotal,
            int rotationTotal,
            int minimumTotal,
            BorneRetenue borneRetenue,
            int minimumAvecIndisponibilites,
            boolean indisponibilitesDeclarees,
            int enchainementTotal,
            Integer joursConsecutifsMax,
            int effectifReference,
            int majeursMin,
            int mineursMax,
            int dureeHebdomadaireMaxMinutes,
            int dureeQuotidienneMaxMinutes,
            int joursTravaillesMaxParSemaine,
            CompetenceStaffing parCompetence,
            List<ReferentielManquant> referentielsManquants) {}

    /**
     * One game category: the same bounds as {@link StaffingSummary}, computed
     * on the seats that provably require it, against its {@code specialistes}
     * — the animateurs declaring it. {@code manque} is what that pool is short
     * of: zero when the bound is met, and zero as long as no animateur is
     * known at all, since there is then nothing to compare the bound to.
     *
     * <p>{@code plafondCreneaux} is the category's {@code maxCreneauxParAnimateur},
     * {@code null} when it carries none, and {@code minimumPlafond} what it
     * forces on its own: {@code ⌈sieges ÷ plafondCreneaux⌉} distinct people.</p>
     */
    @Schema(
            requiredProperties = {
                "chargeTotal",
                "heures",
                "manque",
                "minimumTotal",
                "ninja",
                "nombreSemaines",
                "picAvecPause",
                "picRepas",
                "picSimultane",
                "rotationTotal",
                "sieges",
                "specialistes",
                "enchainementTotal",
                "minimumPlafond"
            })
    public record TypologieStaffing(
            String typologie,
            String label,
            boolean ninja,
            int sieges,
            double heures,
            int nombreSemaines,
            int picSimultane,
            int picAvecPause,
            int picRepas,
            int chargeTotal,
            int rotationTotal,
            int enchainementTotal,
            Integer plafondCreneaux,
            int minimumPlafond,
            int minimumTotal,
            BorneRetenue borneRetenue,
            int specialistes,
            int manque) {}

    /**
     * The bottleneck view: one row per game category holding seats, plus what
     * the per-category reading deliberately leaves out — see the class javadoc.
     *
     * @param parTypologie      rows, the tightest bottleneck first
     * @param polyvalents       animateurs holding the ninja category: a shared
     *                          reserve, dispatchable on any category but on one
     *                          seat at a time
     * @param siegesNonAttribues seats of stands proposing <b>several</b>
     *                          categories, which no single category can claim
     * @param siegesReservesAuxPolyvalents seats of stands proposing <b>no</b>
     *                          category — the opposite case, and the tightest
     *                          demand there is: only a polyvalent can hold one.
     *                          They are folded into the ninja row when the
     *                          referential has one, and holdable by nobody at
     *                          all when it has not
     * @param manqueTotal       sum of the {@code manque} of every row
     * @param manquePolyvalents the part of {@code manqueTotal} carried by the
     *                          ninja row itself. No reinforcement can absorb it:
     *                          that row's pool <em>is</em> the reserve
     * @param animateursTotal   animateurs known at all — {@code 0} means the
     *                          référentiel is still empty and nothing is compared
     * @param typologieNinjaDefinie whether the referential marks a ninja category
     *                          at all. Without it nobody is polyvalent, and a
     *                          reserve of zero must be read as "no such notion
     *                          here" rather than as a shortage of backup
     * @param planchersCumules  the sum of every row's minimum: the team the
     *                          seats would need if nobody held two categories.
     *                          Against {@code minimumTotal}, it says how many
     *                          categories each recruit must carry on average for
     *                          the global floor to be reachable at all
     */
    @Schema(
            requiredProperties = {
                "animateursTotal",
                "manquePolyvalents",
                "manqueTotal",
                "polyvalents",
                "siegesNonAttribues",
                "siegesReservesAuxPolyvalents",
                "typologieNinjaDefinie",
                "planchersCumules"
            })
    public record CompetenceStaffing(
            List<TypologieStaffing> parTypologie,
            int polyvalents,
            int siegesNonAttribues,
            int siegesReservesAuxPolyvalents,
            int manqueTotal,
            int manquePolyvalents,
            int animateursTotal,
            boolean typologieNinjaDefinie,
            int planchersCumules) {}

    /** Half-open interval of one seat, in minutes from the start of its day. */
    private record Siege(LocalDate date, int debut, int fin, Stand stand) {}

    /** What one event day demands, before any weekly reasoning. */
    private record BesoinJour(
            LocalDate date, double heures, int picSimultane, int picAvecPause, int picRepas, int minimum) {}

    /**
     * The edition's rules the bounds are proved under, read in one place by
     * {@link StaffingService} so REST and MCP cannot use two sets.
     *
     * @param dureeHebdomadaireMaxMinutes       adults' weekly ceiling of travail effectif
     * @param dureePauseMinutes                 the break an adult's day owes
     * @param dureeHebdomadaireMaxMineurMinutes minors' weekly ceiling, for the
     *                                          ceiling on minors only
     * @param fenetresRepas                     the meal windows the plan must
     *                                          honour, empty when the rule is off
     * @param joursConsecutifsMax               the cap on days in a row when the
     *                                          edition holds it <b>hard</b>,
     *                                          {@code null} otherwise — a medium
     *                                          rule may be broken, so it raises
     *                                          no floor
     */
    public record StaffingRules(
            int dureeHebdomadaireMaxMinutes,
            int dureePauseMinutes,
            int dureeHebdomadaireMaxMineurMinutes,
            List<FenetreRepas> fenetresRepas,
            Integer joursConsecutifsMax) {

        public StaffingRules {
            fenetresRepas = fenetresRepas == null ? List.of() : List.copyOf(fenetresRepas);
        }

        /** The rules of the older entry points: no cap on days in a row, the legal 35 h for minors. */
        static StaffingRules of(int dureeHebdomadaireMaxMinutes, int dureePauseMinutes, List<FenetreRepas> fenetres) {
            return new StaffingRules(
                    dureeHebdomadaireMaxMinutes,
                    dureePauseMinutes,
                    ParametresLegaux.DUREE_HEBDOMADAIRE_MAX_MINEUR_MINUTES_PAR_DEFAUT,
                    fenetres,
                    null);
        }
    }

    /**
     * Which referentials the edition is still missing, from the three lists
     * every caller of the seat-only build holds. In one place, so REST and
     * MCP cannot disagree on what « missing » means.
     */
    public static List<ReferentielManquant> referentielsManquants(
            List<Stand> stands, List<Creneau> creneaux, List<Animateur> animateurs) {
        List<ReferentielManquant> manquants = new ArrayList<>();
        if (stands == null || stands.isEmpty()) {
            manquants.add(ReferentielManquant.STANDS);
        }
        if (creneaux == null || creneaux.isEmpty()) {
            manquants.add(ReferentielManquant.CRENEAUX);
        }
        if (animateurs == null || animateurs.isEmpty()) {
            manquants.add(ReferentielManquant.ANIMATEURS);
        }
        return List.copyOf(manquants);
    }

    /**
     * On seats already built, with no stand or timeslot list in sight: an
     * empty seat list cannot tell a missing stand from a missing timeslot, so
     * only the animateurs are reported missing here. The callers reading the
     * edition go through {@link #analyze(List, List, List, int, int, List)}
     * with {@link #referentielsManquants}.
     */
    public StaffingSummary analyze(
            List<PosteAffectation> postes,
            List<Animateur> animateurs,
            List<TypologieItem> typologies,
            int dureeHebdomadaireMaxMinutes,
            int dureePauseMinutes) {
        return analyze(
                postes,
                animateurs,
                typologies,
                dureeHebdomadaireMaxMinutes,
                dureePauseMinutes,
                animateurs == null || animateurs.isEmpty() ? List.of(ReferentielManquant.ANIMATEURS) : List.of(),
                List.of());
    }

    /** The bounds without meal windows: what a caller that has not read them asks for. */
    public StaffingSummary analyze(
            List<PosteAffectation> postes,
            List<Animateur> animateurs,
            List<TypologieItem> typologies,
            int dureeHebdomadaireMaxMinutes,
            int dureePauseMinutes,
            List<ReferentielManquant> referentielsManquants) {
        return analyze(
                postes,
                animateurs,
                typologies,
                StaffingRules.of(dureeHebdomadaireMaxMinutes, dureePauseMinutes, List.of()),
                referentielsManquants);
    }

    /**
     * @param animateurs            the animateurs the referential holds, only
     *                              ever counted — never named. An empty list
     *                              still yields every bound; only the
     *                              bottleneck comparison and the availability
     *                              projection are left out, since there is
     *                              nothing to compare against yet.
     * @param typologies            the game category referential, which is
     *                              what tells the ninja one apart. Never a
     *                              hard-coded list: those categories are CRUD
     *                              data.
     * @param referentielsManquants what the edition has not filled in yet,
     *                              carried through so the screen names it
     *                              rather than showing a zero.
     * @param fenetresRepas         the meal windows the plan must honour, or an
     *                              empty list when {@code coupureRepasObligatoire}
     *                              is switched off for this edition. A rule that
     *                              is not enforced must not raise a floor: the
     *                              Besoin screen would then announce a number no
     *                              solve is asked to reach.
     */
    public StaffingSummary analyze(
            List<PosteAffectation> postes,
            List<Animateur> animateurs,
            List<TypologieItem> typologies,
            int dureeHebdomadaireMaxMinutes,
            int dureePauseMinutes,
            List<ReferentielManquant> referentielsManquants,
            List<FenetreRepas> fenetresRepas) {
        return analyze(
                postes,
                animateurs,
                typologies,
                StaffingRules.of(dureeHebdomadaireMaxMinutes, dureePauseMinutes, fenetresRepas),
                referentielsManquants);
    }

    /**
     * The whole analysis, under the edition's own rules — what
     * {@link StaffingService} calls.
     */
    public StaffingSummary analyze(
            List<PosteAffectation> postes,
            List<Animateur> animateurs,
            List<TypologieItem> typologies,
            StaffingRules rules,
            List<ReferentielManquant> referentielsManquants) {
        List<FenetreRepas> fenetres = rules.fenetresRepas();
        int dureeHebdomadaireMaxMinutes = rules.dureeHebdomadaireMaxMinutes();
        int dureePauseMinutes = rules.dureePauseMinutes();
        List<Siege> sieges = sieges(postes);
        Map<LocalDate, BesoinJour> besoins = besoinsByDate(sieges, dureePauseMinutes, fenetres);
        Bornes bornes = bornes(besoins, rules);
        List<Animateur> connus = animateurs == null ? List.of() : animateurs;
        // The team every « how many may » figure is read against: the floor,
        // or the animateurs already entered when there are more of them.
        int effectif = Math.max(bornes.minimumTotal(), connus.size());

        Map<LocalDate, Integer> dayByDate = dayByDate(postes);
        Map<LocalDate, Set<String>> standsByDate = new TreeMap<>();
        Map<LocalDate, Integer> siegesByDate = new TreeMap<>();
        for (Siege siege : sieges) {
            siegesByDate.merge(siege.date(), 1, Integer::sum);
            if (siege.stand() != null) {
                standsByDate
                        .computeIfAbsent(siege.date(), date -> new HashSet<>())
                        .add(siege.stand().getId());
            }
        }

        Map<LocalDate, Integer> majeursParJour = adultsRequired(sieges, besoins);
        List<JourStaffing> parJour = new ArrayList<>();
        for (BesoinJour besoin : besoins.values()) {
            parJour.add(new JourStaffing(
                    besoin.date(),
                    dayByDate.getOrDefault(besoin.date(), 0),
                    standsByDate.getOrDefault(besoin.date(), Set.of()).size(),
                    siegesByDate.getOrDefault(besoin.date(), 0),
                    besoin.heures(),
                    besoin.picSimultane(),
                    besoin.picAvecPause(),
                    besoin.picRepas(),
                    besoin.minimum(),
                    disponibles(connus, besoin.date()),
                    effectif - besoin.minimum(),
                    majeursParJour.getOrDefault(besoin.date(), 0)));
        }

        // The critical day is the one that needs the most people, which is the
        // day-level minimum and no longer the raw peak — a long, flat day can
        // out-demand a spiky one.
        JourStaffing jourCritique = parJour.stream()
                .max(Comparator.comparingInt(JourStaffing::minimumJour).thenComparingInt(JourStaffing::picAvecPause))
                .orElse(null);

        List<SemaineStaffing> parSemaine = bornes.parSemaine().stream()
                .map(semaine -> semaine.withIndisponibilites(
                        semaine.joursTravaillables() * effectif - semaine.joursPersonne(),
                        unavailableBeyondRest(connus, semaine, besoins.keySet())))
                .toList();
        SemaineStaffing semaineCritique = bornes.semaineCritique() == null
                ? null
                : parSemaine.stream()
                        .filter(semaine -> semaine.semaine()
                                .equals(bornes.semaineCritique().semaine()))
                        .findFirst()
                        .orElse(null);
        int majeursMin = majeursParJour.values().stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
        return new StaffingSummary(
                List.copyOf(parJour),
                List.copyOf(parSemaine),
                semaineCritique,
                bornes.picSimultane(),
                bornes.picAvecPause(),
                bornes.picRepas(),
                jourCritique,
                bornes.heures(),
                bornes.semaines(),
                bornes.semaineCritique() == null ? 0 : bornes.semaineCritique().capaciteHeuresParAnimateur(),
                bornes.chargeTotal(),
                bornes.rotationTotal(),
                bornes.minimumTotal(),
                bornes.borneRetenue(),
                projectOnIndisponibilites(parJour, connus, bornes.minimumTotal()),
                connus.stream()
                        .anyMatch(animateur -> !indisponibilites(animateur).isEmpty()),
                bornes.enchainementTotal(),
                rules.joursConsecutifsMax(),
                effectif,
                majeursMin,
                mineursMax(effectif, majeursMin, parSemaine, besoins.keySet(), rules),
                dureeHebdomadaireMaxMinutes,
                PlafondsLegauxMajeurs.DUREE_QUOTIDIENNE_MAX_MINUTES,
                PlafondsLegauxMajeurs.JOURS_TRAVAILLES_MAX_PAR_SEMAINE,
                bottleneckPerCategory(sieges, connus, typologies, rules),
                referentielsManquants == null ? List.of() : List.copyOf(referentielsManquants));
    }

    /**
     * The bounds corrected by the availability the animateurs have declared —
     * a projection, not a bound, and deliberately reported apart from
     * {@code minimumTotal} for that reason.
     *
     * <p>A day where only a share {@code t} of the known pool is free needs a
     * pool of {@code besoin / t} for that day's need to be met at all; the
     * largest such requirement over the days is what a recruiter has to plan
     * for. Never below {@code minimumTotal}: a projection that undercut a
     * proven floor would be nonsense.</p>
     *
     * <p>Falls back to {@code minimumTotal} when nothing is declared (a rate of
     * 1 leaves the bound untouched anyway) and when a day has nobody at all,
     * which the referential's own emptiness already says louder.</p>
     */
    private static int projectOnIndisponibilites(
            List<JourStaffing> parJour, List<Animateur> animateurs, int minimumTotal) {
        if (animateurs.isEmpty()) {
            return minimumTotal;
        }
        int projete = minimumTotal;
        for (JourStaffing jour : parJour) {
            if (jour.disponibles() <= 0) {
                continue;
            }
            double taux = (double) jour.disponibles() / animateurs.size();
            projete = Math.max(projete, (int) Math.ceil(jour.minimumJour() / taux));
        }
        return projete;
    }

    private static int disponibles(List<Animateur> animateurs, LocalDate date) {
        return (int) animateurs.stream()
                .filter(animateur -> !animateur.isIndisponibleOn(date))
                .count();
    }

    private static Set<LocalDate> indisponibilites(Animateur animateur) {
        return animateur.getJoursIndisponibles() == null ? Set.of() : animateur.getJoursIndisponibles();
    }

    /**
     * Replays the same bounds on the seats of each game category and puts
     * them against the animateurs who declare it — see the class javadoc for
     * the two attribution rules this rests on.
     */
    private static CompetenceStaffing bottleneckPerCategory(
            List<Siege> sieges, List<Animateur> animateurs, List<TypologieItem> typologies, StaffingRules rules) {
        List<Animateur> connus = animateurs == null ? List.of() : animateurs;
        List<TypologieItem> referentiel = typologies == null ? List.of() : typologies;

        String ninja = referentiel.stream()
                .filter(TypologieItem::ninja)
                .map(TypologieItem::id)
                .findFirst()
                .orElse(null);
        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, Integer> plafonds = new LinkedHashMap<>();
        referentiel.forEach(typologie -> {
            labels.put(typologie.id(), typologie.label());
            if (typologie.maxCreneauxParAnimateur() != null && typologie.maxCreneauxParAnimateur() > 0) {
                plafonds.put(typologie.id(), typologie.maxCreneauxParAnimateur());
            }
        });

        SeatAttribution attribution = SeatAttribution.of(sieges, ninja);
        Map<String, List<Siege>> parTypologie = attribution.parTypologie;

        Map<String, Integer> specialistes = new LinkedHashMap<>();
        int polyvalents = countSpecialists(connus, ninja, specialistes);

        List<TypologieStaffing> lignes = new ArrayList<>();
        for (Map.Entry<String, List<Siege>> entree : parTypologie.entrySet()) {
            String id = entree.getKey();
            Bornes bornes =
                    bornes(besoinsByDate(entree.getValue(), rules.dureePauseMinutes(), rules.fenetresRepas()), rules);
            // Each seat is one timeslot held: a cap of k timeslots per
            // animateur over the edition spreads them over ⌈seats ÷ k⌉ people
            // at least, whatever the days look like.
            Integer plafond = plafonds.get(id);
            int minimumPlafond =
                    plafond == null ? 0 : Math.ceilDiv(entree.getValue().size(), plafond);
            int minimum = Math.max(bornes.minimumTotal(), minimumPlafond);
            BorneRetenue retenue =
                    minimumPlafond > bornes.minimumTotal() ? BorneRetenue.PLAFOND_TYPOLOGIE : bornes.borneRetenue();
            int disponibles = specialistes.getOrDefault(id, 0);
            int manque = connus.isEmpty() ? 0 : Math.max(0, minimum - disponibles);
            lignes.add(new TypologieStaffing(
                    id,
                    labels.getOrDefault(id, id),
                    id.equals(ninja),
                    entree.getValue().size(),
                    bornes.heures(),
                    bornes.semaines(),
                    bornes.picSimultane(),
                    bornes.picAvecPause(),
                    bornes.picRepas(),
                    bornes.chargeTotal(),
                    bornes.rotationTotal(),
                    bornes.enchainementTotal(),
                    plafond,
                    minimumPlafond,
                    minimum,
                    retenue,
                    disponibles,
                    manque));
        }
        // Tightest bottleneck first, so the row that explains an infeasibility
        // is the one read first.
        lignes.sort(Comparator.comparingInt(TypologieStaffing::manque)
                .reversed()
                .thenComparing(
                        Comparator.comparingInt(TypologieStaffing::minimumTotal).reversed())
                .thenComparing(TypologieStaffing::label)
                .thenComparing(TypologieStaffing::typologie));

        return new CompetenceStaffing(
                List.copyOf(lignes),
                polyvalents,
                attribution.siegesNonAttribues,
                attribution.siegesReservesAuxPolyvalents,
                lignes.stream().mapToInt(TypologieStaffing::manque).sum(),
                lignes.stream()
                        .filter(TypologieStaffing::ninja)
                        .mapToInt(TypologieStaffing::manque)
                        .sum(),
                connus.size(),
                ninja != null,
                lignes.stream().mapToInt(TypologieStaffing::minimumTotal).sum());
    }

    /** Each seat given to the one category that can claim it, when one can. */
    private static final class SeatAttribution {
        private final Map<String, List<Siege>> parTypologie = new LinkedHashMap<>();
        private int siegesNonAttribues;
        private int siegesReservesAuxPolyvalents;

        static SeatAttribution of(List<Siege> sieges, String ninja) {
            SeatAttribution attribution = new SeatAttribution();
            for (Siege siege : sieges) {
                attribution.attribute(siege, ninja);
            }
            return attribution;
        }

        private void attribute(Siege siege, String ninja) {
            Set<String> offered = offeredTypologies(siege.stand());
            if (offered.size() > 1) {
                // Either pool staffs them: no single category can claim them.
                siegesNonAttribues++;
            } else if (offered.isEmpty()) {
                // The opposite case, and the tightest demand there is: with no
                // category at all, hasCompetenceFor() only answers yes for a
                // polyvalent. The demand is the ninja category's own — same
                // required population — so it joins that row when one exists.
                siegesReservesAuxPolyvalents++;
                if (ninja != null) {
                    parTypologie.computeIfAbsent(ninja, id -> new ArrayList<>()).add(siege);
                }
            } else {
                parTypologie
                        .computeIfAbsent(offered.iterator().next(), id -> new ArrayList<>())
                        .add(siege);
            }
        }
    }

    /**
     * Counts, into {@code specialistes}, who declares each category, and
     * returns how many polyvalents there are. Declared competences only: a
     * ninja is eligible everywhere, but counting them in every category would
     * add one person to every pool at once. They are the shared reserve
     * instead.
     */
    private static int countSpecialists(List<Animateur> connus, String ninja, Map<String, Integer> specialistes) {
        int polyvalents = 0;
        for (Animateur animateur : connus) {
            Map<String, ?> competences = animateur.getCompetences() == null ? Map.of() : animateur.getCompetences();
            competences.keySet().forEach(id -> specialistes.merge(id, 1, Integer::sum));
            if (animateur.isNinja() || (ninja != null && competences.containsKey(ninja))) {
                polyvalents++;
            }
        }
        return polyvalents;
    }

    /**
     * What a stand offers, never {@code null}. Its size is what the attribution
     * reads: one category is a provable demand for it, several is a demand no
     * single one can claim, and <b>none</b> is the opposite of several — only a
     * polyvalent can hold such a seat.
     */
    private static Set<String> offeredTypologies(Stand stand) {
        if (stand == null || stand.getTypologiesProposees() == null) {
            return Set.of();
        }
        return stand.getTypologiesProposees();
    }

    /** The four bounds of the class javadoc, over an arbitrary set of seats. */
    private record Bornes(
            double heures,
            int semaines,
            int picSimultane,
            int picAvecPause,
            int picRepas,
            int chargeTotal,
            int rotationTotal,
            int enchainementTotal,
            int minimumTotal,
            BorneRetenue borneRetenue,
            List<SemaineStaffing> parSemaine,
            SemaineStaffing semaineCritique) {}

    /**
     * Day-level demand, keyed by date and ordered by it. Peaks are computed per
     * day because minutes are counted from the start of a day: seats of two
     * different dates never overlap.
     */
    private static Map<LocalDate, BesoinJour> besoinsByDate(
            Collection<Siege> sieges, int dureePauseMinutes, List<FenetreRepas> fenetres) {
        Map<LocalDate, List<Siege>> byDate = new TreeMap<>();
        for (Siege siege : sieges) {
            byDate.computeIfAbsent(siege.date(), date -> new ArrayList<>()).add(siege);
        }
        Map<LocalDate, BesoinJour> besoins = new LinkedHashMap<>();
        for (Map.Entry<LocalDate, List<Siege>> entree : byDate.entrySet()) {
            double heures = 0;
            for (Siege siege : entree.getValue()) {
                heures += (siege.fin() - siege.debut()) / 60.0;
            }
            // No buffer: since ADR 0048 no rule requires a gap between two
            // vacations, so this bound coincides with the simultaneous peak.
            int picAvecPause = pic(entree.getValue(), 0);
            // A day is also bounded by its sheer volume: nobody works more than
            // the daily legal ceiling, so 600 person-hours need 60 people
            // whatever the shape of the day.
            int parLesHeures = (int) Math.ceil(heures * 60 / maxDailyAmplitude(dureePauseMinutes));
            int picRepas = 0;
            for (FenetreRepas fenetre : fenetres) {
                if (fenetre.appliesTo(entree.getKey())) {
                    picRepas = Math.max(picRepas, picRepas(entree.getValue(), fenetre));
                }
            }
            besoins.put(
                    entree.getKey(),
                    new BesoinJour(
                            entree.getKey(),
                            heures,
                            pic(entree.getValue(), 0),
                            picAvecPause,
                            picRepas,
                            Math.max(Math.max(picAvecPause, parLesHeures), picRepas)));
        }
        return besoins;
    }

    private static Bornes bornes(Map<LocalDate, BesoinJour> besoins, StaffingRules rules) {
        int dureeHebdomadaireMaxMinutes = rules.dureeHebdomadaireMaxMinutes();
        int dureePauseMinutes = rules.dureePauseMinutes();
        int picSimultane = besoins.values().stream()
                .mapToInt(BesoinJour::picSimultane)
                .max()
                .orElse(0);
        int picAvecPause = besoins.values().stream()
                .mapToInt(BesoinJour::picAvecPause)
                .max()
                .orElse(0);
        int picRepas =
                besoins.values().stream().mapToInt(BesoinJour::picRepas).max().orElse(0);
        double heures =
                besoins.values().stream().mapToDouble(BesoinJour::heures).sum();

        Map<String, List<BesoinJour>> parSemaineIso = new LinkedHashMap<>();
        for (BesoinJour besoin : besoins.values()) {
            parSemaineIso
                    .computeIfAbsent(semaineIso(besoin.date()), semaine -> new ArrayList<>())
                    .add(besoin);
        }

        List<SemaineStaffing> parSemaine = new ArrayList<>();
        for (Map.Entry<String, List<BesoinJour>> entree : parSemaineIso.entrySet()) {
            List<BesoinJour> jours = entree.getValue();
            int joursTravaillables = Math.min(jours.size(), PlafondsLegauxMajeurs.JOURS_TRAVAILLES_MAX_PAR_SEMAINE);
            double heuresSemaine =
                    jours.stream().mapToDouble(BesoinJour::heures).sum();
            // Two ceilings at once: the weekly one, and the days the event
            // really occupies in that week — six at most, of ten hours at most.
            double capacite = Math.min(
                            dureeHebdomadaireMaxMinutes + joursTravaillables * (long) dureePauseMinutes,
                            joursTravaillables * (long) maxDailyAmplitude(dureePauseMinutes))
                    / 60.0;
            int joursPersonne = jours.stream().mapToInt(BesoinJour::minimum).sum();
            parSemaine.add(new SemaineStaffing(
                    entree.getKey(),
                    jours.get(0).date().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
                    jours.size(),
                    joursTravaillables,
                    heuresSemaine,
                    capacite,
                    capacite > 0 ? (int) Math.ceil(heuresSemaine / capacite) : 0,
                    joursPersonne,
                    joursTravaillables > 0 ? (int) Math.ceil((double) joursPersonne / joursTravaillables) : 0,
                    0,
                    0));
        }
        parSemaine.sort(Comparator.comparing(SemaineStaffing::debut));

        int chargeTotal =
                parSemaine.stream().mapToInt(SemaineStaffing::chargeTotal).max().orElse(0);
        int rotationTotal = parSemaine.stream()
                .mapToInt(SemaineStaffing::rotationTotal)
                .max()
                .orElse(0);
        int autres = Math.max(
                Math.max(Math.max(picSimultane, picAvecPause), picRepas), Math.max(chargeTotal, rotationTotal));
        // The exact cover of the days, searched from the floor already proved:
        // it can only confirm it or raise it.
        Map<LocalDate, Integer> parJour = new TreeMap<>();
        besoins.forEach((date, besoin) -> parJour.put(date, besoin.minimum()));
        int enchainementTotal = DaySequenceFloor.floor(parJour, rules.joursConsecutifsMax(), autres);
        int minimumTotal = Math.max(autres, enchainementTotal);
        SemaineStaffing semaineCritique = parSemaine.stream()
                .max(Comparator.comparingInt(semaine -> Math.max(semaine.chargeTotal(), semaine.rotationTotal())))
                .orElse(null);
        return new Bornes(
                heures,
                parSemaine.size(),
                picSimultane,
                picAvecPause,
                picRepas,
                chargeTotal,
                rotationTotal,
                enchainementTotal,
                minimumTotal,
                enchainementTotal > autres
                        ? BorneRetenue.ENCHAINEMENT_JOURS
                        : borneRetenue(picSimultane, picAvecPause, chargeTotal, rotationTotal, picRepas),
                List.copyOf(parSemaine),
                semaineCritique);
    }

    /** The weeks {@code Creneau#semaineIso()} names, from a bare date. */
    private static String semaineIso(LocalDate date) {
        return String.format(
                Locale.ROOT,
                "%d-W%02d",
                date.get(IsoFields.WEEK_BASED_YEAR),
                date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    /**
     * The bound that set the minimum. Rotation is named only when it is
     * strictly the largest: on a tie any of the others explains the same
     * number in fewer words, and the peak explains it best of all — except
     * against the meal window, which rotation may merely be relaying.
     */
    private static BorneRetenue borneRetenue(
            int picSimultane, int picAvecPause, int chargeTotal, int rotationTotal, int picRepas) {
        // Ties with rotation go to the meal window: the rotation bound counts
        // person-days built from each day's own minimum, so when the window is
        // what raised that minimum, rotation only echoes it back.
        if (picRepas >= rotationTotal && picRepas > chargeTotal && picRepas > picAvecPause && picRepas > picSimultane) {
            return BorneRetenue.COUPURE_REPAS;
        }
        if (rotationTotal > chargeTotal && rotationTotal > picAvecPause && rotationTotal > picSimultane) {
            return BorneRetenue.ROTATION_JOURS;
        }
        if (chargeTotal >= picAvecPause && chargeTotal >= picSimultane) {
            return BorneRetenue.CHARGE_HORAIRE;
        }
        // Strictly greater, not « at least »: with no buffer left the two are
        // equal on every day, and crediting « pic avec tampon » would name a
        // bound that no longer constrains anything.
        return picAvecPause > picSimultane ? BorneRetenue.PIC_AVEC_PAUSE : BorneRetenue.PIC_SIMULTANE;
    }

    /**
     * How many of each day's people must be adults: the whole day's minimum on
     * a public holiday, when no minor may work (art. L3164-6); otherwise the
     * peak of the seats no minor may hold — those of a stand reserved to
     * adults, and those reaching into the night of a 16-17 year old (art.
     * L3163-1, 22 h-6 h). The night of an under-16 starts at 20 h, so this is
     * the most lenient reading: a ceiling on minors computed from it holds for
     * every minor.
     */
    private static Map<LocalDate, Integer> adultsRequired(List<Siege> sieges, Map<LocalDate, BesoinJour> besoins) {
        int finNuit = Creneau.FIN_NUIT.toSecondOfDay() / 60;
        int debutNuit = Creneau.DEBUT_NUIT_16_A_18_ANS.toSecondOfDay() / 60;
        Map<LocalDate, List<Siege>> reserves = new TreeMap<>();
        for (Siege siege : sieges) {
            boolean reserve = siege.stand() != null && siege.stand().isReserveMajeurs();
            // Minutes from the day's midnight, an evening seat running past it
            // keeping an end beyond 24 h: the night is 22 h to 30 h on that axis.
            boolean nuit = siege.debut() < finNuit || siege.fin() > debutNuit;
            if (reserve || nuit) {
                reserves.computeIfAbsent(siege.date(), date -> new ArrayList<>())
                        .add(siege);
            }
        }
        Map<LocalDate, Integer> majeurs = new TreeMap<>();
        for (BesoinJour besoin : besoins.values()) {
            int requis = JoursFeries.isFerieInFrance(besoin.date())
                    ? besoin.minimum()
                    : pic(reserves.getOrDefault(besoin.date(), List.of()), 0);
            majeurs.put(besoin.date(), requis);
        }
        return majeurs;
    }

    /**
     * The most minors a team of {@code effectif} can hold — a <b>ceiling</b>,
     * never a target: it keeps three necessary conditions and nothing else.
     *
     * <ol>
     * <li>The adults left must cover the day needing the most of them:
     * {@code effectif − majeursMin}.</li>
     * <li>A week's person-days: an adult works {@code min(jours, 6)} days of
     * it, a minor {@code min(jours − fériés, 5)} — two rest days in a row
     * (art. L3164-2), never a public holiday — so each minor costs the
     * difference.</li>
     * <li>A week's hours: an adult covers the week's
     * {@code capaciteHeuresParAnimateur}, a minor at most the minors' weekly
     * ceiling plus a thirty-minute break a day.</li>
     * </ol>
     *
     * <p>Neither the cap on days in a row nor the four-and-a-half hours of
     * continuous work a minor may not exceed are counted, so a solve may well
     * hold fewer: the ceiling is where recruiting minors must stop, never how
     * many to recruit.</p>
     */
    private static int mineursMax(
            int effectif,
            int majeursMin,
            List<SemaineStaffing> semaines,
            Set<LocalDate> joursEvenement,
            StaffingRules rules) {
        long plafond = (long) effectif - majeursMin;
        for (SemaineStaffing semaine : semaines) {
            int feries = (int) joursEvenement.stream()
                    .filter(date -> !date.isBefore(semaine.debut())
                            && date.isBefore(semaine.debut().plusDays(7)))
                    .filter(JoursFeries::isFerieInFrance)
                    .count();
            int joursMajeur = semaine.joursTravaillables();
            int joursMineur = Math.clamp(
                    semaine.jours() - (long) feries, 0, 7 - PlafondsLegauxMineurs.JOURS_REPOS_CONSECUTIFS_PAR_SEMAINE);
            if (joursMajeur > joursMineur) {
                plafond = Math.min(
                        plafond,
                        Math.floorDiv(
                                (long) joursMajeur * effectif - semaine.joursPersonne(), joursMajeur - joursMineur));
            }
            double capaciteMineur = Math.min(
                            rules.dureeHebdomadaireMaxMineurMinutes()
                                    + joursMineur * (long) PlafondsLegauxMineurs.PAUSE_MINIMALE_MINUTES,
                            joursMineur
                                    * (long) (PlafondsLegauxMineurs.DUREE_QUOTIDIENNE_MAX_MINUTES
                                            + PlafondsLegauxMineurs.PAUSE_MINIMALE_MINUTES))
                    / 60.0;
            double capaciteMajeur = semaine.capaciteHeuresParAnimateur();
            if (capaciteMajeur > capaciteMineur) {
                plafond = Math.min(plafond, (long)
                        Math.floor((effectif * capaciteMajeur - semaine.heures()) / (capaciteMajeur - capaciteMineur)));
            }
        }
        return Math.clamp(plafond, 0, effectif);
    }

    /**
     * The days the known animateurs declared unavailable in one week beyond
     * the one each may take for free: in a week of seven event days everybody
     * owes a rest day, and a day off falling there costs nothing. In a shorter
     * week the days off the event are the rest, and every unavailable event
     * day counts.
     */
    private static int unavailableBeyondRest(
            List<Animateur> animateurs, SemaineStaffing semaine, Set<LocalDate> joursEvenement) {
        List<LocalDate> jours = joursEvenement.stream()
                .filter(date -> !date.isBefore(semaine.debut())
                        && date.isBefore(semaine.debut().plusDays(7)))
                .toList();
        int libres = jours.size() - semaine.joursTravaillables();
        int auDela = 0;
        for (Animateur animateur : animateurs) {
            int absences =
                    (int) jours.stream().filter(animateur::isIndisponibleOn).count();
            auDela += Math.max(0, absences - libres);
        }
        return auDela;
    }

    /**
     * Largest number of {@code sieges} overlapping at the same instant, each
     * one extended by {@code tamponMinutes} on its right — see the class
     * javadoc for why extending them turns a "seats at once" reading into an
     * exact "distinct people needed" one.
     */
    private static int pic(List<Siege> sieges, int tamponMinutes) {
        List<int[]> evenements = new ArrayList<>(sieges.size() * 2);
        for (Siege siege : sieges) {
            evenements.add(new int[] {siege.debut(), 1});
            evenements.add(new int[] {siege.fin() + tamponMinutes, -1});
        }
        // A seat ending exactly when another starts must not count as an
        // overlap: process the -1 events of an instant before its +1 events.
        evenements.sort(
                Comparator.<int[]>comparingInt(evenement -> evenement[0]).thenComparingInt(evenement -> evenement[1]));
        int courant = 0;
        int pic = 0;
        for (int[] evenement : evenements) {
            courant += evenement[1];
            pic = Math.max(pic, courant);
        }
        return pic;
    }

    /**
     * What one meal window forces on a single day, as a floor on the number of
     * distinct animateurs. The fifth bound of the class javadoc, and like the
     * others a <em>proof</em> rather than an estimate.
     *
     * <h3>Proof</h3>
     *
     * <p>Let {@code N} be the animateurs staffing the day, the window be
     * {@code F = [W1, W2)} of length {@code L}, and {@code D} the break it
     * owes. Put {@code m = ceil(L / D)} and take the instants
     * {@code g(k) = W1 + k·D} for {@code k < m}, all inside {@code F}.</p>
     *
     * <ol>
     * <li>Any free stretch {@code [t, t + D)} contained in {@code F} contains
     * some {@code g(k)}: with {@code k = ceil((t − W1) / D)} we get
     * {@code t ≤ g(k) < t + D}.</li>
     * <li>Take any instant {@code u < W1} and any instant {@code v ≥ W2}.
     * Whoever works at both holds a seat starting before {@code W1} and a seat
     * ending after {@code W2}, so they owe the break — and are therefore idle
     * at some {@code g(k)}.</li>
     * <li>They number at least {@code n(u) + n(v) − N}, while the people idle
     * at one of the {@code g(k)} number at most {@code Σ (N − n(g(k)))}.</li>
     * <li>Hence {@code n(u) + n(v) − N ≤ m·N − Σ n(g(k))}, that is
     * {@code N ≥ ceil((n(u) + n(v) + Σ n(g(k))) / (m + 1))}.</li>
     * </ol>
     *
     * <p>{@code n(u)} is taken at the busiest instant before the window and
     * {@code n(v)} at the busiest from its close on, which is the tightest
     * choice the inequality allows.</p>
     *
     * <p>Worked example: ten seats 08:00-13:00 then ten seats 13:00-20:00, a
     * 12:00-14:00 window owing 60 minutes. {@code m = 2}, the grid is
     * {12:00, 13:00}, and {@code (10 + 10 + 10 + 10) / 3} gives 14 where the
     * plain peak said 10. The real minimum is 20 — nobody can hold both halves
     * — so the bound stays a bound, and a far better one than the peak. Cut the
     * same day as 08:00-12:00 and 14:00-20:00 instead and it yields 7, below
     * the peak: ten people cover it by eating from noon to two, and nothing is
     * inflated.</p>
     *
     * <h3>The seats that deny the break outright (issue #482)</h3>
     *
     * <p>That count is blind to a seat which, on its own, leaves its holder no
     * room to eat — and a hand-written grid is full of them. Call a seat
     * <em>blocking</em> when the part of {@code F} it does not cover holds no
     * free stretch of {@code D}. Whoever holds one cannot take the break, so
     * by {@code CoupureRepas} they cannot work both sides of the window.</p>
     *
     * <ol>
     * <li>Let {@code A} be the blocking seats that start at {@code W1} or
     * later and end after {@code W2}. Their holders work after the window, so
     * none of them works before {@code W1}, so none holds a seat live at any
     * {@code u < W1}: {@code N >= n(u) + |A|}, the seats of {@code A} being
     * pairwise concurrent — each covers the instant {@code W1 + D − 1}.</li>
     * <li>Symmetrically, let {@code B} be the blocking seats that start before
     * {@code W1} and end at {@code W2} or earlier. Their holders work before
     * the window, so none works at any {@code v >= W2}:
     * {@code N >= n(v) + |B|}.</li>
     * </ol>
     *
     * <p>A blocking seat straddling the window on <em>both</em> sides is left
     * out of {@code A} and {@code B} on purpose: its own holder already owes a
     * break the seat forbids, so no headcount ever staffs that grid — it is
     * the unsatisfiable grid {@code RepasConstraints} documents, and a floor
     * has nothing to say about it.</p>
     *
     * <p>Worked example, the one the bound used to miss: an afternoon of 138
     * seats 14:00-20:00, an evening of 27 seats 20:00-24:00, a 20:00-21:00
     * window owing 60 minutes. The grid gives {@code (138 + 27 + 27) / 2 = 96}
     * because it counts the 27 evening holders as merely busy at 20:00; they
     * are in fact barred from the afternoon, and {@code n(u) + |A| = 138 + 27}
     * gives the true 165.</p>
     *
     */
    private static int picRepas(Collection<Siege> sieges, FenetreRepas fenetre) {
        int ouverture = fenetre.debutMinutes();
        int fermeture = fenetre.finMinutes();
        int requis = fenetre.dureeMinutes();
        if (requis <= 0 || fermeture <= ouverture || sieges.isEmpty()) {
            return 0;
        }
        int pas = Math.ceilDiv(fermeture - ouverture, requis);
        int[] grille = new int[pas];
        for (int k = 0; k < pas; k++) {
            grille[k] = ouverture + k * requis;
        }

        WindowSweep balayage = sweep(sieges, ouverture, fermeture, grille);
        int avant = balayage.avant();
        int apres = balayage.apres();
        int total = avant + apres;
        for (int occupe : balayage.occupation()) {
            total += occupe;
        }
        int parLaGrille = Math.ceilDiv(total, pas + 1);

        // The seats that deny the break to whoever holds them, split by the
        // side of the window their holder is then barred from.
        List<Siege> bloquantsApres = sieges.stream()
                .filter(siege -> siege.debut() >= ouverture
                        && siege.fin() > fermeture
                        && Math.min(siege.debut(), fermeture) - ouverture < requis)
                .toList();
        List<Siege> bloquantsAvant = sieges.stream()
                .filter(siege -> siege.fin() <= fermeture
                        && siege.debut() < ouverture
                        && fermeture - Math.max(siege.fin(), ouverture) < requis)
                .toList();
        // pic() rather than the raw count: it is the number of distinct people
        // the group really needs, and it stays exact on the degenerate window
        // shorter than its own break, where two blocking seats may follow one
        // another instead of overlapping.
        return Math.max(parLaGrille, Math.max(avant + pic(bloquantsApres, 0), apres + pic(bloquantsAvant, 0)));
    }

    /**
     * The staffing a meal window's sweep reads: the peak of seats live before
     * the window opens, the peak of those still live after it closes, and the
     * seats live at each instant of the window's grid. A class rather than a
     * record: it carries an array and is only ever read, never compared.
     */
    private static final class WindowSweep {
        private final int avant;
        private final int apres;
        private final int[] occupation;

        WindowSweep(int avant, int apres, int[] occupation) {
            this.avant = avant;
            this.apres = apres;
            this.occupation = occupation;
        }

        int avant() {
            return avant;
        }

        int apres() {
            return apres;
        }

        int[] occupation() {
            return occupation;
        }
    }

    private static WindowSweep sweep(Collection<Siege> sieges, int ouverture, int fermeture, int[] grille) {
        List<int[]> evenements = new ArrayList<>(sieges.size() * 2);
        for (Siege siege : sieges) {
            evenements.add(new int[] {siege.debut(), 1});
            evenements.add(new int[] {siege.fin(), -1});
        }
        // Same ordering as pic(): a seat ending where another starts is not an
        // overlap, so the -1 of an instant is applied before its +1.
        evenements.sort(
                Comparator.<int[]>comparingInt(evenement -> evenement[0]).thenComparingInt(evenement -> evenement[1]));

        int courant = 0;
        int avant = 0;
        int apres = 0;
        int[] occupation = new int[grille.length];
        for (int i = 0; i < evenements.size(); i++) {
            courant += evenements.get(i)[1];
            int instant = evenements.get(i)[0];
            // Not done with this instant while the next event shares it: the
            // count is only meaningful once every one of them has been applied.
            boolean instantComplet = i + 1 == evenements.size() || evenements.get(i + 1)[0] != instant;
            if (instantComplet) {
                int prochain = i + 1 < evenements.size() ? evenements.get(i + 1)[0] : Integer.MAX_VALUE;
                if (instant < ouverture) {
                    avant = Math.max(avant, courant);
                }
                if (prochain > fermeture) {
                    apres = Math.max(apres, courant);
                }
                markOccupation(occupation, grille, instant, prochain, courant);
            }
        }
        return new WindowSweep(avant, apres, occupation);
    }

    /** Records {@code courant} at every grid instant falling in {@code [instant, prochain)}. */
    private static void markOccupation(int[] occupation, int[] grille, int instant, int prochain, int courant) {
        for (int k = 0; k < grille.length; k++) {
            if (instant <= grille[k] && grille[k] < prochain) {
                occupation[k] = courant;
            }
        }
    }

    private static List<Siege> sieges(List<PosteAffectation> postes) {
        List<Siege> sieges = new ArrayList<>();
        for (PosteAffectation poste : postes) {
            siege(poste).ifPresent(sieges::add);
        }
        return sieges;
    }

    /** The seat as the bounds read it, empty when it has no day, no start or no duration. */
    private static Optional<Siege> siege(PosteAffectation poste) {
        Creneau creneau = poste.getCreneau();
        LocalTime debut = poste.heureDebutEffectif();
        if (creneau == null || creneau.getDate() == null || debut == null) {
            return Optional.empty();
        }
        int duree = poste.getDureeEffectiveMinutes();
        if (duree <= 0) {
            return Optional.empty();
        }
        int debutMinutes = debut.toSecondOfDay() / 60;
        // A window running past midnight stays on its own day, with an end
        // beyond 24 h — the peak of a night slot belongs to the evening it
        // started, not to the next morning.
        return Optional.of(new Siege(creneau.getDate(), debutMinutes, debutMinutes + duree, poste.getStand()));
    }

    private static Map<LocalDate, Integer> dayByDate(List<PosteAffectation> postes) {
        Map<LocalDate, Integer> jours = new LinkedHashMap<>();
        for (PosteAffectation poste : postes) {
            Creneau creneau = poste.getCreneau();
            if (creneau != null && creneau.getDate() != null) {
                jours.putIfAbsent(creneau.getDate(), creneau.getJour());
            }
        }
        return jours;
    }
}
