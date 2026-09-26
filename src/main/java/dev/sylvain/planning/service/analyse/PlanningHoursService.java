package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.EffectiveWork;
import dev.sylvain.planning.domain.JoursFeries;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Hours planned per animateur, grouped by ISO calendar week (from
 * {@code Creneau.date}), a grand total, the evening hours, and the three
 * counters the payroll needs for its premiums — Sunday hours, public-holiday
 * hours, hours past 22:00 (issue #597). Reuses
 * {@link dev.sylvain.planning.domain.PosteAffectation#getDureeEffectiveMinutes()}
 * so the midnight-crossing edge case (delegated to {@code Creneau.getDureeMinutes()}
 * when a poste has no effective-window override) is handled in exactly one
 * place, and a poste narrowed by a partial stand closure (issue #60) counts
 * only the time actually staffed, not its créneau's full span.
 *
 * <p><b>The weekly columns and the total are travail effectif</b>: the
 * amplitude less the legal breaks each day owes, computed by
 * {@link dev.sylvain.planning.domain.EffectiveWork} — the same quantity the
 * daily and weekly caps of {@code LegalConstraints} measure, the Équité screen
 * shares out, the Besoin floor divides by and the KPI report (ADR 0048). They
 * used to be amplitude, so a week showed half an hour a day more here than
 * {@code dureeHebdomadaireMax} measured against its ceiling, and an organiser
 * had two numbers for one thing with nothing to tell them apart.</p>
 *
 * <p><b>The three premium counters are not.</b> Sunday hours, public-holiday
 * hours and hours past 22:00 stay at amplitude, prorated over the seat's own
 * window: they answer « how long was somebody there, on this kind of hour »,
 * which is what a premium is paid on, and a break cannot be assigned to one
 * side of midnight or to one of two windows without inventing where it was
 * taken. So the three columns do not add up to the total, and never did. See
 * {@code docs/contraintes.md}, « Ce qui déduit la pause, et ce qui compte
 * l'amplitude ».</p>
 *
 * <p><b>One evening in the whole application.</b> The evening hours are the
 * ones {@link EquiteService} counts, from the same settable
 * {@link ParametresLegaux#getHeureDebutSoiree()} and through the same
 * {@code EquiteService.eveningMinutes}: an animateur read on the Planning
 * page's person axis and on the payroll file carries one evening figure, not
 * one per screen. The fixed 22:00 bound survives as the payroll's own night
 * column — named as such, never as « the evening ».</p>
 */
@ApplicationScoped
public class PlanningHoursService {

    /**
     * Where the edition's current parameters are read — the ones the Équité
     * report reads too. Null when the service is built by hand (a unit test,
     * a plan with no edition behind it): the plan's own parameters then stand.
     */
    private final ReferenceDataService referenceDataService;

    /** A service reading the plan's own parameters only. */
    public PlanningHoursService() {
        this(null);
    }

    @Inject
    public PlanningHoursService(ReferenceDataService referenceDataService) {
        this.referenceDataService = referenceDataService;
    }

    /**
     * 22:00 — the hour the payroll counts its night hours from (« Nuit (paie) »),
     * and the one art. <b>L3163-1</b> opens a 16-to-18 year old's night at.
     *
     * <p>Not {@code ParametresLegaux.heureDebutSoiree}, which is 20:00 by
     * default: that one is the application's single evening, a comfort
     * threshold for comparing evening duty across the roster, and moving it
     * must not move a payroll figure (issue #597).</p>
     *
     * <p>Not the Code du travail's own night period either, which art.
     * <b>L3122-2</b> sets at 21:00-06:00 <i>absent a collective agreement</i>.
     * The counter reports what the organisation asked for; whether the
     * applicable convention moves that bound is left to confirm.</p>
     */
    public static final LocalTime HEURE_NUIT = LocalTime.of(22, 0);

    /** 06:00, where the night ends — the same bound every minors' rule uses. */
    private static final LocalTime FIN_NUIT = Creneau.FIN_NUIT;

    private static final int SECONDES_PAR_JOUR = 24 * 3600;

    /** The premium counters, in amplitude: Sunday, public holiday, both at once, and night. */
    private static final class Premiums {
        private final Map<String, Double> dimancheParAnimateur = new LinkedHashMap<>();
        private final Map<String, Double> ferieParAnimateur = new LinkedHashMap<>();
        private final Map<String, Double> dimancheFerieParAnimateur = new LinkedHashMap<>();
        private final Map<String, Double> nuitParAnimateur = new LinkedHashMap<>();
        private final Map<String, Double> soireeParAnimateur = new LinkedHashMap<>();

        void add(PosteAffectation poste, String animateurId, LocalDate date, double heures, LocalTime debutSoiree) {
            // Sunday alone, never « week-end »: only Sunday carries a premium,
            // and the Équité screen's heuresWeekEnd adds Saturday in — which is
            // why that column could not answer this question (issue #597).
            boolean dimanche = DayOfWeek.SUNDAY.equals(date.getDayOfWeek());
            boolean ferie = JoursFeries.isFerieInFrance(date);
            if (dimanche) {
                dimancheParAnimateur.merge(animateurId, heures, Double::sum);
            }
            if (ferie) {
                ferieParAnimateur.merge(animateurId, heures, Double::sum);
            }
            // A Sunday that is also a public holiday counts in both columns.
            // Their overlap is reported on its own so the screen can say so:
            // adding the two columns up would double-count it, and a total
            // nobody warned about is worse than no total.
            if (dimanche && ferie) {
                dimancheFerieParAnimateur.merge(animateurId, heures, Double::sum);
            }
            nuitParAnimateur.merge(animateurId, nightMinutes(poste) / 60.0, Double::sum);
            soireeParAnimateur.merge(animateurId, EquiteService.eveningMinutes(poste, debutSoiree) / 60.0, Double::sum);
        }
    }

    public HeuresRapport compute(PlanningEvenement planning) {
        // The edition's parameters as they stand, like the Équité report: the
        // plan a browser sends carries the ones of its last solve, and two
        // screens reading two evenings is what this service must not do.
        ParametresLegaux courants = referenceDataService == null ? null : referenceDataService.getParametresLegaux();
        ParametresLegaux parametres = courants == null ? planning.parametresLegaux() : courants;
        LocalTime debutSoiree = parametres.getHeureDebutSoiree() == null
                ? ParametresLegaux.HEURE_DEBUT_SOIREE_PAR_DEFAUT
                : parametres.getHeureDebutSoiree();
        Map<String, Map<String, Double>> heuresParAnimateurEtSemaine = new LinkedHashMap<>();
        Map<String, Double> totalParAnimateur = new LinkedHashMap<>();
        Premiums premiums = new Premiums();
        Map<String, Double> dimancheParAnimateur = premiums.dimancheParAnimateur;
        Map<String, Double> ferieParAnimateur = premiums.ferieParAnimateur;
        Map<String, Double> dimancheFerieParAnimateur = premiums.dimancheFerieParAnimateur;
        Map<String, Double> nuitParAnimateur = premiums.nuitParAnimateur;
        Map<String, Double> soireeParAnimateur = premiums.soireeParAnimateur;
        TreeSet<String> semaines = new TreeSet<>();

        for (PosteAffectation poste : planning.getPostes()) {
            if (poste.getAnimateur() == null || poste.getCreneau() == null) {
                continue;
            }
            String animateurId = poste.getAnimateur().getId();
            String semaine = poste.getCreneau().semaineIso();
            // Amplitude, for the premium counters only: the week and the total
            // are travail effectif, added up day by day below.
            double heures = poste.getDureeEffectiveMinutes() / 60.0;
            semaines.add(semaine);

            LocalDate date = poste.getCreneau().getDate();
            if (date != null) {
                premiums.add(poste, animateurId, date, heures, debutSoiree);
            }
        }

        // Day by day, then added into the weeks: a break is owed per stretch,
        // and a stretch lives inside one day (see EffectiveWork).
        for (Map.Entry<LocalDate, List<PosteAffectation>> jour :
                byDate(planning.getPostes()).entrySet()) {
            String semaine = Creneau.semaineIso(jour.getKey());
            EffectiveWork.minutesPerAnimateur(jour.getValue(), parametres).forEach((animateurId, minutes) -> {
                double heures = minutes / 60.0;
                heuresParAnimateurEtSemaine
                        .computeIfAbsent(animateurId, id -> new LinkedHashMap<>())
                        .merge(semaine, heures, Double::sum);
                totalParAnimateur.merge(animateurId, heures, Double::sum);
            });
        }

        List<String> semainesTriees = new ArrayList<>(semaines);
        List<HeuresAnimateur> lignes = new ArrayList<>();
        for (Animateur animateur : planning.getAnimateurs()) {
            String id = animateur.getId();
            Map<String, Double> parSemaine = heuresParAnimateurEtSemaine.getOrDefault(id, Map.of());
            lignes.add(new HeuresAnimateur(
                    id,
                    animateur.nomAffiche(),
                    new LinkedHashMap<>(parSemaine),
                    totalParAnimateur.getOrDefault(id, 0.0),
                    dimancheParAnimateur.getOrDefault(id, 0.0),
                    ferieParAnimateur.getOrDefault(id, 0.0),
                    dimancheFerieParAnimateur.getOrDefault(id, 0.0),
                    nuitParAnimateur.getOrDefault(id, 0.0),
                    soireeParAnimateur.getOrDefault(id, 0.0)));
        }
        return new HeuresRapport(debutSoiree, semainesTriees, lignes);
    }

    /** The staffed seats of one planning, grouped by the day they fall on. */
    private static Map<LocalDate, List<PosteAffectation>> byDate(List<PosteAffectation> postes) {
        Map<LocalDate, List<PosteAffectation>> byDate = new LinkedHashMap<>();
        for (PosteAffectation poste : postes) {
            if (poste.getAnimateur() == null
                    || poste.getCreneau() == null
                    || poste.getCreneau().getDate() == null) {
                continue;
            }
            byDate.computeIfAbsent(poste.getCreneau().getDate(), date -> new ArrayList<>())
                    .add(poste);
        }
        return byDate;
    }

    /**
     * Minutes of a seat lying past {@link #HEURE_NUIT}, up to the end of the
     * night at 06:00 — <b>prorated</b>, never the whole seat: a 20:00-23:00
     * vacation owes one hour, not three.
     *
     * <p>Same axis as {@code EquiteService.eveningMinutes}: a seat crossing
     * midnight is measured past 24:00 rather than wrapped, and the small hours
     * of its own day are added as a second, disjoint window — so whoever holds
     * the heart of the night is counted wherever the grid happened to cut.</p>
     */
    static int nightMinutes(PosteAffectation poste) {
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
        int finNuit = FIN_NUIT.toSecondOfDay();
        int recouvrement =
                recouvrement(debutSecondes, finSecondes, HEURE_NUIT.toSecondOfDay(), SECONDES_PAR_JOUR + finNuit)
                        + recouvrement(debutSecondes, finSecondes, 0, finNuit);
        return recouvrement / 60;
    }

    /** Seconds two intervals share, on the axis extended past midnight. */
    private static int recouvrement(int debutA, int finA, int debutB, int finB) {
        return Math.max(0, Math.min(finA, finB) - Math.max(debutA, debutB));
    }

    public String generateCsv(HeuresRapport rapport) {
        StringBuilder csv = new StringBuilder();
        csv.append("animateur");
        for (String semaine : rapport.semaines()) {
            csv.append(';').append(semaine);
        }
        csv.append(";total;dimanche;jours feries;dont dimanches feries;nuit paie (apres 22h)\n");
        for (HeuresAnimateur ligne : rapport.animateurs()) {
            csv.append(echapper(ligne.nom()));
            for (String semaine : rapport.semaines()) {
                csv.append(';').append(formater(ligne.heuresParSemaine().getOrDefault(semaine, 0.0)));
            }
            csv.append(';')
                    .append(formater(ligne.total()))
                    .append(';')
                    .append(formater(ligne.heuresDimanche()))
                    .append(';')
                    .append(formater(ligne.heuresJourFerie()))
                    .append(';')
                    .append(formater(ligne.heuresDimancheFerie()))
                    .append(';')
                    .append(formater(ligne.heuresNuit()))
                    .append('\n');
        }
        return csv.toString();
    }

    /**
     * Hours with a <b>comma</b>, the decimal separator of the spreadsheets this
     * file is opened in.
     *
     * <p>Written {@code 4.00}, a French Excel or LibreOffice reads the cell as
     * text: it shows up left-aligned and {@code =SOMME()} over the column
     * answers zero, which is worse than a wrong total because it looks like an
     * answer. The field separator being {@code ;}, the comma is unambiguous —
     * that pairing is exactly the convention those spreadsheets expect.</p>
     */
    private String formater(double heures) {
        return String.format(Locale.ROOT, "%.2f", heures).replace('.', ',');
    }

    private String echapper(String valeur) {
        if (valeur == null) {
            return "";
        }
        if (valeur.contains(";") || valeur.contains("\"") || valeur.contains("\n")) {
            return "\"" + valeur.replace("\"", "\"\"") + "\"";
        }
        return valeur;
    }

    /**
     * @param heuresDimanche      hours held on a Sunday, Saturday excluded
     * @param heuresJourFerie     hours held on a French public holiday
     * @param heuresDimancheFerie the overlap of the two above, reported so the
     *                            screen can say what it double-counts rather
     *                            than hide it inside a sum
     * @param heuresNuit          hours past {@link #HEURE_NUIT}, prorated — the
     *                            payroll's « Nuit (paie) », never the evening
     * @param heuresSoiree        hours past the edition's settable start of the
     *                            evening, the very figure the Équité report
     *                            gives the same person
     */
    @Schema(
            requiredProperties = {
                "total",
                "heuresDimanche",
                "heuresJourFerie",
                "heuresDimancheFerie",
                "heuresNuit",
                "heuresSoiree"
            })
    public record HeuresAnimateur(
            String animateurId,
            String nom,
            Map<String, Double> heuresParSemaine,
            double total,
            double heuresDimanche,
            double heuresJourFerie,
            double heuresDimancheFerie,
            double heuresNuit,
            double heuresSoiree) {}

    /**
     * @param heureDebutSoiree the evening the {@code heuresSoiree} of every line
     *                         was read under — the edition's one setting
     */
    @Schema(requiredProperties = {"heureDebutSoiree", "semaines", "animateurs"})
    public record HeuresRapport(LocalTime heureDebutSoiree, List<String> semaines, List<HeuresAnimateur> animateurs) {}
}
