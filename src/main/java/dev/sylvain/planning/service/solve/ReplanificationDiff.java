package dev.sylvain.planning.service.solve;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

    private ReplanificationDiff() {}

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
            List<String> apres) {}

    /**
     * The stand × créneau cells whose crew differs between the persisted plan
     * the incremental solve started from and the solution it produced. A cell
     * the new problem no longer holds (deleted stand or créneau) is not
     * reported: its seats no longer exist to have a crew.
     */
    public static List<ChangementAffectation> compute(Map<String, List<String>> avant, PlanningEvenement solved) {
        Map<String, Animateur> animateursById = new LinkedHashMap<>();
        if (solved.getAnimateurs() != null) {
            for (Animateur animateur : solved.getAnimateurs()) {
                animateursById.put(animateur.getId(), animateur);
            }
        }
        List<ChangementAffectation> changements = new ArrayList<>();
        for (Map.Entry<String, List<PosteAffectation>> entry :
                seatsByStandAndCreneau(solved).entrySet()) {
            List<String> sortedBefore = avant.getOrDefault(entry.getKey(), List.of()).stream()
                    .sorted()
                    .toList();
            List<String> sortedAfter = holders(entry.getValue());
            if (sortedBefore.equals(sortedAfter)) {
                continue;
            }
            PosteAffectation temoin = entry.getValue().get(0);
            changements.add(new ChangementAffectation(
                    temoin.getStand().getId(),
                    temoin.getStand().getNom(),
                    temoin.getCreneau().getId(),
                    text(temoin.getCreneau().getDate()),
                    text(temoin.heureDebutEffectif()),
                    text(temoin.heureFinEffectif()),
                    noms(sortedBefore, animateursById),
                    noms(sortedAfter, animateursById)));
        }
        changements.sort(Comparator.comparing(
                        (ChangementAffectation changement) -> changement.date() == null ? "" : changement.date())
                .thenComparing(changement -> changement.heureDebut() == null ? "" : changement.heureDebut())
                .thenComparing(ChangementAffectation::standNom, Comparator.nullsFirst(Comparator.naturalOrder())));
        return changements;
    }

    /**
     * The dates whose crew a solve changed — the same comparison {@link #compute}
     * makes, read one grain coarser. A day appears as soon as one of its stand ×
     * timeslot cells holds a different set of animateurs than the persisted plan
     * did.
     *
     * <p>This is what withdraws the « relu et accepté » of a day nobody has read
     * since (issue « validation de relecture »): the review mark must not survive
     * the solve that moved what was read.</p>
     */
    public static Set<LocalDate> joursModifies(Map<String, List<String>> avant, PlanningEvenement solved) {
        Set<LocalDate> jours = new LinkedHashSet<>();
        for (Map.Entry<String, List<PosteAffectation>> entry :
                seatsByStandAndCreneau(solved).entrySet()) {
            List<String> sortedBefore = avant.getOrDefault(entry.getKey(), List.of()).stream()
                    .sorted()
                    .toList();
            if (sortedBefore.equals(holders(entry.getValue()))) {
                continue;
            }
            LocalDate date = entry.getValue().get(0).getCreneau().getDate();
            if (date != null) {
                jours.add(date);
            }
        }
        return jours;
    }

    /** The seats of one plan, grouped by the stand × timeslot cell they fill. */
    private static Map<String, List<PosteAffectation>> seatsByStandAndCreneau(PlanningEvenement solved) {
        Map<String, List<PosteAffectation>> parCle = new LinkedHashMap<>();
        if (solved == null || solved.getPostes() == null) {
            return parCle;
        }
        for (PosteAffectation poste : solved.getPostes()) {
            if (poste.getStand() == null || poste.getCreneau() == null) {
                continue;
            }
            parCle.computeIfAbsent(
                            PlanningPersistenceService.standCreneauKey(
                                    poste.getStand().getId(), poste.getCreneau().getId()),
                            key -> new ArrayList<>())
                    .add(poste);
        }
        return parCle;
    }

    /** Who holds a cell's seats, sorted: the seats of one cell are interchangeable. */
    private static List<String> holders(List<PosteAffectation> postes) {
        return postes.stream()
                .map(PosteAffectation::getAnimateur)
                .filter(Objects::nonNull)
                .map(Animateur::getId)
                .sorted()
                .toList();
    }

    /** Display names, falling back to the raw id for an animateur the referential lost. */
    private static List<String> noms(List<String> ids, Map<String, Animateur> animateursById) {
        List<String> noms = new ArrayList<>();
        for (String id : ids) {
            Animateur animateur = animateursById.get(id);
            if (animateur == null) {
                noms.add(id);
                continue;
            }
            String nom = ((animateur.getPrenom() == null ? "" : animateur.getPrenom()) + " "
                            + (animateur.getNom() == null ? "" : animateur.getNom()))
                    .trim();
            noms.add(nom.isEmpty() ? id : nom);
        }
        return noms;
    }

    private static String text(Object valeur) {
        return valeur == null ? null : valeur.toString();
    }
}
