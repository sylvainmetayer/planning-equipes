package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.JoursFeries;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import jakarta.enterprise.context.ApplicationScoped;
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
 * {@code Creneau.date}), a grand total, and the three counters the payroll
 * needs for its premiums — Sunday hours, public-holiday hours, hours past
 * 22:00 (issue #597). Reuses
 * {@link dev.sylvain.planning.domain.PosteAffectation#getDureeEffectiveMinutes()}
 * so the midnight-crossing edge case (delegated to {@code Creneau.getDureeMinutes()}
 * when a poste has no effective-window override) is handled in exactly one
 * place, and a poste narrowed by a partial stand closure (issue #60) counts
 * only the time actually staffed, not its créneau's full span.
 */
@ApplicationScoped
public class PlanningHoursService {

    /**
     * 22:00 — the hour the organiser counts its night hours from, and the one art.
     * <b>L3163-1</b> opens a 16-to-18 year old's night at.
     *
     * <p>Not {@code ParametresLegaux.heureDebutSoiree}, which is 20:00 by
     * default: that one is a comfort threshold for comparing evening duty
     * across the roster on the Équité screen, and moving it must not move a
     * payroll figure (issue #597).</p>
     *
     * <p>Not the Code du travail's own night period either, which art.
     * <b>L3122-2</b> sets at 21:00-06:00 <i>absent a collective agreement</i>.
     * The counter reports what the organisation asked for; whether the
     * applicable convention moves that bound is what {@code
     * docs/audit-conformite-rh.md} leaves to confirm.</p>
     */
    public static final LocalTime HEURE_NUIT = LocalTime.of(22, 0);

    /** 06:00, where the night ends — the same bound every minors' rule uses. */
    private static final LocalTime FIN_NUIT = Creneau.FIN_NUIT;

    private static final int SECONDES_PAR_JOUR = 24 * 3600;

    public HeuresRapport compute(PlanningEvenement planning) {
        Map<String, Map<String, Double>> heuresParAnimateurEtSemaine = new LinkedHashMap<>();
        Map<String, Double> totalParAnimateur = new LinkedHashMap<>();
        Map<String, Double> dimancheParAnimateur = new LinkedHashMap<>();
        Map<String, Double> ferieParAnimateur = new LinkedHashMap<>();
        Map<String, Double> dimancheFerieParAnimateur = new LinkedHashMap<>();
        Map<String, Double> nuitParAnimateur = new LinkedHashMap<>();
        TreeSet<String> semaines = new TreeSet<>();

        for (PosteAffectation poste : planning.getPostes()) {
            if (poste.getAnimateur() == null || poste.getCreneau() == null) {
                continue;
            }
            String animateurId = poste.getAnimateur().getId();
            String semaine = poste.getCreneau().semaineIso();
            double heures = poste.getDureeEffectiveMinutes() / 60.0;

            semaines.add(semaine);
            heuresParAnimateurEtSemaine
                    .computeIfAbsent(animateurId, id -> new LinkedHashMap<>())
                    .merge(semaine, heures, Double::sum);
            totalParAnimateur.merge(animateurId, heures, Double::sum);

            LocalDate date = poste.getCreneau().getDate();
            if (date == null) {
                continue;
            }
            // Sunday alone, never « week-end »: only Sunday carries a premium,
            // and the Équité screen's heuresWeekEnd adds Saturday in — which is
            // why that column could not answer this question (issue #597).
            boolean dimanche = date.getDayOfWeek() == DayOfWeek.SUNDAY;
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
                    nuitParAnimateur.getOrDefault(id, 0.0)));
        }
        return new HeuresRapport(semainesTriees, lignes);
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
        csv.append(";total;dimanche;jours feries;dont dimanches feries;apres 22h\n");
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
     * @param heuresNuit          hours past {@link #HEURE_NUIT}, prorated
     */
    @Schema(requiredProperties = {"total", "heuresDimanche", "heuresJourFerie", "heuresDimancheFerie", "heuresNuit"})
    public record HeuresAnimateur(
            String animateurId,
            String nom,
            Map<String, Double> heuresParSemaine,
            double total,
            double heuresDimanche,
            double heuresJourFerie,
            double heuresDimancheFerie,
            double heuresNuit) {}

    public record HeuresRapport(List<String> semaines, List<HeuresAnimateur> animateurs) {}
}
