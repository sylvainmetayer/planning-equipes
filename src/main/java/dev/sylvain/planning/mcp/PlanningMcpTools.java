package dev.sylvain.planning.mcp;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.service.FeasibilityAnalyzer;
import dev.sylvain.planning.service.FeasibilityAnalyzer.FeasibilityReport;
import dev.sylvain.planning.service.HeuresPlanningService;
import dev.sylvain.planning.service.HeuresPlanningService.HeuresAnimateur;
import dev.sylvain.planning.service.PlanningPersistenceService;
import dev.sylvain.planning.service.PlanningService;
import dev.sylvain.planning.service.PlanningService.AffectationExplanation;
import dev.sylvain.planning.service.PlanningService.ContrainteImpact;
import dev.sylvain.planning.service.PlanningService.SwapSimulation;
import dev.sylvain.planning.service.ReferenceDataChangeTracker;
import dev.sylvain.planning.service.ReferenceDataService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * MCP tools around the planning itself: the size of the problem the next
 * solve will build, the pre-solve feasibility report, what is persisted from
 * the last solve, the hours worked per animateur, and the per-poste
 * explanation/swap simulation of {@code AffectationExplanationResource}.
 *
 * <p>The explanation/swap/hours REST endpoints take a whole
 * {@code PlanningFestival} in their body; here they always run against the
 * planning persisted by the last solve, since an assistant has no practical
 * way to send back a payload that can weigh dozens of MB.
 *
 * <p>Nothing returned here carries a name: hours are reported per animateur
 * id, and the human-readable constraint details are passed through
 * {@link AnonymisationViolations} first — {@code ViolationFormatter} labels
 * animateurs as "Prénom Nom (id)" for the web UI, which must not leak here.
 */
@ApplicationScoped
public class PlanningMcpTools {

    @Inject
    PlanningService planningService;

    @Inject
    PlanningPersistenceService persistenceService;

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    FeasibilityAnalyzer feasibilityAnalyzer;

    @Inject
    HeuresPlanningService heuresPlanningService;

    @Inject
    ReferenceDataChangeTracker changeTracker;

    @Tool(description = "Volumétrie réelle du problème que construirait la prochaine résolution : nombre "
            + "d'animateurs, de postes à pourvoir et de contraintes ad hoc. Tout à zéro si les données de "
            + "référence ne sont pas chargées.")
    VolumetrieView volumetrie() {
        try {
            PlanningFestival festival = planningService.construireDepuisReferenceData();
            return new VolumetrieView(festival.getAnimateurs().size(), festival.getPostes().size(),
                    festival.getContraintesAdHoc().size());
        } catch (IllegalStateException e) {
            return new VolumetrieView(0, 0, 0);
        }
    }

    @Tool(description = "Diagnostic de faisabilité avant résolution : calcul de capacité en Java pur (aucune "
            + "résolution lancée) sur les données de référence courantes, listant les causes structurellement "
            + "bloquantes (stand sans animateur compétent, créneau en sous-effectif).")
    FeasibilityReport analyser_faisabilite() {
        return feasibilityAnalyzer.analyser(
                referenceDataService.listAnimateurs(),
                referenceDataService.listStandsResolus(),
                referenceDataService.listCreneauxGroupeActif());
    }

    @Tool(description = "État du planning persisté : pour quel groupe de créneaux la dernière résolution a "
            + "tourné, quand, combien d'affectations sont stockées, et quand les données de référence ont été "
            + "modifiées pour la dernière fois (si c'est après la résolution, le planning affiché est périmé).")
    EtatPlanningView etat_planning() {
        PlanningPersistenceService.PlanningResolution resolution = persistenceService.loadResolution();
        int affectations = persistenceService.countPersistedAssignments();
        Instant derniereModificationDonnees = changeTracker.lastModifiedAt();
        if (resolution == null) {
            return new EtatPlanningView(false, null, null, null, affectations, derniereModificationDonnees);
        }
        return new EtatPlanningView(true, resolution.groupeCreneauId(), resolution.groupeCreneauNom(),
                resolution.resoluLe(), affectations, derniereModificationDonnees);
    }

    @Tool(description = "Affectations du dernier planning persisté, filtrables par stand, par créneau ou par "
            + "animateur. Ne renvoie que des ids, jamais de données personnelles. Les postes non pourvus ont un "
            + "animateurId nul.")
    List<AffectationView> lister_affectations(
            @ToolArg(description = "Id de stand pour filtrer", required = false) String standId,
            @ToolArg(description = "Id de créneau pour filtrer", required = false) Long creneauId,
            @ToolArg(description = "Id d'animateur pour filtrer", required = false) String animateurId,
            @ToolArg(description = "Ne garder que les postes non pourvus", required = false) Boolean seulementNonPourvus) {
        PlanningFestival planning = persistenceService.loadPersistedPlanning();
        if (planning == null || planning.getPostes() == null) {
            return List.of();
        }
        return planning.getPostes().stream()
                .filter(poste -> standId == null
                        || (poste.getStand() != null && standId.equals(poste.getStand().getId())))
                .filter(poste -> creneauId == null
                        || (poste.getCreneau() != null && creneauId.equals(poste.getCreneau().getId())))
                .filter(poste -> animateurId == null
                        || (poste.getAnimateur() != null && animateurId.equals(poste.getAnimateur().getId())))
                .filter(poste -> !Boolean.TRUE.equals(seulementNonPourvus) || poste.getAnimateur() == null)
                .map(PlanningMcpTools::toView)
                .toList();
    }

