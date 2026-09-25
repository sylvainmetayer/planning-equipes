package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.referentiel.TypologieItem;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The plan read <b>by typologie of jeu</b>: who actually holds each game, and
 * what it weighs.
 *
 * <p>Nothing carried that reading (issue #590). The Typologies screen is a pure
 * referential — id, label, ninja — and its detail view lists the animateurs
 * <i>vetted</i> on a typologie, never the ones the solver put there. The
 * assignment views are organised by stand, by day or by person. Yet the
 * typologie is the axis the organiser reasons about its games on, and the one a
 * quality rule caps ({@code limiterTypologiesDistinctesParAnimateur}) and a
 * hard one quotas ({@code plafondCreneauxParTypologie}): a ceiling nobody can
 * see is a ceiling nobody can set.</p>
 *
 * <p><b>The edition as a whole first</b>: « who holds the ambiance games this
 * year, and how much does that weigh ». Hours are also broken down by day, for
 * the heatmap of the screen — « quand mes jeux de stratégie tournent-ils » is
 * a question the totals cannot answer — but never slot by slot: that grain
 * belongs to the calendar and the rail.</p>
 *
 * <p>The gap between the two lists is the point: an animateur <b>competent</b>
 * on a typologie is one whose fiche carries it, an animateur <b>affecté</b> is
 * one the plan sat at that game. Somebody competent and never used, or used
 * without being competent — which a stand proposing several typologies makes
 * perfectly ordinary — is exactly what an organiser opens this view for.</p>
 */
@ApplicationScoped
public class TypologieAnalyzer {

    private final PlanningPersistenceService persistenceService;

    private final ReferenceDataService referenceDataService;

    @Inject
    public TypologieAnalyzer(PlanningPersistenceService persistenceService, ReferenceDataService referenceDataService) {
        this.persistenceService = persistenceService;
        this.referenceDataService = referenceDataService;
    }

    /** The reading over the persisted plan and the current referential. */
    public RapportTypologies rapport() {
        return compute(
                persistenceService.loadPersistedPlanning(),
                referenceDataService.listTypologies(),
                referenceDataService.listAnimateurs());
    }

    /**
     * The whole reading from a plan and a referential alone — no database.
     *
     * <p>Every typologie of the referential gets a line, held or not: a
     * typologie nobody was given is the first thing this view has to be able to
     * say. A typologie a stand proposes without the referential declaring it —
     * which an import can produce — gets one too, so nothing the plan contains
     * is invisible here.</p>
     */
    public static RapportTypologies compute(
            PlanningEvenement planning, List<TypologieItem> typologies, List<Animateur> animateurs) {
        List<PosteAffectation> postes = planning == null || planning.getPostes() == null
                ? List.of()
                : planning.getPostes().stream()
                        .filter(poste -> poste.getAnimateur() != null && poste.getStand() != null)
                        .toList();

        Map<String, Set<String>> affectesParTypologie = new LinkedHashMap<>();
        Map<String, String> nomParAnimateur = new LinkedHashMap<>();
        Map<String, Double> heuresParTypologie = new LinkedHashMap<>();
        Map<String, Integer> postesParTypologie = new LinkedHashMap<>();
        Map<String, Map<String, Double>> heuresParTypologieEtJour = new LinkedHashMap<>();
        TreeSet<String> jours = new TreeSet<>();
        for (PosteAffectation poste : postes) {
            Animateur animateur = poste.getAnimateur();
            nomParAnimateur.putIfAbsent(animateur.getId(), animateur.nomAffiche());
            double heures = poste.getDureeEffectiveMinutes() / 60.0;
            LocalDate date =
                    poste.getCreneau() == null ? null : poste.getCreneau().getDate();
            String jour = date == null ? null : date.toString();
            if (jour != null) {
                jours.add(jour);
            }
            // One poste counts for every typologie its stand proposes — the same
            // reading as the quota rule (ADR 0042): somebody holds the game they
            // are sat at, whether or not their fiche mentions it.
            for (String typologie : standTypologies(poste.getStand())) {
                affectesParTypologie
                        .computeIfAbsent(typologie, id -> new LinkedHashSet<>())
                        .add(animateur.getId());
                heuresParTypologie.merge(typologie, heures, Double::sum);
                postesParTypologie.merge(typologie, 1, Integer::sum);
                if (jour != null) {
                    heuresParTypologieEtJour
                            .computeIfAbsent(typologie, id -> new LinkedHashMap<>())
                            .merge(jour, heures, Double::sum);
                }
            }
        }

        Map<String, Set<String>> competentsParTypologie = new LinkedHashMap<>();
        for (Animateur animateur : animateurs == null ? List.<Animateur>of() : animateurs) {
            if (animateur.getCompetences() == null) {
                continue;
            }
            nomParAnimateur.putIfAbsent(animateur.getId(), animateur.nomAffiche());
            for (String typologie : animateur.getCompetences().keySet()) {
                competentsParTypologie
                        .computeIfAbsent(typologie, id -> new LinkedHashSet<>())
                        .add(animateur.getId());
            }
        }

        Map<String, TypologieItem> referentiel = new LinkedHashMap<>();
        for (TypologieItem typologie : typologies == null ? List.<TypologieItem>of() : typologies) {
            referentiel.put(typologie.id(), typologie);
        }
        Set<String> tousLesIds = new TreeSet<>(referentiel.keySet());
        tousLesIds.addAll(affectesParTypologie.keySet());
        tousLesIds.addAll(competentsParTypologie.keySet());

        List<LigneTypologie> lignes = new ArrayList<>();
        for (String id : tousLesIds) {
            TypologieItem item = referentiel.get(id);
            Set<String> affectes = affectesParTypologie.getOrDefault(id, Set.of());
            Set<String> competents = competentsParTypologie.getOrDefault(id, Set.of());
            lignes.add(new LigneTypologie(
                    id,
                    item == null ? id : item.label(),
                    item != null && item.ninja(),
                    item == null ? null : item.maxCreneauxParAnimateur(),
                    item == null ? null : item.description(),
                    noms(affectes, nomParAnimateur),
                    noms(competents, nomParAnimateur),
                    noms(without(competents, affectes), nomParAnimateur),
                    noms(without(affectes, competents), nomParAnimateur),
                    heuresParTypologie.getOrDefault(id, 0.0),
                    postesParTypologie.getOrDefault(id, 0),
                    new LinkedHashMap<>(heuresParTypologieEtJour.getOrDefault(id, Map.of()))));
        }
        return new RapportTypologies(lignes, List.copyOf(jours));
    }

    private static Set<String> standTypologies(Stand stand) {
        return stand.getTypologiesProposees() == null ? Set.of() : stand.getTypologiesProposees();
    }

    private static Set<String> without(Set<String> ids, Set<String> autres) {
        Set<String> restant = new LinkedHashSet<>(ids);
        restant.removeAll(autres);
        return restant;
    }

    /**
     * Ids turned into names <b>with</b> their id, sorted by name: a nominative
     * list is read, not scanned, and the screen links each name to that
     * person's timeline — which it cannot do from a name alone.
     */
    private static List<AnimateurTypologie> noms(Set<String> ids, Map<String, String> nomParAnimateur) {
        return ids.stream()
                .map(id -> new AnimateurTypologie(id, nomParAnimateur.getOrDefault(id, id)))
                .sorted(Comparator.comparing(AnimateurTypologie::nom, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** One animateur of a typologie's lists: the name to read, the id to link on. */
    public record AnimateurTypologie(String animateurId, String nom) {}

    /**
     * @param animateursAffectes       distinct animateurs the plan sat at this
     *                                 game, by display name
     * @param animateursCompetents     those the referential vets on it
     * @param competentsJamaisAffectes vetted, never used — a reserve nobody
     *                                 drew on
     * @param affectesSansCompetence   used without being vetted, which a stand
     *                                 proposing several typologies makes
     *                                 ordinary rather than suspicious
     * @param description              the organiser's own note on the typologie,
     *                                 {@code null} when none was written
     * @param heures                   hours held on it over the whole edition
     * @param postes                   seats held on it over the whole edition
     * @param heuresParJour            the same hours, ISO day by ISO day; a day
     *                                 the typologie was not held has no entry
     */
    @Schema(requiredProperties = {"ninja", "heures", "postes"})
    public record LigneTypologie(
            String typologie,
            String label,
            boolean ninja,
            Integer maxCreneauxParAnimateur,
            String description,
            List<AnimateurTypologie> animateursAffectes,
            List<AnimateurTypologie> animateursCompetents,
            List<AnimateurTypologie> competentsJamaisAffectes,
            List<AnimateurTypologie> affectesSansCompetence,
            double heures,
            int postes,
            Map<String, Double> heuresParJour) {}

    /** @param jours every day the plan holds a seat on, sorted — the heatmap's columns */
    public record RapportTypologies(List<LigneTypologie> typologies, List<String> jours) {}
}
