package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer.ReferentielManquant;
import dev.sylvain.planning.solver.EligibleAnimateurMoveFilter;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * How much slack the event has, day by day and timeslot by timeslot: the
 * animateurs available at that moment, minus the seats still to staff.
 *
 * <p>The margin already existed on two other screens, at two scales neither of
 * which answers « when are we short ». {@link StaffingAnalyzer} proves a
 * recruitment floor over the whole event and bounds per day;
 * {@code PlanningWhatIf#creneauAvailability} — the bench — answers in full for
 * <b>one</b> seat of <b>one</b> timeslot. Between the two, nothing said that
 * Saturday 20:00 → midnight is the only moment at −2, which is the question
 * asked when recruiting, when closing one opening window, or when deciding
 * whether a withdrawal can be absorbed.</p>
 *
 * <h2>Two modes, two definitions of « available »</h2>
 *
 * <ul>
 * <li><b>{@link Mode#AVANT}</b> — raw capacity against the need, read on the
 * seats a solve would have to fill. Available means « has not declared this
 * date unavailable », and the need is every seat of the cell. Nothing else is
 * checked: there is no plan yet to check it against, and this mode is read
 * before the first solve, often before the roster is complete.</li>
 * <li><b>{@link Mode#APRES}</b> — who is really free, on the persisted plan.
 * Available means: not unavailable that date, not holding a seat that overlaps
 * the cell, rested — no assignment within the legal break either side of it —
 * and eligible for at least one of the cell's seats as far as the (seat,
 * animateur) pair alone can tell. The need is what the plan left empty, since
 * whoever holds a seat is already out of the available count: counting every
 * seat again would subtract the same people twice.</li>
 * </ul>
 *
 * <p>The eligibility of the second mode is {@link EligibleAnimateurMoveFilter},
 * the very predicate the solver's move filters and the bench read, so somebody
 * counted here as free is somebody the bench would list as available for a
 * seat of that cell. The converse is not claimed, and that is the deliberate
 * approximation of this screen: the bench also asks the score director what
 * the rest of the plan says (daily and weekly caps, adult supervision,
 * appreciation), which cannot be paid once per animateur × timeslot over a
 * whole grid. Everything this leaves out can only <em>lower</em> the real
 * margin, so a cell reported negative certainly is, while a comfortable cell
 * is a promise this screen cannot fully keep — the same optimism as the bounds
 * of {@link StaffingAnalyzer} and the substitutes of {@link FragiliteAnalyzer}.</p>
 *
 * <h2>What a cell is</h2>
 *
 * <p>A column is a timeslot of the grid — a distinct {@code (heureDebut,
 * heureFin)} pair — and never a fixed hour, so the reading holds both on an
 * opening-span grid (one long column a day) and on shifts. A row is an event
 * day. A cell exists only where seats do: a day carrying no seat on a column
 * has nothing to say, which the screen renders as a hole rather than as a
 * zero.</p>
 *
 * <p>Competences are deliberately out of scope, in both modes: the per-category
 * reading is {@link StaffingAnalyzer.CompetenceStaffing}, on the Besoin screen,
 * and replaying it per timeslot would multiply its two attribution
 * approximations by the number of columns.</p>
 */
@ApplicationScoped
public class MargeAnalyzer {

    /** Which capacity the margin is computed against. */
    public enum Mode {
        /** Before a solve: raw capacity minus the seats to fill. */
        AVANT,
        /** After a solve: the people really free minus the seats left empty. */
        APRES
    }

    /**
     * One column of the heatmap: a timeslot of the grid, identified by its
     * hours rather than by a créneau id, since the same hours recur day after
     * day and a column has to line them up.
     */
    @Schema(requiredProperties = {"debut", "fin"})
    public record TrancheMarge(LocalTime debut, LocalTime fin) {}

    /**
     * One cell: what the grid holds on a given day at a given timeslot.
     *
     * @param creneauId  the timeslot the cell is read from — what the screen
     *                   passes to the bench when the cell is clicked. The
     *                   smallest id when several créneaux share these hours on
     *                   the same day, which the active group makes rare but not
     *                   impossible
     * @param sieges     seats the cell holds
     * @param siegesPourvus seats somebody already holds — always zero in
     *                   {@link Mode#AVANT}, where no assignment is read
     * @param besoin     seats still to staff: every seat before a solve, the
     *                   unfilled ones after
     * @param disponibles animateurs counted as available, see the class javadoc
     * @param marge      {@code disponibles - besoin}
     */
    @Schema(
            requiredProperties = {
                "besoin",
                "creneauId",
                "debut",
                "disponibles",
                "fin",
                "jour",
                "marge",
                "sieges",
                "siegesPourvus"
            })
    public record CelluleMarge(
            LocalDate date,
            int jour,
            LocalTime debut,
            LocalTime fin,
            long creneauId,
            int sieges,
            int siegesPourvus,
            int besoin,
            int disponibles,
            int marge) {}

    /**
     * One event day, with the cells it holds and the synthesis line the screen
     * shows under the grid.
     *
     * @param pireCellule the worst cell of the day — the tightest margin, and
     *                    on a tie the earliest. Never {@code null}: a day with
     *                    no cell is not emitted at all
     */
    @Schema(requiredProperties = {"cellules", "jour"})
    public record JourMarge(LocalDate date, int jour, List<CelluleMarge> cellules, CelluleMarge pireCellule) {}

    /**
     * @param tranches             the columns, earliest start first
     * @param jours                the rows, one per day holding at least one seat
     * @param animateursTotal      animateurs the referential holds — what every
     *                             {@code disponibles} is a subset of
     * @param cellulesDeficitaires cells whose margin is negative
     * @param pireCellule          the tightest cell of the whole event, or
     *                             {@code null} when there is no cell at all
     * @param pauseMinimaleMinutes the legal break between two shifts, which is
     *                             the buffer {@link Mode#APRES} keeps either
     *                             side of a cell. Carried so the screen can say
     *                             what « rested » meant
     * @param referentielsManquants what the edition has not filled in yet, named
     *                             rather than shown as a zero (issue #416)
     */
    @Schema(
            requiredProperties = {
                "animateursTotal",
                "cellulesDeficitaires",
                "jours",
                "message",
                "mode",
                "pauseMinimaleMinutes",
                "referentielsManquants",
                "tranches"
            })
    public record RapportMarge(
            Mode mode,
            List<TrancheMarge> tranches,
            List<JourMarge> jours,
            int animateursTotal,
            int cellulesDeficitaires,
            CelluleMarge pireCellule,
            int pauseMinimaleMinutes,
            List<ReferentielManquant> referentielsManquants,
            String message) {}

    /** Identity of a cell: one day, one pair of hours. */
    private record CelluleKey(LocalDate date, LocalTime debut, LocalTime fin) {}

    /** The seats of one cell, before anything is counted against them. */
    private static final class Cellule {
        private final Creneau creneau;
        private final List<PosteAffectation> postes = new ArrayList<>();
        private long creneauId;

        private Cellule(Creneau creneau) {
            this.creneau = creneau;
            this.creneauId = creneau.getId() == null ? 0L : creneau.getId();
        }
    }

    /**
     * Half-open interval in minutes since the epoch day — <b>absolute</b>, not
     * relative to a day, for the reason {@link FragiliteAnalyzer} spells out:
     * a 22:00 → 02:00 seat has to be comparable with the 00:00 → 04:00 seat of
     * the next day, and two per-day buckets never compare them.
     */
    private record Interval(long debut, long fin) {

        private boolean overlaps(long autreDebut, long autreFin) {
            return debut < autreFin && autreDebut < fin;
        }
    }

    private static final long MINUTES_PAR_JOUR = 24 * 60L;

    /** Tightest margin first, and on a tie the earliest cell of the day. */
    private static final Comparator<CelluleMarge> ORDRE_PIRE = Comparator.comparingInt(CelluleMarge::marge)
            .thenComparing(CelluleMarge::date, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(CelluleMarge::debut, Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * @param postes                the seats to read: those a solve would have
     *                              to fill in {@link Mode#AVANT}, those the
     *                              persisted plan holds in {@link Mode#APRES}
     * @param animateurs            the roster; an empty one still yields the
     *                              grid, with every margin equal to minus the
     *                              need
     * @param pauseMinimaleMinutes  the legal break between two shifts
     * @param pauseSurPoste         the organiser's « la pause se prend sur le
     *                              poste » declaration, read by the eligibility
     *                              filter exactly as the constraints read it
     * @param referentielsManquants what the edition has not filled in yet
     */
    public RapportMarge analyze(
            Mode mode,
            List<PosteAffectation> postes,
            List<Animateur> animateurs,
            int pauseMinimaleMinutes,
            boolean pauseSurPoste,
            List<ReferentielManquant> referentielsManquants) {
        Mode retenu = mode == null ? Mode.AVANT : mode;
        List<Animateur> connus = animateurs == null ? List.of() : animateurs;
        Map<CelluleKey, Cellule> cellules = groupSeats(postes == null ? List.of() : postes);
        Map<String, List<Interval>> occupation = retenu == Mode.APRES ? busyIntervals(postes) : Map.of();

        List<TrancheMarge> tranches = cellules.keySet().stream()
                .map(cle -> new TrancheMarge(cle.debut(), cle.fin()))
                .distinct()
                .sorted(Comparator.comparing(TrancheMarge::debut).thenComparing(TrancheMarge::fin))
                .toList();

        Map<LocalDate, List<CelluleMarge>> parJour = new TreeMap<>();
        for (Map.Entry<CelluleKey, Cellule> entree : cellules.entrySet()) {
            CelluleMarge cellule =
                    evaluate(retenu, entree.getValue(), connus, occupation, pauseMinimaleMinutes, pauseSurPoste);
            parJour.computeIfAbsent(entree.getKey().date(), date -> new ArrayList<>())
                    .add(cellule);
        }

        List<JourMarge> jours = new ArrayList<>(parJour.size());
        for (Map.Entry<LocalDate, List<CelluleMarge>> entree : parJour.entrySet()) {
            List<CelluleMarge> lignes = entree.getValue().stream()
                    .sorted(Comparator.comparing(CelluleMarge::debut).thenComparing(CelluleMarge::fin))
                    .toList();
            jours.add(new JourMarge(
                    entree.getKey(),
                    lignes.getFirst().jour(),
                    lignes,
                    lignes.stream().min(ORDRE_PIRE).orElseThrow()));
        }

        CelluleMarge pire =
                jours.stream().map(JourMarge::pireCellule).min(ORDRE_PIRE).orElse(null);
        int deficitaires = (int) jours.stream()
                .flatMap(jour -> jour.cellules().stream())
                .filter(cellule -> cellule.marge() < 0)
                .count();

        return new RapportMarge(
                retenu,
                tranches,
                List.copyOf(jours),
                connus.size(),
                deficitaires,
                pire,
                pauseMinimaleMinutes,
                referentielsManquants == null ? List.of() : List.copyOf(referentielsManquants),
                message(retenu, jours, connus, deficitaires, pire));
    }

    /**
     * Seats grouped by day and hours. A seat whose timeslot carries no date or
     * no hours is dropped rather than bucketed under a guess: it has no column
     * to sit in, and inventing one would move the margin of a cell that does
     * exist.
     */
    private static Map<CelluleKey, Cellule> groupSeats(List<PosteAffectation> postes) {
        Map<CelluleKey, Cellule> cellules = new LinkedHashMap<>();
        for (PosteAffectation poste : postes) {
            Creneau creneau = poste.getCreneau();
            if (creneau == null
                    || creneau.getDate() == null
                    || creneau.getHeureDebut() == null
                    || creneau.getHeureFin() == null) {
                continue;
            }
            CelluleKey cle = new CelluleKey(creneau.getDate(), creneau.getHeureDebut(), creneau.getHeureFin());
            Cellule cellule = cellules.computeIfAbsent(cle, ignore -> new Cellule(creneau));
            if (creneau.getId() != null && (cellule.creneauId == 0L || creneau.getId() < cellule.creneauId)) {
                cellule.creneauId = creneau.getId();
            }
            cellule.postes.add(poste);
        }
        return cellules;
    }

    /** When each animateur is already on duty, read from the seats the plan filled. */
    private static Map<String, List<Interval>> busyIntervals(List<PosteAffectation> postes) {
        Map<String, List<Interval>> occupation = new LinkedHashMap<>();
        for (PosteAffectation poste : postes) {
            Animateur animateur = poste.getAnimateur();
            Creneau creneau = poste.getCreneau();
            if (animateur == null || creneau == null || creneau.getDate() == null) {
                continue;
            }
            LocalTime debut = poste.heureDebutEffectif();
            if (debut == null) {
                continue;
            }
            long start = absolute(creneau.getDate(), debut);
            occupation
                    .computeIfAbsent(animateur.getId(), id -> new ArrayList<>())
                    .add(new Interval(start, start + poste.getDureeEffectiveMinutes()));
        }
        return occupation;
    }

    private CelluleMarge evaluate(
            Mode mode,
            Cellule cellule,
            List<Animateur> animateurs,
            Map<String, List<Interval>> occupation,
            int pauseMinimaleMinutes,
            boolean pauseSurPoste) {
        Creneau creneau = cellule.creneau;
        // Owed seats only (issue #505): a renfort is a capacity the stand
        // declared, never a need. Counting one here would turn a margin the
        // organiser put there on purpose into a shortfall this screen shouts
        // about — the very false alarm ADR 0046 exists to avoid. Somebody
        // holding a renfort is still out of `disponibles` below: they are
        // working, whatever the seat is called.
        List<PosteAffectation> dus =
                cellule.postes.stream().filter(poste -> !poste.isOptionnel()).toList();
        int sieges = dus.size();
        int pourvus =
                (int) dus.stream().filter(poste -> poste.getAnimateur() != null).count();
        int besoin = mode == Mode.AVANT ? sieges : sieges - pourvus;
        long debut = absolute(creneau.getDate(), creneau.getHeureDebut());
        long fin = debut + creneau.getDureeMinutes();
        int disponibles = 0;
        for (Animateur animateur : animateurs) {
            if (isAvailable(mode, animateur, cellule, occupation, debut, fin, pauseMinimaleMinutes, pauseSurPoste)) {
                disponibles++;
            }
        }
        return new CelluleMarge(
                creneau.getDate(),
                creneau.getJour(),
                creneau.getHeureDebut(),
                creneau.getHeureFin(),
                cellule.creneauId,
                sieges,
                mode == Mode.AVANT ? 0 : pourvus,
                besoin,
                disponibles,
                disponibles - besoin);
    }

    private static boolean isAvailable(
            Mode mode,
            Animateur animateur,
            Cellule cellule,
            Map<String, List<Interval>> occupation,
            long debut,
            long fin,
            int pauseMinimaleMinutes,
            boolean pauseSurPoste) {
        if (animateur.isIndisponibleOn(cellule.creneau.getDate())) {
            return false;
        }
        if (mode == Mode.AVANT) {
            return true;
        }
        // The buffer is what « rested » means here: a shift ending minutes
        // before this cell leaves nobody free to take it, however empty the
        // hours look side by side.
        long marge = Math.max(pauseMinimaleMinutes, 0);
        for (Interval occupe : occupation.getOrDefault(animateur.getId(), List.of())) {
            if (occupe.overlaps(debut - marge, fin + marge)) {
                return false;
            }
        }
        return cellule.postes.stream()
                .anyMatch(poste -> EligibleAnimateurMoveFilter.isEligible(poste, animateur, pauseSurPoste));
    }

    private static long absolute(LocalDate date, LocalTime heure) {
        return date.toEpochDay() * MINUTES_PAR_JOUR + heure.toSecondOfDay() / 60L;
    }

    /**
     * One sentence for the screen's banner, and the only place this class words
     * anything: the grid says the rest. It names the tightest cell because that
     * is the whole reading — a margin that holds everywhere is one number, a
     * margin that fails once is a date and an hour.
     */
    private static String message(
            Mode mode, List<JourMarge> jours, List<Animateur> animateurs, int deficitaires, CelluleMarge pire) {
        if (animateurs.isEmpty()) {
            return "Aucun animateur n'est saisi : la capacité disponible ne peut pas être mesurée.";
        }
        if (jours.isEmpty()) {
            return mode == Mode.AVANT
                    ? "Aucun siège à couvrir : vérifiez les horaires des stands et la grille de créneaux."
                    : "Aucun planning persisté : lancez une résolution pour lire la marge réelle.";
        }
        if (deficitaires == 0) {
            return "Aucune tranche en déficit : la plus tendue est %s, marge %+d.".formatted(situe(pire), pire.marge());
        }
        return "%d tranche(s) en déficit, la plus tendue %s à %+d.".formatted(deficitaires, situe(pire), pire.marge());
    }

    private static String situe(CelluleMarge cellule) {
        return "J%d %s-%s".formatted(cellule.jour(), cellule.debut(), cellule.fin());
    }
}
