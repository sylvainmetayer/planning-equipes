package dev.sylvain.planning.service.journal;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What has changed in the problem since a given moment: how many changes, of
 * what kind, and the most recent ones — the answer to « des données de
 * référence ont été modifiées depuis cette résolution, lesquelles ? ».
 *
 * <p>Only the actions declared through {@code CatalogueActions.changesData(…)}
 * count: a send, an export or a snapshot leaves a line in the history without
 * changing anything a solve would be given.</p>
 *
 * <p>The grouping is by referential family rather than by action, because that
 * is what reads in one glance — « 3 animateurs, 1 stand » — while the detail
 * lines below it carry the verb. Assembled here, off any database, so the rule
 * is tested without a container.</p>
 *
 * @param total     how many changes since, all families together
 * @param parEntite one entry per family touched, in the order of
 *                  {@link ActionJournalisee.Entite}, families untouched absent
 * @param dernieres the most recent lines, newest first, capped by the caller
 */
public record ReferenceDataChanges(int total, List<CompteEntite> parEntite, List<EntreeJournal> dernieres) {

    /** Nothing has moved since — what the screens get when the plan is up to date. */
    public static final ReferenceDataChanges NONE = new ReferenceDataChanges(0, List.of(), List.of());

    public ReferenceDataChanges {
        parEntite = List.copyOf(parEntite);
        dernieres = List.copyOf(dernieres);
    }

    /** How many times one family was touched. */
    public record CompteEntite(ActionJournalisee.Entite entite, int nombre) {}

    /**
     * Builds the summary from the counts the journal gives per action code.
     *
     * <p>A code the catalogue no longer knows is ignored rather than counted
     * under nothing: the table outlives the code names it stores, which is the
     * whole reason those are never renamed.</p>
     */
    public static ReferenceDataChanges of(Map<String, Integer> comptesParAction, List<EntreeJournal> dernieres) {
        Map<ActionJournalisee.Entite, Integer> parFamille = new EnumMap<>(ActionJournalisee.Entite.class);
        int total = 0;
        for (Map.Entry<String, Integer> compte : comptesParAction.entrySet()) {
            ActionJournalisee action = CatalogueActions.actions().get(compte.getKey());
            if (action == null || action.entite() == null) {
                continue;
            }
            parFamille.merge(action.entite(), compte.getValue(), Integer::sum);
            total += compte.getValue();
        }
        // An EnumMap already iterates in the order the families are declared.
        List<CompteEntite> familles = parFamille.entrySet().stream()
                .map(entree -> new CompteEntite(entree.getKey(), entree.getValue()))
                .toList();
        return new ReferenceDataChanges(total, familles, dernieres);
    }
}
