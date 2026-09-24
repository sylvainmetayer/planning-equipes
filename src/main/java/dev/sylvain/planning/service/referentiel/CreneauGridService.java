package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.FenetreRepas;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeJoursHoraire;
import dev.sylvain.planning.domain.VacationVerrouillee;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.FeasibilityReport;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer.Anomaly;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.DayOfWeek;
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
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Builds and checks the edition's <b>grid of créneaux</b>: expanding a
 * recurrence rule ("09:00-12:00 and 14:00-18:00, every day but the weekend,
 * from the 6th to the 19th") into concrete créneaux, and auditing a grid for
 * the mistakes nothing else catches.
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
 * <p>Nothing here writes: generation returns unsaved {@link Creneau}s and
 * validation returns a report. Persisting is the caller's decision, which is
 * what makes a preview possible.</p>
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

    @Inject
    FeasibilityAnalyzer feasibilityAnalyzer;

    /**
     * Only for the constraint states: the grid report estimates how many seats
     * the team could hold, and that estimate changes with the supervision of
     * minors (issue #595).
     */
    @Inject
    ParametresService parametres;

    /* ------------------------------ Generation ------------------------------ */

    /**
     * Expands {@code regle} into unsaved créneaux, one per (matching date ×
     * window), ordered by date then start time.
     *
     * <p>{@link Creneau#getJour()} is left at its default: the day number is
     * never stored, {@code CreneauRepository} recomputes it with
     * {@link Creneau#assignerJours} on every read so that consecutive calendar
     * days always differ by one. Setting it here would be write-only noise —
     * and would be wrong the moment these créneaux join an edition whose
     * earliest date is older.</p>
     */
    public static List<Creneau> generateRecurrence(RegleRecurrence regle) {
        List<FenetreHoraire> fenetres = regle.fenetresValidees();
        List<Creneau> creneaux = new ArrayList<>();
        for (LocalDate date : regle.datesCouvertes()) {
            for (FenetreHoraire fenetre : fenetres) {
                creneaux.add(new Creneau(null, 0, date, fenetre.getHeureDebut(), fenetre.getHeureFin()));
            }
        }
        creneaux.sort(Comparator.comparing(Creneau::getDate).thenComparing(Creneau::getHeureDebut));
        return creneaux;
    }

    /**
     * A recurrence to expand. Reuses {@link TypeJoursHoraire} and
     * {@link FenetreHoraire} rather than inventing a parallel vocabulary: an
     * operator who has learned "TOUS / JOURS_SEMAINE / PLAGE / DATES" on stand
     * horaires should not have to learn a second dialect for créneaux.
     *
     * @param exclusions dates to skip whatever the selector says — the
     *                   "every day except the 14th" case, which would
     *                   otherwise force listing every date explicitly
     */
    public record RegleRecurrence(
            TypeJoursHoraire jours,
            LocalDate dateDebut,
            LocalDate dateFin,
            Set<DayOfWeek> joursSemaine,
            Set<LocalDate> dates,
            Set<LocalDate> exclusions,
            List<FenetreHoraire> fenetres) {

        public RegleRecurrence {
            jours = jours != null ? jours : TypeJoursHoraire.TOUS;
            joursSemaine = joursSemaine != null ? Set.copyOf(joursSemaine) : Set.of();
            dates = dates != null ? Set.copyOf(dates) : Set.of();
            exclusions = exclusions != null ? Set.copyOf(exclusions) : Set.of();
            fenetres = fenetres != null ? List.copyOf(fenetres) : List.of();
        }

        /** The windows, rejecting the malformed ones with a message an assistant can act on. */
        List<FenetreHoraire> fenetresValidees() {
            if (fenetres.isEmpty()) {
                throw new BusinessError.Invalid("Aucune fenêtre horaire fournie, ex. « 09:00-12:00,14:00-18:00 »");
            }
            for (FenetreHoraire fenetre : fenetres) {
                if (fenetre.getHeureDebut() == null || fenetre.getHeureFin() == null) {
                    throw new BusinessError.Invalid(
                            "Chaque fenêtre doit porter une heure de début ET une heure de fin : un créneau est "
                                    + "l'amplitude elle-même, il n'a pas de fermeture extérieure dont hériter.");
                }
                if (fenetre.getHeureDebut().equals(fenetre.getHeureFin())) {
                    throw new BusinessError.Invalid(
                            "Fenêtre de durée nulle : " + fenetre.getHeureDebut() + " → " + fenetre.getHeureFin());
                }
            }
            return fenetres;
        }

        /**
         * The dates the rule actually covers, exclusions removed. A selector
         * needing bounds says so explicitly rather than silently generating
         * nothing, which is the failure mode that wastes an assistant's turn.
         */
        Set<LocalDate> datesCouvertes() {
            Set<LocalDate> retenues = new TreeSet<>();
            if (jours == TypeJoursHoraire.DATES) {
                if (dates.isEmpty()) {
                    throw new BusinessError.Invalid("La portée DATES exige la liste des dates.");
                }
                retenues.addAll(dates);
            } else {
                if (dateDebut == null || dateFin == null) {
                    throw new BusinessError.Invalid(
                            "La portée " + jours + " exige dateDebut et dateFin (bornes incluses).");
                }
                if (dateFin.isBefore(dateDebut)) {
                    throw new BusinessError.Invalid(
                            "dateFin (" + dateFin + ") est antérieure à dateDebut (" + dateDebut + ").");
                }
                if (jours == TypeJoursHoraire.JOURS_SEMAINE && joursSemaine.isEmpty()) {
                    throw new BusinessError.Invalid("La portée JOURS_SEMAINE exige joursSemaine, ex. MONDAY,TUESDAY.");
                }
                for (LocalDate date = dateDebut; !date.isAfter(dateFin); date = date.plusDays(1)) {
                    if (jours != TypeJoursHoraire.JOURS_SEMAINE || joursSemaine.contains(date.getDayOfWeek())) {
                        retenues.add(date);
                    }
                }
            }
            retenues.removeAll(exclusions);
            if (retenues.isEmpty()) {
                throw new BusinessError.Invalid("La règle ne couvre aucune date : sélecteur et exclusions s'annulent.");
            }
            return retenues;
        }
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
     *
     * <p>The meal windows come given rather than derived from the parameters:
     * the ones a consigne restates on its date (issue #4) count for the relay
     * check of that date, and an overload deriving them from {@code legaux}
     * alone would judge that date against the edition's window.</p>
     */
    public RapportGrille validate(
            List<Creneau> creneaux,
            List<Stand> stands,
            List<Animateur> animateurs,
            ParametresLegaux legaux,
            List<VerrouillagePlanning> verrouillages,
            List<FenetreRepas> fenetresRepas) {
        List<GridAnomaly> anomalies = new ArrayList<>();
        List<Creneau> dates =
                creneaux.stream().filter(creneau -> creneau.getDate() != null).toList();

        anomalies.addAll(unitAnomalies(creneaux, legaux, fenetresRepas == null ? List.of() : fenetresRepas));
        anomalies.addAll(anomaliesByDay(dates));
        anomalies.addAll(datesIsolees(dates));
        anomalies.addAll(verrouillagesWithoutVacation(creneaux, verrouillages));

        anomalies.sort(Comparator.comparingInt(
                        (GridAnomaly anomalie) -> anomalie.severite().ordinal())
                .thenComparing(anomalie -> anomalie.date() != null ? anomalie.date() : LocalDate.MIN)
                .thenComparing(GridAnomaly::message));

        List<Anomaly> ouvertures = openingAnomalies(creneaux, stands);
        FeasibilityReport faisabilite = stands.isEmpty() || creneaux.isEmpty()
                ? null
                : feasibilityAnalyzer.analyze(
                        animateurs,
                        stands,
                        creneaux,
                        List.of(),
                        FeasibilityAnalyzer.encadrementMineursActif(parametres.disabledContraintes()));

        return new RapportGrille(creneaux.size(), anomalies, ouvertures, faisabilite);
    }

    /**
     * The opening report's anomalies {@link #validate} folds in — without the
     * informational ones, which say how a stand's rules are written and nothing
     * about the grid: a grid check, a series preview or {@code valider_creneaux}
     * listing them would warn about what no grid can fix. The Ouvertures screen
     * and the coherence checklist read them from the report itself.
     */
    static List<Anomaly> openingAnomalies(List<Creneau> creneaux, List<Stand> stands) {
        if (stands.isEmpty() || creneaux.isEmpty()) {
            return List.of();
        }
        return OuvertureStandsAnalyzer.analyze(stands, creneaux).anomalies().stream()
                .filter(anomalie -> !anomalie.type().isInformational())
                .toList();
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
     * {@code docs/rgpd.md} §7).</p>
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
                        + " de les retirer depuis l'écran Verrouillages. Un échange validé pose deux de"
                        + " ces verrous.")));
        return anomalies;
    }

    /** Checks that need one créneau at a time: shape, then duration against the legal ceilings. */
    private static List<GridAnomaly> unitAnomalies(
            List<Creneau> creneaux, ParametresLegaux legaux, List<FenetreRepas> fenetresRepas) {
        List<GridAnomaly> anomalies = new ArrayList<>();
        int amplitudeMaximaleLegale = MINUTES_PAR_JOUR - legaux.getReposQuotidienMinimalMinutes();
        for (Creneau creneau : creneaux) {
            if (creneau.getDate() == null || creneau.getHeureDebut() == null || creneau.getHeureFin() == null) {
                anomalies.add(new GridAnomaly(
                        SeveriteGrille.ERREUR,
                        GridAnomalyType.CRENEAU_INCOMPLET,
                        creneau.getDate(),
                        "Créneau incomplet : date, heure de début et heure de fin sont requises."));
                continue;
            }
            int duree = creneau.getDureeMinutes();
            if (duree == 0) {
                anomalies.add(new GridAnomaly(
                        SeveriteGrille.ERREUR,
                        GridAnomalyType.DUREE_NULLE,
                        creneau.getDate(),
                        libelle(creneau) + " : durée nulle (début et fin identiques)."));
                continue;
            }
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
                                + " min. Toute affectation sur ce créneau violera une contrainte dure."));
            }
            if (creneau.isCouverturePause() && !insideMealWindow(creneau, fenetresRepas)) {
                anomalies.add(new GridAnomaly(
                        SeveriteGrille.AVERTISSEMENT,
                        GridAnomalyType.RELAIS_REPAS_HORS_FENETRE,
                        creneau.getDate(),
                        libelle(creneau) + " : relais repas hors de toute fenêtre repas ("
                                + libelleFenetresRepas(fenetresRepas)
                                + "). L'effectif y est divisé par deux sans qu'aucune coupure"
                                + " ne puisse s'y prendre."));
            }
        }
        return anomalies;
    }

    /**
     * A meal-relay vacation halves the seats so that the other half can eat:
     * it only makes sense inside a window where a meal break may be taken. A
     * slot running past midnight is never inside one — the windows are
     * wall-clock, within the day.
     */
    private static boolean insideMealWindow(Creneau creneau, List<FenetreRepas> fenetres) {
        int debut = creneau.getHeureDebut().toSecondOfDay() / 60;
        int fin = creneau.getHeureFin().toSecondOfDay() / 60;
        if (fin <= debut) {
            return false;
        }
        return fenetres.stream()
                .filter(fenetre -> fenetre.appliesTo(creneau.getDate()))
                .anyMatch(fenetre -> debut >= fenetre.debutMinutes() && fin <= fenetre.finMinutes());
    }

    private static String libelleFenetresRepas(List<FenetreRepas> fenetres) {
        if (fenetres.isEmpty()) {
            return "aucune fenêtre repas déclarée dans les paramètres légaux";
        }
        return fenetres.stream()
                .map(fenetre -> fenetre.libelle() + " " + fenetre.debut() + "-" + fenetre.fin())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
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
     * dates, with how many meal relays among them. The cheap first call — for
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
            return new DiagnosticGrille(0, null, null, false, "L'édition n'a aucun créneau : la grille est à créer.");
        }
        List<LocalDate> dates = creneaux.stream()
                .map(Creneau::getDate)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        long relais = creneaux.stream().filter(Creneau::isCouverturePause).count();
        int dureeMediane =
                mediane(creneaux.stream().map(Creneau::getDureeMinutes).sorted().toList());

        return new DiagnosticGrille(
                creneaux.size(),
                first(dates),
                last(dates),
                relais > 0,
                creneaux.size() + " vacations sur " + dates.size() + " date(s), durée médiane de " + dureeMediane
                        + " min"
                        + (relais > 0
                                ? ", dont " + relais + " relais repas à effectif réduit."
                                : ", aucun relais repas."));
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
        /** A pause-covering vacation outside every meal window: half the seats, and nobody can eat. */
        RELAIS_REPAS_HORS_FENETRE,
        /** Locks naming a vacation this grid does not hold — see issue #577. */
        VERROUILLAGE_SANS_VACATION
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
     * @param contientCouverturePause whether at least one créneau is a meal relay,
     *                                staffed at half the usual headcount
     */
    @Schema(requiredProperties = {"contientCouverturePause", "nombreCreneaux"})
    public record DiagnosticGrille(
            int nombreCreneaux,
            LocalDate premiereDate,
            LocalDate derniereDate,
            boolean contientCouverturePause,
            String explication) {}

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
