package dev.sylvain.planning.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Hours planned per animateur, grouped by ISO calendar week (from
 * {@code Creneau.date}) plus a grand total. Reuses
 * {@link dev.sylvain.planning.domain.Creneau#getDureeMinutes()}
 * so the midnight-crossing edge case is handled in exactly one place.
 */
@ApplicationScoped
public class HeuresPlanningService {

    public HeuresRapport calculer(PlanningFestival planning) {
        Map<String, Map<String, Double>> heuresParAnimateurEtSemaine = new LinkedHashMap<>();
        Map<String, Double> totalParAnimateur = new LinkedHashMap<>();
        TreeSet<String> semaines = new TreeSet<>();

        for (PosteAffectation poste : planning.getPostes()) {
            if (poste.getAnimateur() == null || poste.getCreneau() == null) {
                continue;
            }
            String animateurId = poste.getAnimateur().getId();
            String semaine = poste.getCreneau().semaineIso();
            double heures = poste.getCreneau().getDureeMinutes() / 60.0;

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
            lignes.add(new HeuresAnimateur(animateur.getId(), nomAffiche(animateur), new LinkedHashMap<>(parSemaine), total));
        }
        return new HeuresRapport(semainesTriees, lignes);
    }

    public String genererCsv(HeuresRapport rapport) {
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

    private String nomAffiche(Animateur animateur) {
        String nom = List.of(animateur.getPrenom(), animateur.getNom()).stream()
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining(" "));
        return nom.isBlank() ? animateur.getId() : nom;
    }

    private String formater(double heures) {
        return String.format(Locale.ROOT, "%.2f", heures);
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

    public record HeuresAnimateur(String animateurId, String nom, Map<String, Double> heuresParSemaine, double total) {
    }

    public record HeuresRapport(List<String> semaines, List<HeuresAnimateur> animateurs) {
    }
}