    @Tool(description = "Heures travaillées par animateur d'après le dernier planning persisté, par semaine ISO "
            + "et au total. Les animateurs sont désignés par id seul.")
    HeuresView heures_travaillees() {
        PlanningFestival planning = persistenceService.loadPersistedPlanning();
        if (planning == null || planning.getPostes() == null) {
            return new HeuresView(List.of(), List.of());
        }
        HeuresPlanningService.HeuresRapport rapport = heuresPlanningService.calculer(planning);
        return new HeuresView(rapport.semaines(), rapport.animateurs().stream()
                .map(PlanningMcpTools::toView)
                .toList());
    }

    @Tool(description = "Explique le score d'un poste du dernier planning persisté : contraintes violées et "
            + "contraintes respectées le concernant. Ne relance aucune résolution.")
    ExplicationView expliquer_affectation(@ToolArg(description = "Id du poste") String posteId) {
        AffectationExplanation explication = planningService.expliquerAffectation(planningPersiste(), posteId);
        return new ExplicationView(explication.posteId(), explication.animateurId(),
                String.valueOf(explication.score()),
                toViews(explication.contraintesViolees()), toViews(explication.contraintesRespectees()));
    }

    @Tool(description = "Simule l'affectation d'un poste à un autre animateur sur le dernier planning persisté, "
            + "et renvoie l'impact sur le score. Ne persiste rien et ne relance aucune résolution.")
    SwapView simuler_swap(
            @ToolArg(description = "Id du poste") String posteId,
            @ToolArg(description = "Id de l'animateur candidat") String animateurId) {
        SwapSimulation simulation = planningService.simulerSwap(planningPersiste(), posteId, animateurId);
        return new SwapView(simulation.posteId(), simulation.animateurActuelId(), simulation.animateurCandidatId(),
                String.valueOf(simulation.scoreAvant()), String.valueOf(simulation.scoreApres()),
                String.valueOf(simulation.delta()),
                toViews(simulation.contraintesVioleesAvant()), toViews(simulation.contraintesVioleesApres()));
    }

    private PlanningFestival planningPersiste() {
        PlanningFestival planning = persistenceService.loadPersistedPlanning();
        if (planning == null || planning.getPostes() == null || planning.getPostes().isEmpty()) {
            throw new IllegalStateException("Aucun planning persisté : lancez d'abord une résolution.");
        }
        return planning;
    }

    private static List<ContrainteImpactView> toViews(List<ContrainteImpact> impacts) {
        return impacts.stream()
                .map(impact -> new ContrainteImpactView(impact.name(), impact.niveau(), impact.categorie(),
                        impact.description(), impact.matchCount(), AnonymisationViolations.anonymiser(impact.details())))
                .toList();
    }

    private static AffectationView toView(PosteAffectation poste) {
        return new AffectationView(poste.getId(),
                poste.getStand() == null ? null : poste.getStand().getId(),
                poste.getStand() == null ? null : poste.getStand().getNom(),
                poste.getCreneau() == null ? null : poste.getCreneau().getId(),
                poste.getCreneau() == null ? null : poste.getCreneau().getDate(),
                poste.getHeureDebutEffective(), poste.getHeureFinEffective(),
                poste.getAnimateur() == null ? null : poste.getAnimateur().getId());
    }

    private static HeuresAnimateurView toView(HeuresAnimateur ligne) {
        return new HeuresAnimateurView(ligne.animateurId(), ligne.heuresParSemaine(), ligne.total());
    }

    public record VolumetrieView(int animateurCount, int posteCount, int contrainteAdHocCount) {
    }

    /** @param resolu false quand rien n'a jamais été résolu */
    public record EtatPlanningView(boolean resolu, String groupeCreneauId, String groupeCreneauNom,
            Instant resoluLe, int affectationsPersistees, Instant derniereModificationDonnees) {
    }

    public record AffectationView(String posteId, String standId, String standNom, Long creneauId,
            LocalDate date, LocalTime heureDebut, LocalTime heureFin, String animateurId) {
    }

    public record HeuresView(List<String> semaines, List<HeuresAnimateurView> animateurs) {
    }

    public record HeuresAnimateurView(String animateurId, Map<String, Double> heuresParSemaine, double total) {
    }

    public record ExplicationView(String posteId, String animateurId, String score,
            List<ContrainteImpactView> contraintesViolees, List<ContrainteImpactView> contraintesRespectees) {
    }

    public record SwapView(String posteId, String animateurActuelId, String animateurCandidatId,
            String scoreAvant, String scoreApres, String delta,
            List<ContrainteImpactView> contraintesVioleesAvant, List<ContrainteImpactView> contraintesVioleesApres) {
    }

    /** @param details lignes lisibles décrivant chaque correspondance, anonymisées (id d'animateur, jamais de nom) */
    public record ContrainteImpactView(String nom, String niveau, String categorie, String description,
            int nombreCorrespondances, List<String> details) {
    }
}
