package dev.sylvain.planning.service;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import ai.timefold.solver.core.api.solver.Solver;

import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.service.notification.Notification;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

/**
 * Ce qu'un solve fait <b>toujours</b>, de bout en bout : capturer le plan qu'il
 * va écraser, construire son problème, résoudre, persister, diagnostiquer,
 * alimenter l'écran Contraintes, écrire la ligne de KPI, annoncer la fin.
 *
 * <p>C'était écrit quatre fois. Trois copies étaient complètes, la quatrième —
 * le solve synchrone de {@code POST /api/planning/solve} — s'arrêtait après
 * « persister ». La conséquence n'était pas cosmétique : l'écran Contraintes
 * restait sur l'analyse du solve <i>précédent</i>, donc affichait des
 * violations qui ne décrivaient plus le plan persisté, et aucun KPI n'était
 * écrit. C'est la raison d'être de cette classe : il n'y a plus de « chemin
 * qui oublie une étape », il n'y a qu'un chemin.</p>
 *
 * <p>Deux coutures seulement, parce que ce sont les deux seules choses qui
 * varient réellement d'un appelant à l'autre : <b>comment le problème est
 * construit</b> (fourni par la requête, bâti depuis le référentiel, ou
 * incrémental) et <b>ce qui doit tenir le solveur</b> pour pouvoir l'arrêter
 * — un job de fond en a besoin, un appel synchrone non.</p>
 */
@ApplicationScoped
public class ResolutionPipeline {

    @Inject
    PlanSnapshotService snapshotService;

    @Inject
    PlanningService planningService;

    @Inject
    PlanningPersistenceService persistenceService;

    @Inject
    ConstraintAnalysisStore analysisStore;

    @Inject
    KpiHistoriqueService kpiHistoriqueService;

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    EditionService editionService;

    @Inject
    Event<Notification> notifications;

    /**
     * Ce qu'un solve a produit.
     *
     * @param probleme    l'objet dont il est parti, rendu tel quel — une
     *                    replanification incrémentale a besoin de le relire
     *                    ensuite (périmètre gelé, affectations précédentes)
     * @param planning    le plan résolu, déjà persisté
     * @param diagnostic  son score et ses violations, déjà enregistrés
     */
    public record Resolution<P>(P probleme, PlanningFestival planning,
            PlanningService.PlanningDiagnostic diagnostic) {
    }

    /**
     * Le cas courant : le problème est déjà là, et l'édition est celle du
     * thread courant. C'est la forme qu'appelle {@code POST /api/planning/solve}.
     */
    public Resolution<PlanningFestival> executer(PlanningFestival probleme, Long secondsLimit) {
        return executer(editionService.editionCourante().getNom(), () -> probleme,
                Function.identity(), secondsLimit, null);
    }

    /** Même chose, pour un job de fond qui doit pouvoir arrêter son solveur. */
    public Resolution<PlanningFestival> executer(String editionNom, PlanningFestival probleme,
            Long secondsLimit, Consumer<Solver<PlanningFestival>> attacheSolveur) {
        return executer(editionNom, () -> probleme, Function.identity(), secondsLimit, attacheSolveur);
    }

    /**
     * La forme complète, pour un problème qui doit être bâti <b>dans</b> le
     * job : une tâche mise en file doit résoudre l'édition telle qu'elle est
     * quand son tour vient, pas telle qu'elle était au clic.
     *
     * @param construireProbleme appelé après la capture du plan précédent, donc
     *                           jamais avant que le filet soit posé
     * @param planningDe         extrait le planning à résoudre du problème,
     *                           quand celui-ci porte plus que ça
     * @param attacheSolveur     {@code null} quand l'appelant n'a rien à arrêter
     */
    public <P> Resolution<P> executer(String editionNom, Supplier<P> construireProbleme,
            Function<P, PlanningFestival> planningDe, Long secondsLimit,
            Consumer<Solver<PlanningFestival>> attacheSolveur) {
        // Le filet de l'issue #138 : le plan sur le point d'être écrasé est
        // capturé d'abord, donc un solve ne détruit plus le résultat précédent.
        snapshotService.capturerAvantSolve();
        P probleme = construireProbleme.get();
        Instant debutSolve = Instant.now();
        PlanningFestival resolu = planningService.resoudre(planningDe.apply(probleme), secondsLimit, attacheSolveur);
        long dureeSolveSecondes = Duration.between(debutSolve, Instant.now()).getSeconds();
        persistenceService.persist(resolu);
        PlanningService.PlanningDiagnostic diagnostic = planningService.diagnostiquer(resolu);
        analysisStore.record(diagnostic);
        // Historique KPI (issue #89) : une ligne par solve terminé, portant la
        // durée réelle. Délibérément après l'analyse — le KPI lit le score
        // qu'elle vient d'enregistrer — et jamais en mesure de faire échouer
        // la résolution.
        kpiHistoriqueService.enregistrerApresSolve(dureeSolveSecondes);
        annoncer(editionNom, diagnostic);
        return new Resolution<>(probleme, resolu, diagnostic);
    }

    /**
     * Annonce le résultat quand l'édition le demande — l'intérêt d'un long
     * solve lancé avant de partir. Émet un fait plutôt qu'un mail : « ne coûte
     * jamais son résultat à l'utilisateur » n'est plus un {@code try/catch}
     * écrit ici, c'est la politique de livraison qu'{@code ExpediteurNotifications}
     * applique à toute notification.
     */
    private void annoncer(String editionNom, PlanningService.PlanningDiagnostic diagnostic) {
        if (!referenceDataService.getParametresSolveur().mailFinResolution()) {
            return;
        }
        // Faisable au sens de Timefold : plus aucune contrainte dure violée.
        notifications.fire(new Notification.ResolutionTerminee(
                editionNom, diagnostic.score(), diagnostic.hardScore() >= 0));
    }
}
