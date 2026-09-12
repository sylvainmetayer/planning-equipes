package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.FenetreRepas;
import dev.sylvain.planning.domain.PlafondsLegauxMajeurs;
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
 * <h2>The five bounds</h2>
 *
 * <p>Five bounds are computed and the largest wins. Each one is a
 * <em>proof</em> that no smaller number of people can cover the seats, derived
 * from a rule {@code LegalConstraints} really enforces — so the floor and the
 * solver can never contradict each other:</p>
 * <ol>
 * <li><b>Pic simultané</b> — the largest number of seats open at the same
 * instant. A floor by definition: nobody holds two seats at once.</li>
 * <li><b>Pic avec tampon de pause</b> — the same peak, over intervals each
 * extended by the legally-required break between two vacations
 * ({@code ParametresLegaux#getPauseMinimaleEntreVacationsMinutes()}). Two
 * vacations can be held by the same person only if their extended intervals
 * don't overlap, so this maximum overlap is <em>exactly</em> the minimum number
 * of distinct animateurs a day requires — the chromatic number of an interval
 * graph is its maximum clique.</li>
 * <li><b>Charge horaire</b> — the hours of the <em>busiest ISO week</em>,
 * divided by what one animateur may legally work during that week.</li>
 * <li><b>Rotation sur les jours</b> — the person-days of the busiest ISO week,
 * divided by the number of days one animateur may work in it (art. L3132-1:
 * six).</li>
 * <li><b>Coupure repas</b> — what the meal windows force, see
 * {@link #picRepas(List, FenetreRepas)} for the proof. A day whose grid leaves
 * no room to eat cannot be staffed by the people its peak alone suggests:
 * whoever works either side of the window has to step out of it, and somebody
 * else holds the seat meanwhile.</li>
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
 * <p>All five stay optimistic: none of them accounts for competences, for the
 * daily-rest constraint, or for the fact that a real plan spreads work far
 * below the legal ceilings. They are a recruitment floor to exceed, never a
 * target — the exact answer only comes from a real solve.</p>
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

    /** Which of the bounds ended up setting {@link StaffingSummary#minimumTotal()}. */
    public enum BorneRetenue {
        PIC_SIMULTANE,
        PIC_AVEC_PAUSE,
        CHARGE_HORAIRE,
        ROTATION_JOURS,
        COUPURE_REPAS
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
                "standsOuverts"
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
            int disponibles) {}

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
     * @param capaciteHeuresParAnimateur what one animateur may work that week:
     *                      the weekly ceiling, capped by
     *                      {@code joursTravaillables × durée quotidienne max}
     * @param chargeTotal   {@code heures / capaciteHeuresParAnimateur}
     * @param joursPersonne sum of the days' {@code minimumJour}: the
     *                      (animateur, jour travaillé) pairs the week requires
     * @param rotationTotal {@code joursPersonne / joursTravaillables}
     */
    @Schema(
            requiredProperties = {
                "capaciteHeuresParAnimateur",
                "chargeTotal",
                "heures",
                "jours",
                "joursPersonne",
                "joursTravaillables",
                "rotationTotal"
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
            int rotationTotal) {}

    @Schema(
            requiredProperties = {
                "capaciteHeuresParAnimateur",
                "chargeTotal",
                "dureeHebdomadaireMaxMinutes",
                "dureeQuotidienneMaxMinutes",
                "indisponibilitesDeclarees",
                "joursTravaillesMaxParSemaine",
                "minimumAvecIndisponibilites",
                "minimumMajeurs",
                "minimumMineurs",
                "minimumTotal",
                "nombreSemaines",
                "pauseMinimaleMinutes",
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
            int minimumMajeurs,
            int minimumMineurs,
            int pauseMinimaleMinutes,
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
                "specialistes"
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
     */
    @Schema(
            requiredProperties = {
                "animateursTotal",
                "manquePolyvalents",
                "manqueTotal",
                "polyvalents",
                "siegesNonAttribues",
                "siegesReservesAuxPolyvalents",
                "typologieNinjaDefinie"
            })
    public record CompetenceStaffing(
            List<TypologieStaffing> parTypologie,
            int polyvalents,
            int siegesNonAttribues,
            int siegesReservesAuxPolyvalents,
            int manqueTotal,
            int manquePolyvalents,
            int animateursTotal,
            boolean typologieNinjaDefinie) {}

    /** Half-open interval of one seat, in minutes from the start of its day. */
    private record Siege(LocalDate date, int debut, int fin, Stand stand) {}

    /** What one event day demands, before any weekly reasoning. */
    private record BesoinJour(
            LocalDate date, double heures, int picSimultane, int picAvecPause, int picRepas, int minimum) {}

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
            int pauseMinimaleMinutes) {
        return analyze(
                postes,
                animateurs,
                typologies,
                dureeHebdomadaireMaxMinutes,
                pauseMinimaleMinutes,
                animateurs == null || animateurs.isEmpty() ? List.of(ReferentielManquant.ANIMATEURS) : List.of(),
                List.of());
    }

    /** The bounds without meal windows: what a caller that has not read them asks for. */
    public StaffingSummary analyze(
            List<PosteAffectation> postes,
            List<Animateur> animateurs,
            List<TypologieItem> typologies,
            int dureeHebdomadaireMaxMinutes,
            int pauseMinimaleMinutes,
            List<ReferentielManquant> referentielsManquants) {
        return analyze(
                postes,
                animateurs,
                typologies,
                dureeHebdomadaireMaxMinutes,
                pauseMinimaleMinutes,
                referentielsManquants,
                List.of());
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
            int pauseMinimaleMinutes,
            List<ReferentielManquant> referentielsManquants,
            List<FenetreRepas> fenetresRepas) {
        List<FenetreRepas> fenetres = fenetresRepas == null ? List.of() : fenetresRepas;
        List<Siege> sieges = sieges(postes);
        Map<LocalDate, BesoinJour> besoins = besoinsByDate(sieges, pauseMinimaleMinutes, fenetres);
        Bornes bornes = bornes(besoins, dureeHebdomadaireMaxMinutes);

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

        List<Animateur> connus = animateurs == null ? List.of() : animateurs;
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
                    disponibles(connus, besoin.date())));
        }

        // The critical day is the one that needs the most people, which is the
        // day-level minimum and no longer the raw peak — a long, flat day can
        // out-demand a spiky one.
        JourStaffing jourCritique = parJour.stream()
                .max(Comparator.comparingInt(JourStaffing::minimumJour).thenComparingInt(JourStaffing::picAvecPause))
                .orElse(null);

        int minimumMajeurs = (int) Math.ceil(bornes.minimumTotal() * partMajeurs(sieges));
        return new StaffingSummary(
                List.copyOf(parJour),
                bornes.parSemaine(),
                bornes.semaineCritique(),
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
                minimumMajeurs,
                bornes.minimumTotal() - minimumMajeurs,
                pauseMinimaleMinutes,
                dureeHebdomadaireMaxMinutes,
                PlafondsLegauxMajeurs.DUREE_QUOTIDIENNE_MAX_MINUTES,
                PlafondsLegauxMajeurs.JOURS_TRAVAILLES_MAX_PAR_SEMAINE,
                bottleneckPerCategory(
                        sieges, connus, typologies, dureeHebdomadaireMaxMinutes, pauseMinimaleMinutes, fenetres),
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
            List<Siege> sieges,
            List<Animateur> animateurs,
            List<TypologieItem> typologies,
            int dureeHebdomadaireMaxMinutes,
            int pauseMinimaleMinutes,
            List<FenetreRepas> fenetres) {
        List<Animateur> connus = animateurs == null ? List.of() : animateurs;
        List<TypologieItem> referentiel = typologies == null ? List.of() : typologies;

        String ninja = referentiel.stream()
                .filter(TypologieItem::ninja)
                .map(TypologieItem::id)
                .findFirst()
                .orElse(null);
        Map<String, String> labels = new LinkedHashMap<>();
        referentiel.forEach(typologie -> labels.put(typologie.id(), typologie.label()));

        Map<String, List<Siege>> parTypologie = new LinkedHashMap<>();
        int siegesNonAttribues = 0;
        int siegesReservesAuxPolyvalents = 0;
        for (Siege siege : sieges) {
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

        Map<String, Integer> specialistes = new LinkedHashMap<>();
        int polyvalents = 0;
        for (Animateur animateur : connus) {
            Map<String, ?> competences = animateur.getCompetences() == null ? Map.of() : animateur.getCompetences();
            // Declared competences only: a ninja is eligible everywhere, but
            // counting them in every category would add one person to every
            // pool at once. They are the shared reserve below instead.
            competences.keySet().forEach(id -> specialistes.merge(id, 1, Integer::sum));
            if (animateur.isNinja() || (ninja != null && competences.containsKey(ninja))) {
                polyvalents++;
            }
        }

        List<TypologieStaffing> lignes = new ArrayList<>();
        for (Map.Entry<String, List<Siege>> entree : parTypologie.entrySet()) {
            String id = entree.getKey();
            Bornes bornes = bornes(
                    besoinsByDate(entree.getValue(), pauseMinimaleMinutes, fenetres), dureeHebdomadaireMaxMinutes);
            int disponibles = specialistes.getOrDefault(id, 0);
            int manque = connus.isEmpty() ? 0 : Math.max(0, bornes.minimumTotal() - disponibles);
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
                    bornes.minimumTotal(),
                    bornes.borneRetenue(),
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
                siegesNonAttribues,
                siegesReservesAuxPolyvalents,
                lignes.stream().mapToInt(TypologieStaffing::manque).sum(),
                lignes.stream()
                        .filter(TypologieStaffing::ninja)
                        .mapToInt(TypologieStaffing::manque)
                        .sum(),
                connus.size(),
                ninja != null);
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
            Collection<Siege> sieges, int pauseMinimaleMinutes, List<FenetreRepas> fenetres) {
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
            int picAvecPause = pic(entree.getValue(), pauseMinimaleMinutes);
            // A day is also bounded by its sheer volume: nobody works more than
            // the daily legal ceiling, so 600 person-hours need 60 people
            // whatever the shape of the day.
            int parLesHeures = (int) Math.ceil(heures * 60 / PlafondsLegauxMajeurs.DUREE_QUOTIDIENNE_MAX_MINUTES);
            int picRepas = 0;
            for (FenetreRepas fenetre : fenetres) {
                picRepas = Math.max(picRepas, picRepas(entree.getValue(), fenetre));
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

    private static Bornes bornes(Map<LocalDate, BesoinJour> besoins, int dureeHebdomadaireMaxMinutes) {
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
                            dureeHebdomadaireMaxMinutes,
                            (long) joursTravaillables * PlafondsLegauxMajeurs.DUREE_QUOTIDIENNE_MAX_MINUTES)
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
                    joursTravaillables > 0 ? (int) Math.ceil((double) joursPersonne / joursTravaillables) : 0));
        }
        parSemaine.sort(Comparator.comparing(SemaineStaffing::debut));

        int chargeTotal =
                parSemaine.stream().mapToInt(SemaineStaffing::chargeTotal).max().orElse(0);
        int rotationTotal = parSemaine.stream()
                .mapToInt(SemaineStaffing::rotationTotal)
                .max()
                .orElse(0);
        int minimumTotal = Math.max(
                Math.max(Math.max(picSimultane, picAvecPause), picRepas), Math.max(chargeTotal, rotationTotal));
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
                minimumTotal,
                borneRetenue(picSimultane, picAvecPause, chargeTotal, rotationTotal, picRepas),
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
        return picAvecPause >= picSimultane ? BorneRetenue.PIC_AVEC_PAUSE : BorneRetenue.PIC_SIMULTANE;
    }

    /**
     * Share of the seats that an adult has to hold: all of them on a
     * {@code reserveMajeurs} stand, half of them (rounded up, per stand and
     * per créneau) everywhere else — the same rule the seats themselves are
     * generated under. Defaults to 0.5 when there is nothing to count.
     */
    private static double partMajeurs(List<Siege> sieges) {
        if (sieges.isEmpty()) {
            return 0.5;
        }
        // Group by stand and window, so "half the seats, rounded up" is
        // applied to a real group of simultaneous seats and not to the
        // event's grand total.
        Map<String, int[]> byGroup = new LinkedHashMap<>();
        for (Siege siege : sieges) {
            String key = (siege.stand() == null ? "?" : siege.stand().getId()) + "@" + siege.date() + "#"
                    + siege.debut() + "-" + siege.fin();
            int[] compteur = byGroup.computeIfAbsent(key, ignored -> new int[] {0, 0});
            compteur[0]++;
            compteur[1] = siege.stand() != null && siege.stand().isReserveMajeurs() ? 1 : 0;
        }
        int total = 0;
        int majeurs = 0;
        for (int[] compteur : byGroup.values()) {
            total += compteur[0];
            majeurs += compteur[1] == 1 ? compteur[0] : (compteur[0] + 1) / 2;
        }
        return total == 0 ? 0.5 : (double) majeurs / total;
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
        int[] occupation = new int[pas];
        for (int i = 0; i < evenements.size(); i++) {
            courant += evenements.get(i)[1];
            int instant = evenements.get(i)[0];
            if (i + 1 < evenements.size() && evenements.get(i + 1)[0] == instant) {
                // Not done with this instant yet: the count is only meaningful
                // once every event sharing it has been applied.
                continue;
            }
            int prochain = i + 1 < evenements.size() ? evenements.get(i + 1)[0] : Integer.MAX_VALUE;
            if (instant < ouverture) {
                avant = Math.max(avant, courant);
            }
            if (prochain > fermeture) {
                apres = Math.max(apres, courant);
            }
            for (int k = 0; k < pas; k++) {
                if (instant <= grille[k] && grille[k] < prochain) {
                    occupation[k] = courant;
                }
            }
        }

        int total = avant + apres;
        for (int occupe : occupation) {
            total += occupe;
        }
        return Math.ceilDiv(total, pas + 1);
    }

    private static List<Siege> sieges(List<PosteAffectation> postes) {
        List<Siege> sieges = new ArrayList<>();
        for (PosteAffectation poste : postes) {
            Creneau creneau = poste.getCreneau();
            if (creneau == null || creneau.getDate() == null) {
                continue;
            }
            LocalTime debut = poste.heureDebutEffectif();
            if (debut == null) {
                continue;
            }
            int duree = poste.getDureeEffectiveMinutes();
            if (duree <= 0) {
                continue;
            }
            int debutMinutes = debut.toSecondOfDay() / 60;
            // A window running past midnight stays on its own day, with an end
            // beyond 24 h — the peak of a night slot belongs to the evening it
            // started, not to the next morning.
            sieges.add(new Siege(creneau.getDate(), debutMinutes, debutMinutes + duree, poste.getStand()));
        }
        return sieges;
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
