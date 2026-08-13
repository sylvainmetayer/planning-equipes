package dev.sylvain.planning.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
 * <p>The result is a ranked list of blocking causes
 * ({@link CauseInfaisabilite}), each carrying a severity and the concrete
 * entities involved, so the setup screen can display them before any solve is
 * launched. The only kind of cause detected is
 * {@link TypeCauseInfaisabilite#CRENEAU_SOUS_EFFECTIF} — the demand of a
 * créneau exceeds the number of animateurs available to serve it.
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
 * <p>Against that demand we count, for each créneau, every animateur present
 * that day — an optimistic upper bound on how many seats that créneau could
 * fill, since it ignores which specific stand each animateur would need to
 * cover. The reported shortfall is therefore a floor: the real gap can only be
 * equal or worse.
 *
 * <p>Competence is deliberately <b>not</b> part of this capacity count: the
 * business treats it as an administrator's post-formation appreciation,
 * enforced only as a medium constraint
 * ({@code QualiteConstraints.appreciationIncompatible}), so any available
 * animateur can literally be assigned to any stand — an appreciation mismatch
 * is a quality penalty visible on the calendar and the constraint score, never
 * a pre-solve blocking cause.</p>
 */
@ApplicationScoped
public class FeasibilityAnalyzer {

    /** Number of causes returned to the caller; the rest is only counted. */
    private static final int MAX_CAUSES = 10;

    /** Number of stand names spelled out in a créneau message before eliding. */
    private static final int MAX_STANDS_NOMMES = 3;

    /**
     * Causes are ranked so the first ones are the most blocking: CRITIQUE
     * before ELEVE, then by decreasing shortfall, and finally by créneau id so
     * two runs on the same data return the same order.
     */
    private static final Comparator<CauseInfaisabilite> ORDRE_CAUSES = Comparator
            .<CauseInfaisabilite, SeveriteInfaisabilite>comparing(CauseInfaisabilite::severite)
            .thenComparing(CauseInfaisabilite::manque, Comparator.reverseOrder())
            .thenComparingLong(cause -> cause.creneauId() == null ? Long.MIN_VALUE : cause.creneauId());

    public FeasibilityReport analyser(List<Animateur> animateurs, List<Stand> stands, List<Creneau> creneaux) {
        List<Animateur> animateursSurs = animateurs == null ? List.of() : animateurs;
        List<Stand> standsSurs = stands == null ? List.of() : stands;
        List<Creneau> creneauxSurs = creneaux == null ? List.of() : creneaux;

        List<CauseInfaisabilite> causes = new ArrayList<>(creneauxSousEffectif(animateursSurs, standsSurs, creneauxSurs));
        causes.sort(ORDRE_CAUSES);

        int manqueAnimateurs = causes.stream()
                .filter(cause -> cause.type() == TypeCauseInfaisabilite.CRENEAU_SOUS_EFFECTIF)
                .mapToInt(CauseInfaisabilite::manque)
                .max()
                .orElse(0);
        int totalCauses = causes.size();
        boolean feasible = totalCauses == 0;
        List<CauseInfaisabilite> topCauses = List.copyOf(causes.subList(0, Math.min(MAX_CAUSES, totalCauses)));

        return new FeasibilityReport(feasible, manqueAnimateurs, topCauses, totalCauses,
                construireMessage(feasible, manqueAnimateurs, totalCauses, topCauses));
    }

    private List<CauseInfaisabilite> creneauxSousEffectif(List<Animateur> animateurs, List<Stand> stands,
            List<Creneau> creneaux) {
        List<CauseInfaisabilite> causes = new ArrayList<>();
        for (Creneau creneau : creneaux) {
            List<Stand> standsOuverts = stands.stream()
                    .filter(stand -> creneau.estStandOuvert(stand))
                    .toList();
            int demande = standsOuverts.stream()
                    .mapToInt(stand -> Math.max(1, stand.getEffectifMin()))
                    .sum();
            long capacite = animateurs.stream()
                    .filter(animateur -> !animateur.estIndisponibleLe(creneau.getDate()))
                    .count();
            int manque = (int) Math.max(0, demande - capacite);
            if (manque <= 0) {
                continue;
            }
            causes.add(new CauseInfaisabilite(
                    TypeCauseInfaisabilite.CRENEAU_SOUS_EFFECTIF,
                    manque >= demande ? SeveriteInfaisabilite.CRITIQUE : SeveriteInfaisabilite.ELEVE,
                    "Le " + decrireCreneau(creneau) + ", il manque " + manque + " " + motAnimateur(manque)
                            + " pour couvrir " + decrireStands(standsOuverts) + ".",
                    creneau.getId(), creneau.getDate(), creneau.getHeureDebut(), creneau.getHeureFin(),
                    standsOuverts.stream().map(Stand::getId).toList(),
                    demande, (int) capacite, manque));
        }
        return causes;
    }

    private String construireMessage(boolean feasible, int manque, int totalCauses,
            List<CauseInfaisabilite> topCauses) {
        if (feasible) {
            return "Le planning est réalisable : il y a assez d'animateurs disponibles pour couvrir chaque créneau.";
        }
        String causesPhrase = totalCauses > 1
                ? totalCauses + " causes bloquantes ont été détectées"
                : "1 cause bloquante a été détectée";
        if (manque <= 0) {
            return "Ce planning n'est pas réalisable avec les données actuelles : " + causesPhrase
                    + ". Par exemple : " + topCauses.getFirst().message();
        }
        return "Ce planning n'est pas réalisable avec les animateurs actuels : il manque au moins " + manque + " "
                + motAnimateur(manque) + " sur un créneau, et " + causesPhrase + ".";
    }

    private static String motAnimateur(int manque) {
        return manque > 1 ? "animateurs" : "animateur";
    }

    private static String decrireCreneau(Creneau creneau) {
        if (creneau.getDate() == null) {
            return "créneau " + creneau.getId();
        }
        if (creneau.getHeureDebut() == null || creneau.getHeureFin() == null) {
            return String.valueOf(creneau.getDate());
        }
        return creneau.getDate() + " " + creneau.getHeureDebut() + "-" + creneau.getHeureFin();
    }

    /**
     * Spells out at most {@value #MAX_STANDS_NOMMES} stand names so the
     * sentence stays readable on a festival with dozens of open stands.
     */
    private static String decrireStands(List<Stand> stands) {
        if (stands.isEmpty()) {
            return "les stands ouverts";
        }
        String nommes = stands.stream()
                .limit(MAX_STANDS_NOMMES)
                .map(FeasibilityAnalyzer::nomStand)
                .collect(Collectors.joining(", "));
        int restants = stands.size() - MAX_STANDS_NOMMES;
        return restants > 0 ? nommes + " et " + restants + (restants > 1 ? " autres stands" : " autre stand") : nommes;
    }

    private static String nomStand(Stand stand) {
        return stand.getNom() == null || stand.getNom().isBlank() ? stand.getId() : stand.getNom();
    }

    /** Kind of blocking cause detected before any solve. */
    public enum TypeCauseInfaisabilite {
        CRENEAU_SOUS_EFFECTIF
    }

    /**
     * Severity ordered from the most to the least blocking: the enum order is
     * the sort order used by {@link #ORDRE_CAUSES}.
     */
    public enum SeveriteInfaisabilite {
        CRITIQUE,
        ELEVE
    }

    /**
     * One blocking cause, ready to display.
     *
     * @param message    French sentence describing the cause
     * @param standIds   stands concerned, always at least one
     * @param demande    seats to fill
     * @param capacite   animateurs able to fill them
     * @param manque     {@code demande - capacite}
     */
    public record CauseInfaisabilite(
            TypeCauseInfaisabilite type,
            SeveriteInfaisabilite severite,
            String message,
            Long creneauId,
            LocalDate date,
            LocalTime heureDebut,
            LocalTime heureFin,
            List<String> standIds,
            int demande,
            int capacite,
            int manque) {
    }

    /**
     * @param manqueAnimateurs worst single-créneau shortfall, {@code 0} when no
     *                         créneau is under-staffed
     * @param causes           ranked causes, capped to the ten most blocking
     * @param totalCauses      number of causes found <em>before</em> capping,
     *                         so the UI can say "+N autres"
     */
    public record FeasibilityReport(
            boolean feasible,
            int manqueAnimateurs,
            List<CauseInfaisabilite> causes,
            int totalCauses,
            String message) {
    }
}
