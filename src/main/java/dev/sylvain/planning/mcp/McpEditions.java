package dev.sylvain.planning.mcp;

import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.edition.EditionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Turns the {@code edition} argument an MCP client passes into the edition id
 * the call must run in (issue #181).
 *
 * <p>An unknown value <b>fails</b> here, where the HTTP {@code X-Edition-Id}
 * header deliberately falls back to the default edition. The two callers are
 * not in the same situation: a browser tab left open on a since-deleted
 * edition must keep displaying screens rather than 400 on every one of them,
 * whereas an assistant that names an edition is about to read — or write —
 * something, and a silent fallback would send that write to the wrong
 * edition without anyone being able to notice.</p>
 *
 * <p>A name is accepted as well as an id, because that is what a human says
 * out loud ("dans l'édition 2025") and what {@code lister_editions} shows
 * next to the id. An ambiguous name is refused rather than arbitrated.</p>
 */
@ApplicationScoped
class McpEditions {

    @Inject
    EditionService editionService;

    /**
     * @return the edition id to run in, or {@code null} when the call names no
     *         edition — in which case nothing is overridden and the tool keeps
     *         working in the default edition, as it did before #181
     */
    String solve(String edition) {
        if (edition == null || edition.isBlank()) {
            return null;
        }
        String demande = edition.trim();
        List<Edition> editions = editionService.listEditions();

        List<Edition> parId = editions.stream()
                .filter(candidate -> candidate.getId().equalsIgnoreCase(demande))
                .toList();
        if (!parId.isEmpty()) {
            return parId.get(0).getId();
        }

        List<Edition> parNom = editions.stream()
                .filter(candidate ->
                        candidate.getNom() != null && candidate.getNom().trim().equalsIgnoreCase(demande))
                .toList();
        if (parNom.size() == 1) {
            return parNom.get(0).getId();
        }
        if (parNom.size() > 1) {
            throw new BusinessError.Invalid("Plusieurs éditions portent le nom « " + demande
                    + " » : désigner celle voulue par son id (" + ids(parNom) + ").");
        }
        throw new BusinessError.Invalid("Édition inconnue « " + demande + " ». Éditions disponibles : "
                + descriptions(editions) + ". Voir lister_editions.");
    }

    private static String ids(List<Edition> editions) {
        return editions.stream().map(Edition::getId).collect(Collectors.joining(", "));
    }

    private static String descriptions(List<Edition> editions) {
        if (editions.isEmpty()) {
            return "aucune";
        }
        return editions.stream()
                .map(edition -> edition.getId() + " (" + edition.getNom() + ")")
                .collect(Collectors.joining(", "));
    }
}
