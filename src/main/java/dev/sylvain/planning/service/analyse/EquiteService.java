package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.JoursFeries;
import dev.sylvain.planning.domain.NiveauEffort;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.ToDoubleFunction;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The equity table (issue #497): one line per animateur holding at least one
 * seat of the persisted plan, with everything a complaint after publication is
 * about — evening, week-end and public-holiday hours, demanding seats, how
 * many distinct stands, game categories and locations, how often a wish or an
 * appreciation was honoured, worked and rest days, the longest run — and, per
 * column, the median, min, max and standard deviation the screen colours the
 * deviations against.
 *
 * <p>Every figure is read from the plan, never solved for: {@link #compute}
 * is a pure function of the planning and the legal parameters, so its rules
 * are tested without a container. Where a column mirrors a solver rule it
 * counts <b>exactly what the rule counts</b> — a demanding seat is
 * {@code PreferenceConstraints.isDemanding}'s, a distinct game category is
 * {@code QualiteConstraints.likedTypologiesOfPoste}'s — so the table and the
 * score never tell two stories about the same plan. {@link RapportEquite#colonnesSolveur}
 * says which columns the solver measures at all, and whether that rule is
 * switched on for this edition: a column the solver ignores is information for
 * the reader, not a defect of the solve.</p>
 *
 * <p>The evening starts at {@link ParametresLegaux#getHeureDebutSoiree()}: the
 * minutes of a poste past that hour are its evening hours, up to its end when
 * it crosses midnight. Week-end and public holiday count the whole poste, on
 * the date of its créneau — the same French calendar
 * {@code travailInterditJourFerieMineur} reads.</p>
 */
@ApplicationScoped
public class EquiteService {

    /** Column keys of the JSON line, as the screen and the CSV name them. */
    static final String HEURES_TOTAL = "heuresTotal";

    static final String HEURES_SOIREE = "heuresSoiree";
    static final String HEURES_WEEK_END = "heuresWeekEnd";
    static final String HEURES_JOUR_FERIE = "heuresJourFerie";
    static final String POSTES = "postes";
    static final String POSTES_PENIBLES = "postesPenibles";
    static final String STANDS_DISTINCTS = "standsDistincts";
    static final String TYPOLOGIES_DISTINCTES = "typologiesDistinctes";
    static final String EMPLACEMENTS_PAR_JOUR_MAX = "emplacementsDistinctsParJourMax";
    static final String TAUX_SOUHAITS = "tauxSouhaits";
    static final String TAUX_APPRECIATION = "tauxAppreciation";
    static final String JOURS_TRAVAILLES = "joursTravailles";
    static final String JOURS_REPOS = "joursRepos";
    static final String PLUS_LONGUE_SERIE = "plusLongueSerie";

    /**
     * The columns a solver rule measures, and that rule's name — in the order
     * the screen lists them. A column absent from here (evening, week-end,
     * holiday hours, rest days…) is something the solver does not weigh, which
     * is precisely what the organiser has to know before answering « why me ».
     */
    private static final Map<String, String> CONTRAINTES_PAR_COLONNE = new LinkedHashMap<>();

    static {
        CONTRAINTES_PAR_COLONNE.put(POSTES, "equilibrerCharge");
        CONTRAINTES_PAR_COLONNE.put(POSTES_PENIBLES, "equilibrerCreneauxPenibles");
        CONTRAINTES_PAR_COLONNE.put(TYPOLOGIES_DISTINCTES, "limiterTypologiesDistinctesParAnimateur");
        CONTRAINTES_PAR_COLONNE.put(EMPLACEMENTS_PAR_JOUR_MAX, "limiterEmplacementsParJour");
        CONTRAINTES_PAR_COLONNE.put(TAUX_SOUHAITS, "souhaitsIncompatibles");
        CONTRAINTES_PAR_COLONNE.put(TAUX_APPRECIATION, "appreciationIncompatible");
    }

    private static final int SECONDES_PAR_JOUR = 24 * 3600;

    @Inject
    PlanningPersistenceService persistenceService;

    @Inject
    ReferenceDataService referenceDataService;

    /** The table over the persisted plan, under the organiser's current parameters and toggles. */
    public RapportEquite rapport() {
        return compute(
                persistenceService.loadPersistedPlanning(),
                referenceDataService.getParametresLegaux(),
                referenceDataService.getContraintesDesactivees());
    }

    /**
     * The whole table from a plan alone — no database, no solve. An empty
     * plan gives no line and no synthesis; an animateur without a seat gives
     * no line either, the table being about what people were given.
     */
    public static RapportEquite compute(
            PlanningEvenement planning, ParametresLegaux parametres, Set<String> contraintesDesactivees) {
        LocalTime debutSoiree = parametres == null || parametres.getHeureDebutSoiree() == null
                ? ParametresLegaux.HEURE_DEBUT_SOIREE_PAR_DEFAUT
                : parametres.getHeureDebutSoiree();
        List<PosteAffectation> postes = planning == null || planning.getPostes() == null
                ? List.of()
                : planning.getPostes().stream()
                        .filter(poste -> poste.getCreneau() != null)
                        .toList();

        // The event's days are the dates the plan holds créneaux for, seats
        // filled or not: a day nothing is scheduled on is outside the event,
        // not a rest day (same reading as the Repos screen).
        TreeSet<LocalDate> joursEvenement = new TreeSet<>();
        TreeSet<String> semaines = new TreeSet<>();
        for (PosteAffectation poste : postes) {
            if (poste.getCreneau().getDate() != null) {
                joursEvenement.add(poste.getCreneau().getDate());
            }
        }

        Map<String, Tally> tallies = new LinkedHashMap<>();
        for (Animateur animateur : animateursInOrder(planning, postes)) {
            tallies.put(animateur.getId(), new Tally(animateur));
        }
        for (PosteAffectation poste : postes) {
            if (poste.getAnimateur() == null) {
                continue;
            }
            Tally tally = tallies.get(poste.getAnimateur().getId());
            tally.add(poste, debutSoiree);
            semaines.add(poste.getCreneau().semaineIso());
        }

        List<LocalDate> jours = new ArrayList<>(joursEvenement);
        List<LigneEquite> lignes = new ArrayList<>();
        for (Tally tally : tallies.values()) {
            if (tally.postes > 0) {
                lignes.add(tally.toLigne(jours));
            }
        }
        List<String> semainesTriees = new ArrayList<>(semaines);
        return new RapportEquite(
                debutSoiree,
                semainesTriees,
                lignes,
                syntheses(lignes, semainesTriees),
                colonnesSolveur(contraintesDesactivees));
    }

    /**
     * The referential's order first, so the table opens the way the roster
     * reads; an animateur the plan names but the list does not — a hand-built
     * plan — is appended rather than dropped.
     */
    private static Collection<Animateur> animateursInOrder(PlanningEvenement planning, List<PosteAffectation> postes) {
        Set<Animateur> ordered = new LinkedHashSet<>();
        if (planning != null && planning.getAnimateurs() != null) {
            ordered.addAll(planning.getAnimateurs());
        }
        for (PosteAffectation poste : postes) {
            if (poste.getAnimateur() != null) {
                ordered.add(poste.getAnimateur());
            }
        }
        return ordered;
    }

    private static List<ColonneSolveur> colonnesSolveur(Set<String> contraintesDesactivees) {
        Set<String> desactivees = contraintesDesactivees == null ? Set.of() : contraintesDesactivees;
        List<ColonneSolveur> colonnes = new ArrayList<>();
        CONTRAINTES_PAR_COLONNE.forEach((colonne, contrainte) ->
                colonnes.add(new ColonneSolveur(colonne, contrainte, !desactivees.contains(contrainte))));
        return colonnes;
    }

    /* ------------------------------- Synthesis ------------------------------ */

    /**
     * Median, min, max and standard deviation of every numeric column — the
     * week columns included, keyed by their ISO week. Empty when there is no
     * line: a synthesis of nothing is not zero, it is absent.
     */
    private static Map<String, SyntheseColonne> syntheses(List<LigneEquite> lignes, List<String> semaines) {
        Map<String, SyntheseColonne> syntheses = new LinkedHashMap<>();
        if (lignes.isEmpty()) {
            return syntheses;
        }
        syntheses.put(HEURES_TOTAL, synthese(lignes, LigneEquite::heuresTotal));
        for (String semaine : semaines) {
            syntheses.put(
                    semaine, synthese(lignes, ligne -> ligne.heuresParSemaine().getOrDefault(semaine, 0.0)));
        }
        syntheses.put(HEURES_SOIREE, synthese(lignes, LigneEquite::heuresSoiree));
        syntheses.put(HEURES_WEEK_END, synthese(lignes, LigneEquite::heuresWeekEnd));
        syntheses.put(HEURES_JOUR_FERIE, synthese(lignes, LigneEquite::heuresJourFerie));
        syntheses.put(POSTES, synthese(lignes, LigneEquite::postes));
        syntheses.put(POSTES_PENIBLES, synthese(lignes, LigneEquite::postesPenibles));
        syntheses.put(STANDS_DISTINCTS, synthese(lignes, LigneEquite::standsDistincts));
        syntheses.put(TYPOLOGIES_DISTINCTES, synthese(lignes, LigneEquite::typologiesDistinctes));
        syntheses.put(EMPLACEMENTS_PAR_JOUR_MAX, synthese(lignes, LigneEquite::emplacementsDistinctsParJourMax));
        syntheses.put(TAUX_SOUHAITS, synthese(lignes, LigneEquite::tauxSouhaits));
        syntheses.put(TAUX_APPRECIATION, synthese(lignes, LigneEquite::tauxAppreciation));
        syntheses.put(JOURS_TRAVAILLES, synthese(lignes, LigneEquite::joursTravailles));
        syntheses.put(JOURS_REPOS, synthese(lignes, LigneEquite::joursRepos));
        syntheses.put(PLUS_LONGUE_SERIE, synthese(lignes, LigneEquite::plusLongueSerie));
        return syntheses;
    }

    private static SyntheseColonne synthese(List<LigneEquite> lignes, ToDoubleFunction<LigneEquite> valeur) {
        double[] valeurs = lignes.stream().mapToDouble(valeur).toArray();
        return new SyntheseColonne(
                median(valeurs),
                Arrays.stream(valeurs).min().orElse(0),
                Arrays.stream(valeurs).max().orElse(0),
                standardDeviation(valeurs));
    }

    /** The middle value, or the mean of the two middle ones on an even count. */
    static double median(double[] valeurs) {
        if (valeurs.length == 0) {
            return 0;
        }
        double[] triees = valeurs.clone();
        Arrays.sort(triees);
        int milieu = triees.length / 2;
        return triees.length % 2 == 1 ? triees[milieu] : (triees[milieu - 1] + triees[milieu]) / 2;
    }

    /** Population standard deviation: the roster is the whole population, not a sample of one. */
    static double standardDeviation(double[] valeurs) {
        if (valeurs.length == 0) {
            return 0;
        }
        double moyenne = Arrays.stream(valeurs).average().orElse(0);
        double variance = Arrays.stream(valeurs)
                        .map(valeur -> (valeur - moyenne) * (valeur - moyenne))
                        .sum()
                / valeurs.length;
        return Math.sqrt(variance);
    }

    /* --------------------------------- CSV ---------------------------------- */

    /**
     * Same file conventions as the Hours export: {@code ;} between fields, a
     * comma as decimal separator, one column per ISO week between the total
     * and the evening hours. Rates are written as ratios (0,75), which a French
     * spreadsheet formats as a percentage in one click.
     */
    public static String generateCsv(RapportEquite rapport) {
        StringBuilder csv = new StringBuilder();
        csv.append("animateur;").append(HEURES_TOTAL);
        for (String semaine : rapport.semaines()) {
            csv.append(';').append(semaine);
        }
        csv.append(';')
                .append(String.join(
                        ";",
                        HEURES_SOIREE,
                        HEURES_WEEK_END,
                        HEURES_JOUR_FERIE,
                        POSTES,
                        POSTES_PENIBLES,
                        STANDS_DISTINCTS,
                        TYPOLOGIES_DISTINCTES,
                        EMPLACEMENTS_PAR_JOUR_MAX,
                        TAUX_SOUHAITS,
                        TAUX_APPRECIATION,
                        JOURS_TRAVAILLES,
                        JOURS_REPOS,
                        PLUS_LONGUE_SERIE))
                .append('\n');
        for (LigneEquite ligne : rapport.lignes()) {
            csv.append(escape(ligne.nom())).append(';').append(decimal(ligne.heuresTotal()));
            for (String semaine : rapport.semaines()) {
                csv.append(';').append(decimal(ligne.heuresParSemaine().getOrDefault(semaine, 0.0)));
            }
            csv.append(';')
                    .append(decimal(ligne.heuresSoiree()))
                    .append(';')
                    .append(decimal(ligne.heuresWeekEnd()))
                    .append(';')
                    .append(decimal(ligne.heuresJourFerie()))
                    .append(';')
                    .append(ligne.postes())
                    .append(';')
                    .append(ligne.postesPenibles())
                    .append(';')
                    .append(ligne.standsDistincts())
                    .append(';')
                    .append(ligne.typologiesDistinctes())
                    .append(';')
                    .append(ligne.emplacementsDistinctsParJourMax())
                    .append(';')
                    .append(decimal(ligne.tauxSouhaits()))
                    .append(';')
                    .append(decimal(ligne.tauxAppreciation()))
                    .append(';')
                    .append(ligne.joursTravailles())
                    .append(';')
                    .append(ligne.joursRepos())
                    .append(';')
                    .append(ligne.plusLongueSerie())
                    .append('\n');
        }
        return csv.toString();
    }

    private static String decimal(double valeur) {
        return String.format(Locale.ROOT, "%.2f", valeur).replace('.', ',');
    }

    private static String escape(String valeur) {
        if (valeur == null) {
            return "";
        }
        if (valeur.contains(";") || valeur.contains("\"") || valeur.contains("\n")) {
            return "\"" + valeur.replace("\"", "\"\"") + "\"";
        }
        return valeur;
    }

    /* ---------------------------- Per-poste rules --------------------------- */

    /** Same definition as {@code PreferenceConstraints.isDemanding}: exhausting or premium stand. */
    static boolean isDemanding(Stand stand) {
        return stand != null && (stand.getNiveauEffort() == NiveauEffort.EPUISANT || stand.isPremium());
    }

    /**
     * Minutes of the poste past the start of the evening, its end included
     * when it crosses midnight — 20:00-01:00 under a 22:00 evening is three
     * hours. A poste that starts after midnight on the next date (01:00-03:00)
     * lies before any evening of its own day and counts nothing.
     */
    static int eveningMinutes(PosteAffectation poste, LocalTime debutSoiree) {
        LocalTime debut = poste.heureDebutEffectif();
        LocalTime fin = poste.heureFinEffectif();
        if (debut == null || fin == null) {
            return 0;
        }
        int debutSecondes = debut.toSecondOfDay();
        int finSecondes = fin.toSecondOfDay();
        if (finSecondes <= debutSecondes) {
            finSecondes += SECONDES_PAR_JOUR;
        }
        int soireeSecondes = debutSoiree.toSecondOfDay();
        return Math.max(0, finSecondes - Math.max(debutSecondes, soireeSecondes)) / 60;
    }

    /**
     * The game categories this seat lets the animateur exercise: the stand's,
     * for a ninja — versatile by definition — and only the ones the animateur
     * is appreciated for otherwise ({@code QualiteConstraints.likedTypologiesOfPoste}).
     */
    static Set<String> exercisedTypologies(Animateur animateur, Stand stand) {
        if (stand == null || stand.getTypologiesProposees() == null) {
            return Set.of();
        }
        if (animateur.isNinja()) {
            return new HashSet<>(stand.getTypologiesProposees());
        }
        Set<String> intersection = new HashSet<>(stand.getTypologiesProposees());
        intersection.retainAll(
                animateur.getCompetences() == null
                        ? Set.of()
                        : animateur.getCompetences().keySet());
        return intersection;
    }

    /** One animateur's running totals, never exposed. */
    private static final class Tally {

        private final Animateur animateur;
        private final Map<String, Double> heuresParSemaine = new LinkedHashMap<>();
        private final Set<String> stands = new HashSet<>();
        private final Set<String> typologies = new HashSet<>();
        private final Map<Integer, Set<String>> emplacementsParJour = new HashMap<>();
        private final Set<LocalDate> joursTravailles = new HashSet<>();
        private double heuresTotal;
        private double heuresSoiree;
        private double heuresWeekEnd;
        private double heuresJourFerie;
        private int postes;
        private int postesPenibles;
        private int postesSouhaites;
        private int postesApprecies;

        Tally(Animateur animateur) {
            this.animateur = animateur;
        }

        void add(PosteAffectation poste, LocalTime debutSoiree) {
            Creneau creneau = poste.getCreneau();
            Stand stand = poste.getStand();
            double heures = poste.getDureeEffectiveMinutes() / 60.0;
            postes++;
            heuresTotal += heures;
            heuresParSemaine.merge(creneau.semaineIso(), heures, Double::sum);
            heuresSoiree += eveningMinutes(poste, debutSoiree) / 60.0;
            LocalDate date = creneau.getDate();
            if (date != null) {
                joursTravailles.add(date);
                if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                    heuresWeekEnd += heures;
                }
                if (JoursFeries.isFerieInFrance(date)) {
                    heuresJourFerie += heures;
                }
            }
            if (isDemanding(stand)) {
                postesPenibles++;
            }
            if (stand != null) {
                stands.add(stand.getId());
                typologies.addAll(exercisedTypologies(animateur, stand));
                if (animateur.hasSouhaitFor(stand)) {
                    postesSouhaites++;
                }
                if (animateur.hasCompetenceFor(stand)) {
                    postesApprecies++;
                }
                // A poste without a location is left out, as the constraint
                // leaves it out: nothing counted, rather than one big zone.
                if (stand.getEmplacement() != null && stand.getEmplacement().getId() != null) {
                    emplacementsParJour
                            .computeIfAbsent(creneau.getJour(), jour -> new HashSet<>())
                            .add(stand.getEmplacement().getId());
                }
            }
        }

        LigneEquite toLigne(List<LocalDate> joursEvenement) {
            int repos = 0;
            int serie = 0;
            int plusLongueSerie = 0;
            for (LocalDate jour : joursEvenement) {
                if (joursTravailles.contains(jour)) {
                    serie++;
                    plusLongueSerie = Math.max(plusLongueSerie, serie);
                    continue;
                }
                serie = 0;
                // A day the person declared off is not a rest day: they were
                // not to be called on it (same reading as the Repos screen).
                if (!animateur.isIndisponibleOn(jour)) {
                    repos++;
                }
            }
            int emplacementsMax = emplacementsParJour.values().stream()
                    .mapToInt(Set::size)
                    .max()
                    .orElse(0);
            return new LigneEquite(
                    animateur.getId(),
                    animateur.nomAffiche(),
                    heuresTotal,
                    new LinkedHashMap<>(heuresParSemaine),
                    heuresSoiree,
                    heuresWeekEnd,
                    heuresJourFerie,
                    postes,
                    postesPenibles,
                    stands.size(),
                    typologies.size(),
                    emplacementsMax,
                    postes == 0 ? 0 : (double) postesSouhaites / postes,
                    postes == 0 ? 0 : (double) postesApprecies / postes,
                    joursTravailles.size(),
                    repos,
                    plusLongueSerie);
        }
    }

    /* --------------------------------- DTOs --------------------------------- */

    /**
     * One animateur's line. {@code nom} is for the screen and the CSV; the MCP
     * view drops it. Rates are ratios in {@code [0, 1]}: seats on a wished
     * (resp. appreciated) game category over all seats.
     */
    @Schema(
            requiredProperties = {
                "animateurId",
                "heuresTotal",
                "heuresSoiree",
                "heuresWeekEnd",
                "heuresJourFerie",
                "postes",
                "postesPenibles",
                "standsDistincts",
                "typologiesDistinctes",
                "emplacementsDistinctsParJourMax",
                "tauxSouhaits",
                "tauxAppreciation",
                "joursTravailles",
                "joursRepos",
                "plusLongueSerie"
            })
    public record LigneEquite(
            String animateurId,
            String nom,
            double heuresTotal,
            Map<String, Double> heuresParSemaine,
            double heuresSoiree,
            double heuresWeekEnd,
            double heuresJourFerie,
            int postes,
            int postesPenibles,
            int standsDistincts,
            int typologiesDistinctes,
            int emplacementsDistinctsParJourMax,
            double tauxSouhaits,
            double tauxAppreciation,
            int joursTravailles,
            int joursRepos,
            int plusLongueSerie) {}

    /** Median, min, max and standard deviation of one column over the lines. */
    @Schema(requiredProperties = {"mediane", "min", "max", "ecartType"})
    public record SyntheseColonne(double mediane, double min, double max, double ecartType) {}

    /**
     * A column the solver measures: the line's field, the rule's name, and
     * whether that rule is switched on for this edition.
     */
    @Schema(requiredProperties = {"colonne", "contrainte", "active"})
    public record ColonneSolveur(String colonne, String contrainte, boolean active) {}

    /**
     * The whole table: the evening it was read under, the ISO weeks the lines
     * carry hours in, the lines, one synthesis per numeric column (the weeks
     * keyed by their name), and the columns a solver rule measures.
     */
    @Schema(requiredProperties = {"heureDebutSoiree", "semaines", "lignes", "syntheses", "colonnesSolveur"})
    public record RapportEquite(
            LocalTime heureDebutSoiree,
            List<String> semaines,
            List<LigneEquite> lignes,
            Map<String, SyntheseColonne> syntheses,
            List<ColonneSolveur> colonnesSolveur) {}
}
