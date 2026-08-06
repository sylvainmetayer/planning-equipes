package dev.sylvain.planning.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Stand;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Plain-Java (no Timefold) capacity check meant for non-technical users: given
 * the reference data, is there even a theoretical chance to fill every seat,
 * or is the problem structurally short of animateurs regardless of how long
 * the solver runs?
 *
 * <p>The demand of a créneau is counted exactly as
 * {@link PlanningService#construirePostes(List, List)} generates seats:
 * {@code max(1, effectifMin)} per stand actually open on that créneau (see
 * {@link Creneau#estStandOuvert(String)}). {@code effectifMax} is the upper
 * capacity a stand <em>could</em> accept, not the number of seats that must be
 * staffed, and closed stands generate no seat at all — counting either of them
 * as demand overstates it and reports a shortfall on plannings the solver fills
 * without trouble.
 *
 * <p>Against that demand we count, for each créneau, the animateurs who are
 * both present that day and competent for at least one stand — an optimistic
 * upper bound on how many seats that créneau could fill, since it ignores which
 * specific stand each animateur would need to cover. The reported shortfall is
 * therefore a floor: the real gap can only be equal or worse.
 */
@ApplicationScoped
public class FeasibilityAnalyzer {

    public FeasibilityReport analyser(List<Animateur> animateurs, List<Stand> stands, List<Creneau> creneaux) {
        List<Animateur> animateursSurs = animateurs == null ? List.of() : animateurs;
        List<Stand> standsSurs = stands == null ? List.of() : stands;
        List<Creneau> creneauxSurs = creneaux == null ? List.of() : creneaux;

        Set<String> animateursCompetents = animateursSurs.stream()
                .filter(animateur -> standsSurs.stream().anyMatch(animateur::possedeCompetencePour))
                .map(Animateur::getId)
                .collect(Collectors.toSet());

        CreneauManque pire = null;
        for (Creneau creneau : creneauxSurs) {
            int demande = standsSurs.stream()
                    .filter(stand -> creneau.estStandOuvert(stand.getId()))
                    .mapToInt(stand -> Math.max(1, stand.getEffectifMin()))
                    .sum();
            long capacite = animateursSurs.stream()
                    .filter(animateur -> animateursCompetents.contains(animateur.getId()))
                    .filter(animateur -> !animateur.estIndisponibleLe(creneau.getDate()))
                    .count();
            int manque = (int) Math.max(0, demande - capacite);
            if (pire == null || manque > pire.manque()) {
                pire = new CreneauManque(creneau.getId(), creneau.getDate(), creneau.getHeureDebut(),
                        creneau.getHeureFin(), manque);
            }
        }

        int manqueAnimateurs = pire == null ? 0 : pire.manque();
        boolean feasible = manqueAnimateurs <= 0;
        return new FeasibilityReport(feasible, manqueAnimateurs, feasible ? null : pire,
                construireMessage(feasible, manqueAnimateurs, pire));
    }

    private String construireMessage(boolean feasible, int manque, CreneauManque pire) {
        if (feasible) {
            return "Le planning est réalisable : il y a assez d'animateurs disponibles et compétents "
                    + "pour couvrir chaque créneau.";
        }
        String creneauDescription = pire.date() != null
                ? pire.date() + (pire.heureDebut() != null && pire.heureFin() != null
                        ? " " + pire.heureDebut() + "-" + pire.heureFin()
                        : "")
                : String.valueOf(pire.creneauId());
        String animateurMot = manque > 1 ? "animateurs" : "animateur";
        return "Ce planning n'est pas réalisable avec les animateurs actuels : il manque au moins " + manque + " "
                + animateurMot + " (par exemple le " + creneauDescription + ") pour couvrir tous les postes.";
    }

    public record CreneauManque(Long creneauId, LocalDate date, LocalTime heureDebut, LocalTime heureFin,
            int manque) {
    }

    public record FeasibilityReport(boolean feasible, int manqueAnimateurs, CreneauManque creneauLePlusCritique,
            String message) {
    }
}
