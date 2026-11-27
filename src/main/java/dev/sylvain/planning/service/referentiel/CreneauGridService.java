package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.JoursFeries;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.VacationVerrouillee;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.FeasibilityReport;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer.Anomaly;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Checks the edition's <b>grid of créneaux</b>: auditing a grid for the
 * mistakes nothing else catches. Producing one is {@link GenerateurCreneaux}'s
 * (ADR 0126), which reads this verdict on every preview.
 *
 * <p>Written for the MCP tools (issue: assisted créneau creation), where a
 * single sentence can produce sixty rows at once and a silent typo is
 * expensive — but deliberately kept free of any MCP dependency so the
 * Créneaux screen can call it too.</p>
 *
 * <h2>Division of labour with the existing analyzers</h2>
 * <p>Two analyzers already audit neighbouring ground and are <em>reused</em>
 * rather than reimplemented: {@link OuvertureStandsAnalyzer} judges the
 * stand↔créneau relationship (a stand nobody will ever staff, a window that
 * overlaps no créneau, an unusably short opening) and {@link FeasibilityAnalyzer}
 * judges headcount (a créneau demanding more animateurs than exist). What
 * neither looks at is the grid's own internal consistency — duplicates,
 * overlaps, holes, durations — which is exactly what {@link #validate} adds
 * before folding the other two reports in.</p>
 *
 * <p>Nothing here writes: validation returns a report.</p>
 */
@ApplicationScoped
public class CreneauGridService {

    /**
     * A date this far from every other dated créneau is treated as a typo
     * rather than as a deliberate gap. Sized to clear a legitimate pause — a
     * event running over two weekends may genuinely skip five or six days —
     * while still catching the mistakes that actually happen, which are
     * month-or-year slips landing a créneau weeks or months away.
     */
    static final int ECART_DATE_ISOLEE_JOURS = 7;

    /** Minutes in a day, for the durations of créneaux that cross midnight. */
    private static final int MINUTES_PAR_JOUR = 24 * 60;

    private final FeasibilityAnalyzer feasibilityAnalyzer;

    /**
     * Only for the constraint states: the grid report estimates how many seats
     * the team could hold, and that estimate changes with the supervision of
     * minors (issue #595).
     */
    private final ParametresService parametres;

    @Inject
    public CreneauGridService(FeasibilityAnalyzer feasibilityAnalyzer, ParametresService parametres) {
        this.feasibilityAnalyzer = feasibilityAnalyzer;
        this.parametres = parametres;
    }

    /* ------------------------------ Validation ------------------------------ */

    /**
     * Audits {@code creneaux}, then folds in the stand-opening and feasibility
     * reports so a caller gets one verdict instead of three.
     *
     * <p>A créneau is a <b>vacation</b> — one shift somebody will actually
     * work — and nothing else. There used to be a second reading, the daily
     * opening amplitude a découpage sliced before solving, and the validator
     * had to be told which one it was looking at: the same overlap is a
     * data-entry mistake between two amplitudes and the normal shape of two
     * staggered vacations. Now that events declare their opening hours and
     * project them through journées types, that ambiguity is gone — and with
     * it the mode the caller had to supply.</p>
     */
    public RapportGrille validate(
            List<Creneau> creneaux,
            List<Stand> stands,
            List<Animateur> animateurs,
            ParametresLegaux legaux,
            List<VerrouillagePlanning> verrouillages) {
        List<GridAnomaly> anomalies = new ArrayList<>(gridAnomalies(creneaux, legaux, verrouillages));
        anomalies.addAll(holidayVacations(creneaux, animateurs));
        anomalies.sort(ORDRE_ANOMALIES);

        List<Anomaly> ouvertures = openingAnomalies(creneaux, stands);
        FeasibilityReport faisabilite = stands.isEmpty() || creneaux.isEmpty()
                ? null
                : feasibilityAnalyzer.analyze(
                        animateurs,
                        stands,
                        creneaux,
                        List.of(),
                        FeasibilityAnalyzer.encadrementMineursActif(parametres.disabledContraintes()),
                        // The grid is what decides whether the cap holds: a
                        // variant previewed here is judged on it like the
                        // edition's own grid. No plan in place — it is a
                        // hypothesis.
                        FeasibilityAnalyzer.ConsecutiveDaysRule.of(
                                parametres.disabledContraintes(),
                                parametres.niveauxObligatoires(),
                                parametres.qualite()),
                        FeasibilityAnalyzer.PlanContext.NONE);

        return new RapportGrille(creneaux.size(), anomalies, ouvertures, faisabilite);
    }

    /**
     * The opening report's anomalies {@link #validate} folds in.
     */
    static List<Anomaly> openingAnomalies(List<Creneau> creneaux, List<Stand> stands) {
        if (stands.isEmpty() || creneaux.isEmpty()) {
            return List.of();
        }
        return OuvertureStandsAnalyzer.analyze(stands, creneaux).anomalies();
    }

    /**
     * The grid's own inconsistencies, sorted worst first — what
     * {@link #validate} reports before it folds in the openings and the
     * feasibility. Static and cheap: the coherence checklist reads it on every
     * visit of the home screen, where the feasibility is already computed
     * elsewhere and must not be paid twice.
     */
    public static List<GridAnomaly> gridAnomalies(
            List<Creneau> creneaux, ParametresLegaux legaux, List<VerrouillagePlanning> verrouillages) {
        List<GridAnomaly> anomalies = new ArrayList<>();
        List<Creneau> dates =
                creneaux.stream().filter(creneau -> creneau.getDate() != null).toList();

        anomalies.addAll(unitAnomalies(creneaux, legaux));
        anomalies.addAll(anomaliesByDay(dates));
        anomalies.addAll(datesIsolees(dates));
        anomalies.addAll(verrouillagesWithoutVacation(creneaux, verrouillages));

        anomalies.sort(ORDRE_ANOMALIES);
        return anomalies;
    }

    /** Worst first, then by date, then by wording: the order every report of the grid is read in. */
    private static final Comparator<GridAnomaly> ORDRE_ANOMALIES = Comparator.comparingInt(
                    (GridAnomaly anomalie) -> anomalie.severite().ordinal())
            .thenComparing(anomalie -> anomalie.date() != null ? anomalie.date() : LocalDate.MIN)
            .thenComparing(GridAnomaly::message);

    /**
     * The vacations falling on a public holiday, one line per such date — but
     * only on a date where the edition counts at least one minor, judged on
     * that date, who has not declared it off: a minor may not work a public
     * holiday (art. L3164-6), so those seats are for adults only. An edition
     * without minors available that day hears nothing, since a festival
     * legitimately opens on 14 July. Never blocking: the hard rule
     * already exists, this only says it before the solve.
     *
     * <p>A count of minors and never a name: the sentence is read on screens
     * whose client keeps a log ({@code site/src/docs/installer/rgpd.md} §7).</p>
     */
    static List<GridAnomaly> holidayVacations(List<Creneau> creneaux, List<Animateur> animateurs) {
        Map<LocalDate, Integer> parDate = new TreeMap<>();
        for (Creneau creneau : creneaux) {
            if (JoursFeries.isFerieInFrance(creneau.getDate())) {
                parDate.merge(creneau.getDate(), 1, Integer::sum);
            }
        }
        List<GridAnomaly> anomalies = new ArrayList<>();
        parDate.forEach((date, vacations) -> {
            long mineurs = animateurs == null
                    ? 0
                    : animateurs.stream()
                            .filter(animateur -> animateur.isMineurOn(date) && !animateur.isIndisponibleOn(date))
                            .count();
            if (mineurs == 0) {
                return;
            }
            anomalies.add(new GridAnomaly(
                    SeveriteGrille.AVERTISSEMENT,
                    GridAnomalyType.VACATION_JOUR_FERIE,
                    date,
                    date + " (" + JoursFeries.label(date).orElse("jour férié") + ") : " + vacations
                            + " vacation(s) tombent un jour férié, où seuls les majeurs peuvent siéger (art."
                            + " L3164-6). L'édition compte " + mineurs
                            + " animateur(s) mineur(s) disponible(s) ce jour-là."));
        });
        return anomalies;
    }

    /**
     * The locks whose vacation this grid does not hold (issue #577).
     *
     * <p>Locks are no longer cascaded away with their créneau: they name the
     * vacation by its natural key and find it again when the day is recreated
     * unchanged. What this reports is the other case — the day did not come
     * back — and it reports it <b>before</b> the write when the caller is
     * previewing a derivation, which is exactly when « régénérer la grille »
     * is about to drop the two locks a validated swap posed.</p>
     *
     * <p>One line per vacation, with a count and never a name: these
     * sentences are read on screens whose client keeps a log (see
     * {@code site/src/docs/installer/rgpd.md} §7).</p>
     */
    private static List<GridAnomaly> verrouillagesWithoutVacation(
            List<Creneau> creneaux, List<VerrouillagePlanning> verrouillages) {
        if (verrouillages == null || verrouillages.isEmpty()) {
            return List.of();
        }
        Set<VacationVerrouillee> tenues = creneaux.stream()
                .filter(creneau -> creneau.getDate() != null)
                .map(creneau ->
                        new VacationVerrouillee(creneau.getDate(), creneau.getHeureDebut(), creneau.getHeureFin()))
                .collect(Collectors.toSet());
        Map<VacationVerrouillee, Integer> perdues = new LinkedHashMap<>();
        for (VerrouillagePlanning verrouillage : verrouillages) {
            VacationVerrouillee vacation = verrouillage.vacation();
            if (vacation != null && !tenues.contains(vacation)) {
                perdues.merge(vacation, 1, Integer::sum);
            }
        }
        List<GridAnomaly> anomalies = new ArrayList<>();
        perdues.forEach((vacation, nombre) -> anomalies.add(new GridAnomaly(
                SeveriteGrille.AVERTISSEMENT,
                GridAnomalyType.VERROUILLAGE_SANS_VACATION,
                vacation.date(),
                nombre + " verrouillage(s) visent la vacation " + vacation
                        + ", que la grille ne porte pas. Ils attendent qu'elle revienne : recréer la"
                        + " vacation à l'identique les remet en service, la supprimer pour de bon demande"
                        + " de les retirer depuis l'onglet Verrouillages des Règles du planning. Un échange"
                        + " validé pose deux de"
                        + " ces verrous.")));
        return anomalies;
    }

    /** Checks that need one créneau at a time: shape, then duration against the legal ceilings. */
    private static List<GridAnomaly> unitAnomalies(List<Creneau> creneaux, ParametresLegaux legaux) {
        List<GridAnomaly> anomalies = new ArrayList<>();
        for (Creneau creneau : creneaux) {
            anomalies.addAll(unitAnomalies(creneau, legaux));
        }
        return anomalies;
    }

    /** One créneau's shape, then its duration against the legal ceilings. */
    private static List<GridAnomaly> unitAnomalies(Creneau creneau, ParametresLegaux legaux) {
        if (creneau.getDate() == null || creneau.getHeureDebut() == null || creneau.getHeureFin() == null) {
            return List.of(new GridAnomaly(
                    SeveriteGrille.ERREUR,
                    GridAnomalyType.CRENEAU_INCOMPLET,
                    creneau.getDate(),
                    "Créneau incomplet : date, heure de début et heure de fin sont requises."));
        }
        int duree = creneau.getDureeMinutes();
        if (duree == 0) {
            return List.of(new GridAnomaly(
                    SeveriteGrille.ERREUR,
                    GridAnomalyType.DUREE_NULLE,
                    creneau.getDate(),
                    libelle(creneau) + " : durée nulle (début et fin identiques)."));
        }
        List<GridAnomaly> anomalies = new ArrayList<>();
        int amplitudeMaximaleLegale = MINUTES_PAR_JOUR - legaux.getReposQuotidienMinimalMinutes();
        if (duree > legaux.getDureeVacationMaxMinutes()) {
            anomalies.add(new GridAnomaly(
                    SeveriteGrille.AVERTISSEMENT,
                    GridAnomalyType.VACATION_TROP_LONGUE,
                    creneau.getDate(),
                    libelle(creneau) + " : vacation de " + duree + " min, au-delà du maximum de "
                            + legaux.getDureeVacationMaxMinutes()
                            + " min. Cette vacation contiendra une pause à relayer : vérifiez qu'un"
                            + " collègue tient une place sur le stand à ce moment-là, ou coupez la"
                            + " journée en deux vacations."));
        }
        if (duree > amplitudeMaximaleLegale) {
            anomalies.add(new GridAnomaly(
                    SeveriteGrille.ERREUR,
                    GridAnomalyType.REPOS_QUOTIDIEN_IMPOSSIBLE,
                    creneau.getDate(),
                    libelle(creneau) + " : vacation de " + duree + " min alors que le repos "
                            + "quotidien minimal de " + legaux.getReposQuotidienMinimalMinutes()
                            + " min plafonne une journée travaillée à " + amplitudeMaximaleLegale
                            + " min. Toute affectation sur ce créneau ferait un écart à une règle impérative."));
        }
        return anomalies;
    }

    /** Checks that need the whole day: duplicates, overlaps, and holes in the covered span. */
    private static List<GridAnomaly> anomaliesByDay(List<Creneau> creneaux) {
        List<GridAnomaly> anomalies = new ArrayList<>();
        for (Map.Entry<LocalDate, List<Creneau>> jour : byDate(creneaux).entrySet()) {
            List<Creneau> duJour = jour.getValue().stream()
                    .filter(creneau -> creneau.getHeureDebut() != null && creneau.getDureeMinutes() > 0)
                    .sorted(Comparator.comparing(Creneau::getHeureDebut))
                    .toList();
            anomalies.addAll(doublons(jour.getKey(), duJour));
            anomalies.addAll(trous(jour.getKey(), duJour));
        }
        return anomalies;
    }

    private static List<GridAnomaly> doublons(LocalDate date, List<Creneau> duJour) {
        List<GridAnomaly> anomalies = new ArrayList<>();
        Set<String> vus = new HashSet<>();
        for (Creneau creneau : duJour) {
            String signature = creneau.getHeureDebut() + "→" + creneau.getHeureFin();
            if (!vus.add(signature)) {
                anomalies.add(new GridAnomaly(
                        SeveriteGrille.ERREUR,
                        GridAnomalyType.DOUBLON,
                        date,
                        libelle(creneau) + " : créneau en double (mêmes heures)."));
            }
        }
        return anomalies;
    }

    /**
     * Gaps inside a day's covered span. Only the interior counts: a day
     * starting at 10:00 is not "missing" its morning, but a day covered
     * 10:00-12:00 then 14:00-20:00 leaves two hours nobody will ever be
     * scheduled on — which is either a deliberate closure or a forgotten row,
     * and worth one line either way.
     */
    private static List<GridAnomaly> trous(LocalDate date, List<Creneau> duJour) {
        if (duJour.size() < 2) {
            return List.of();
        }
        List<int[]> fusionnes = new ArrayList<>();
        for (Creneau creneau : duJour) {
            int[] intervalle = intervalle(creneau);
            if (!fusionnes.isEmpty() && intervalle[0] <= fusionnes.get(fusionnes.size() - 1)[1]) {
                int[] dernier = fusionnes.get(fusionnes.size() - 1);
                dernier[1] = Math.max(dernier[1], intervalle[1]);
            } else {
                fusionnes.add(new int[] {intervalle[0], intervalle[1]});
            }
        }
        List<GridAnomaly> anomalies = new ArrayList<>();
        for (int i = 1; i < fusionnes.size(); i++) {
            int debutTrou = fusionnes.get(i - 1)[1];
            int finTrou = fusionnes.get(i)[0];
            anomalies.add(new GridAnomaly(
                    SeveriteGrille.AVERTISSEMENT,
                    GridAnomalyType.TROU_DANS_LA_JOURNEE,
                    date,
                    date + " : rien entre " + heure(debutTrou) + " et " + heure(finTrou) + " (" + (finTrou - debutTrou)
                            + " min). Aucun stand ne peut être armé sur cette plage."));
        }
        return anomalies;
    }

    /**
     * Dates standing more than {@link #ECART_DATE_ISOLEE_JOURS} days away from
     * every other one. The mistake this catches is the month or year slip —
     * {@code 2026-08-06} typed for {@code 2026-07-06} — which produces a
     * perfectly valid créneau that no stand and no animateur will ever meet.
     */
    private static List<GridAnomaly> datesIsolees(List<Creneau> creneaux) {
        List<LocalDate> distinctes =
                creneaux.stream().map(Creneau::getDate).distinct().sorted().toList();
        if (distinctes.size() < 2) {
            return List.of();
        }
        List<GridAnomaly> anomalies = new ArrayList<>();
        for (int i = 0; i < distinctes.size(); i++) {
            long avant = i == 0 ? Long.MAX_VALUE : ChronoUnit.DAYS.between(distinctes.get(i - 1), distinctes.get(i));
            long apres = i == distinctes.size() - 1
                    ? Long.MAX_VALUE
                    : ChronoUnit.DAYS.between(distinctes.get(i), distinctes.get(i + 1));
            if (Math.min(avant, apres) > ECART_DATE_ISOLEE_JOURS) {
                anomalies.add(new GridAnomaly(
                        SeveriteGrille.AVERTISSEMENT,
                        GridAnomalyType.DATE_ISOLEE,
                        distinctes.get(i),
                        distinctes.get(i) + " est isolée de plus de " + ECART_DATE_ISOLEE_JOURS
                                + " jours du reste de la grille — erreur de mois ou d'année ?"));
            }
        }
        return anomalies;
    }

    /* ------------------------------ Diagnostic ------------------------------ */

    /**
     * Describes the grid currently in place: how many créneaux, over which
     * dates. The cheap first call — for
     * an assistant discovering an edition, or a screen opening on it — before
     * {@link #validate} says whether that grid holds up.
     *
     * <p>It used to answer a second question too: <em>how</em> the grid should
     * be read, amplitudes to slice or vacations to solve, guessed from the
     * median duration. There is only one reading left, so the guess and the
     * confidence flag that qualified it are gone with it.</p>
     */
    public static DiagnosticGrille diagnose(List<Creneau> creneaux) {
        if (creneaux.isEmpty()) {
            return new DiagnosticGrille(
                    0, null, null, "L'édition n'a aucun créneau : la grille est à créer.", List.of());
        }
        List<LocalDate> dates = creneaux.stream()
                .map(Creneau::getDate)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        int dureeMediane =
                mediane(creneaux.stream().map(Creneau::getDureeMinutes).sorted().toList());
        List<JoursFeries.PublicHoliday> feries = dates.stream()
                .flatMap(date -> JoursFeries.label(date).map(nom -> new JoursFeries.PublicHoliday(date, nom)).stream())
                .toList();

        return new DiagnosticGrille(
                creneaux.size(),
                first(dates),
                last(dates),
                creneaux.size() + " vacations sur " + dates.size() + " date(s), durée médiane de " + dureeMediane
                        + " min"
                        + "."
                        + (feries.isEmpty()
                                ? ""
                                : " " + feries.size() + " date(s) de la grille tombent un jour férié, où les"
                                        + " mineurs ne peuvent pas travailler."),
                feries);
    }

    /* -------------------------------- Sorties ------------------------------- */

    /** Severity ordered from the most to the least blocking; the enum order is the sort order. */
    public enum SeveriteGrille {
        /** The grid is malformed, or guarantees a hard-constraint violation. */
        ERREUR,
        /** Suspicious but possibly deliberate: worth one line, never a refusal. */
        AVERTISSEMENT
    }

    public enum GridAnomalyType {
        CRENEAU_INCOMPLET,
        DUREE_NULLE,
        DOUBLON,
        REPOS_QUOTIDIEN_IMPOSSIBLE,
        TROU_DANS_LA_JOURNEE,
        VACATION_TROP_LONGUE,
        DATE_ISOLEE,
        /** Locks naming a vacation this grid does not hold — see issue #577. */
        VERROUILLAGE_SANS_VACATION,
        /**
         * Vacations on a public holiday, on a date where the edition has a minor:
         * only adults may sit there. For information, never blocking.
         */
        VACATION_JOUR_FERIE
    }

    public record GridAnomaly(SeveriteGrille severite, GridAnomalyType type, LocalDate date, String message) {}

    /**
     * @param anomalies      the grid's own inconsistencies
     * @param ouvertures     {@link OuvertureStandsAnalyzer}'s stand↔créneau anomalies
     * @param faisabilite    {@link FeasibilityAnalyzer}'s headcount verdict, {@code null} when
     *                       there is nothing to judge (no stand, or no créneau)
     */
    @Schema(requiredProperties = {"nombreCreneaux"})
    public record RapportGrille(
            int nombreCreneaux, List<GridAnomaly> anomalies, List<Anomaly> ouvertures, FeasibilityReport faisabilite) {

        /** True when nothing blocking was found — warnings alone do not make a grid invalid. */
        public boolean hasNoBlockingAnomaly() {
            return anomalies.stream().noneMatch(anomalie -> anomalie.severite() == SeveriteGrille.ERREUR);
        }
    }

    /**
     * @param joursFeries the dates of the grid falling on a public holiday, named — the
     *                    grid check turns them into {@link GridAnomalyType#VACATION_JOUR_FERIE}
     *                    when a minor is on the team that day
     */
    @Schema(requiredProperties = {"joursFeries", "nombreCreneaux"})
    public record DiagnosticGrille(
            int nombreCreneaux,
            LocalDate premiereDate,
            LocalDate derniereDate,
            String explication,
            List<JoursFeries.PublicHoliday> joursFeries) {}

    /* -------------------------------- Outils -------------------------------- */

    private static Map<LocalDate, List<Creneau>> byDate(List<Creneau> creneaux) {
        Map<LocalDate, List<Creneau>> byDate = new LinkedHashMap<>();
        for (Creneau creneau : creneaux) {
            byDate.computeIfAbsent(creneau.getDate(), date -> new ArrayList<>()).add(creneau);
        }
        return byDate;
    }

    /**
     * Minute-of-day bounds, the end extended past midnight for a slot that
     * crosses it — so an overlap or a hole is computed on a monotonic axis
     * instead of wrapping around to a negative width.
     */
    private static int[] intervalle(Creneau creneau) {
        int debut = creneau.getHeureDebut().toSecondOfDay() / 60;
        return new int[] {debut, debut + creneau.getDureeMinutes()};
    }

    private static String heure(int minuteDuJour) {
        return LocalTime.ofSecondOfDay((minuteDuJour % MINUTES_PAR_JOUR) * 60L).toString();
    }

    private static String libelle(Creneau creneau) {
        return creneau.getDate() + " " + creneau.getHeureDebut() + "-" + creneau.getHeureFin();
    }

    private static int mediane(List<Integer> triees) {
        return triees.isEmpty() ? 0 : triees.get(triees.size() / 2);
    }

    private static LocalDate first(List<LocalDate> dates) {
        return dates.isEmpty() ? null : dates.get(0);
    }

    private static LocalDate last(List<LocalDate> dates) {
        return dates.isEmpty() ? null : dates.get(dates.size() - 1);
    }
}
