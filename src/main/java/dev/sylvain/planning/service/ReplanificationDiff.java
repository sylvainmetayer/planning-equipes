package dev.sylvain.planning.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;

/**
 * What an incremental re-solve actually changed (issue #86). Replanning
 * partially is only worth it if one can say <b>who</b> is impacted — "seules
 * ces trois personnes ont un planning différent" — so the result carries the
 * diff, not just a score.
 *
 * <p>The comparison is per stand × créneau, the same positional convention the
 * reconciliation itself uses: the seats of one stand on one créneau are
 * interchangeable, so two of them swapping holders is not a change anybody
 * needs to be told about.</p>
 */
public final class ReplanificationDiff {

    private ReplanificationDiff() {
    }

    /**
     * One stand × créneau whose crew changed, both crews spelled out as display
     * names — the audience of this list is whoever will call the animateurs.
     */
    public record ChangementAffectation(
            String standId,
            String standNom,
            long creneauId,
            String date,
            String heureDebut,
            String heureFin,
            List<String> avant,
            List<String> apres) {
    }

    /**
     * The stand × créneau cells whose crew differs between the persisted plan
     * the incremental solve started from and the solution it produced. A cell
     * the new problem no longer holds (deleted stand or créneau) is not
     * reported: its seats no longer exist to have a crew.
     */
    public static List<ChangementAffectation> calculer(Map<String, List<String>> avant, PlanningFestival solved) {
        Map<String, Animateur> animateursParId = new LinkedHashMap<>();
        if (solved.getAnimateurs() != null) {
            for (Animateur animateur : solved.getAnimateurs()) {
                animateursParId.put(animateur.getId(), animateur);
            }
        }
        Map<String, List<PosteAffectation>> parCle = new LinkedHashMap<>();
        for (PosteAffectation poste : solved.getPostes()) {
            if (poste.getStand() == null || poste.getCreneau() == null) {
                continue;
            }
            parCle.computeIfAbsent(
                    PlanningPersistenceService.cleStandCreneau(poste.getStand().getId(), poste.getCreneau().getId()),
                    key -> new ArrayList<>()).add(poste);
        }
        List<ChangementAffectation> changements = new ArrayList<>();
        for (Map.Entry<String, List<PosteAffectation>> entry : parCle.entrySet()) {
            List<String> idsApres = new ArrayList<>();
            for (PosteAffectation poste : entry.getValue()) {
                if (poste.getAnimateur() != null) {
                    idsApres.add(poste.getAnimateur().getId());
                }
            }
            List<String> triAvant = avant.getOrDefault(entry.getKey(), List.of()).stream().sorted().toList();
            List<String> triApres = idsApres.stream().sorted().toList();
            if (triAvant.equals(triApres)) {
                continue;
            }
            PosteAffectation temoin = entry.getValue().get(0);
            changements.add(new ChangementAffectation(
                    temoin.getStand().getId(),
                    temoin.getStand().getNom(),
                    temoin.getCreneau().getId(),
                    texte(temoin.getCreneau().getDate()),
                    texte(temoin.heureDebutEffectif()),
                    texte(temoin.heureFinEffectif()),
                    noms(triAvant, animateursParId),
                    noms(triApres, animateursParId)));
        }
        changements.sort(Comparator
                .comparing((ChangementAffectation changement) -> changement.date() == null ? "" : changement.date())
                .thenComparing(changement -> changement.heureDebut() == null ? "" : changement.heureDebut())
                .thenComparing(ChangementAffectation::standNom, Comparator.nullsFirst(Comparator.naturalOrder())));
        return changements;
    }

    /** Display names, falling back to the raw id for an animateur the referential lost. */
    private static List<String> noms(List<String> ids, Map<String, Animateur> animateursParId) {
        List<String> noms = new ArrayList<>();
        for (String id : ids) {
            Animateur animateur = animateursParId.get(id);
            if (animateur == null) {
                noms.add(id);
                continue;
            }
            String nom = ((animateur.getPrenom() == null ? "" : animateur.getPrenom()) + " "
                    + (animateur.getNom() == null ? "" : animateur.getNom())).trim();
            noms.add(nom.isEmpty() ? id : nom);
        }
        return noms;
    }

    private static String texte(Object valeur) {
        return valeur == null ? null : valeur.toString();
    }
}
