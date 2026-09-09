package dev.sylvain.planning.service;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Hours planned per animateur, grouped by ISO calendar week (from
 * {@code Creneau.date}) plus a grand total. Reuses
 * {@link dev.sylvain.planning.domain.PosteAffectation#getDureeEffectiveMinutes()}
 * so the midnight-crossing edge case (delegated to {@code Creneau.getDureeMinutes()}
 * when a poste has no effective-window override) is handled in exactly one
 * place, and a poste narrowed by a partial stand closure (issue #60) counts
 * only the time actually staffed, not its créneau's full span.
 */
@ApplicationScoped
public class PlanningHoursService {

    public HeuresRapport compute(PlanningEvenement planning) {
        Map<String, Map<String, Double>> heuresParAnimateurEtSemaine = new LinkedHashMap<>();
        Map<String, Double> totalParAnimateur = new LinkedHashMap<>();
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
        }

        List<String> semainesTriees = new ArrayList<>(semaines);
        List<HeuresAnimateur> lignes = new ArrayList<>();
        for (Animateur animateur : planning.getAnimateurs()) {
            Map<String, Double> parSemaine = heuresParAnimateurEtSemaine.getOrDefault(animateur.getId(), Map.of());
            double total = totalParAnimateur.getOrDefault(animateur.getId(), 0.0);
            lignes.add(new HeuresAnimateur(animateur.getId(), animateur.nomAffiche(), new LinkedHashMap<>(parSemaine), total));
        }
        return new HeuresRapport(semainesTriees, lignes);
    }

    public String generateCsv(HeuresRapport rapport) {
        StringBuilder csv = new StringBuilder();
        csv.append("animateur");
        for (String semaine : rapport.semaines()) {
            csv.append(';').append(semaine);
        }
        csv.append(";total\n");
        for (HeuresAnimateur ligne : rapport.animateurs()) {
            csv.append(echapper(ligne.nom()));
            for (String semaine : rapport.semaines()) {
                csv.append(';').append(formater(ligne.heuresParSemaine().getOrDefault(semaine, 0.0)));
            }
            csv.append(';').append(formater(ligne.total())).append('\n');
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

    @Schema(requiredProperties = {"total"})
    public record HeuresAnimateur(String animateurId, String nom, Map<String, Double> heuresParSemaine, double total) {
    }

    public record HeuresRapport(List<String> semaines, List<HeuresAnimateur> animateurs) {
    }
}
