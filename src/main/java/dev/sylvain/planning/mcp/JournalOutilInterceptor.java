package dev.sylvain.planning.mcp;

import dev.sylvain.planning.service.journal.Acteur;
import dev.sylvain.planning.service.journal.ActionJournalisee;
import dev.sylvain.planning.service.journal.CatalogueActions;
import dev.sylvain.planning.service.journal.CurrentAction;
import dev.sylvain.planning.service.journal.JournalActionService;
import io.quarkiverse.mcp.server.Tool;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Writes one line of the history for every MCP tool that changes something
 * (issue #406).
 *
 * <p>An MCP call never reaches the JAX-RS chain — {@code quarkus-mcp-server-http}
 * registers its routes ahead of it, which is why
 * {@code JournalActionFilter} cannot cover them and this interceptor exists.
 * An assistant creating a stand is an action like any other; a history that
 * showed only the screens would let a stand appear out of nowhere.</p>
 *
 * <p>Bound to {@link Journalise}, and not to {@code @EditionCiblee} as the
 * first implementation was: {@code EditionMcpTools} and
 * {@code SauvegardeMcpTools} act across editions, carry no
 * {@code @EditionCiblee} for that very reason, and six of their write tools —
 * deleting an entire edition among them — were therefore intercepted by
 * nobody while the catalogue claimed to cover them.</p>
 *
 * <p>Runs <b>inside</b> {@code EditionCibleeInterceptor} (a later priority),
 * so the line is written in the edition the tool argument named rather than in
 * the default one. A tool that names no edition writes into the current one,
 * which is what the REST side does too: « depuis ici, quelqu'un a supprimé
 * l'édition X » is the honest place for that line — inside X it would vanish
 * with the cascade.</p>
 */
@Journalise
@Interceptor
@Priority(Interceptor.Priority.APPLICATION + 10)
public class JournalOutilInterceptor {

    private final JournalActionService journal;

    private final CurrentAction currentAction;

    @Inject
    JournalOutilInterceptor(JournalActionService journal, CurrentAction currentAction) {
        this.journal = journal;
        this.currentAction = currentAction;
    }

    @AroundInvoke
    Object journaliser(InvocationContext context) throws Exception {
        Optional<ActionJournalisee> action = context.getMethod().isAnnotationPresent(Tool.class)
                ? CatalogueActions.forTool(FeatureNames.of(context.getMethod()))
                : Optional.empty();
        if (action.isEmpty()) {
            return context.proceed();
        }
        String entiteId = entiteId(context);
        try {
            Object resultat = context.proceed();
            // The fields the edit really changed, as the referential façade
            // noted them on the way — the same enrichment the REST filter
            // reads, so a « champs modifiés » column no longer depends on
            // which door the write came through.
            journal.record(action.get(), Acteur.ASSISTANT, PRINCIPAL, entiteId, currentAction.champs(), 200);
            return resultat;
        } catch (Exception e) {
            // A refused tool call is a fact worth keeping: an assistant that
            // tried to delete an edition and was told no belongs in the
            // history quite as much as one that succeeded.
            journal.record(action.get(), Acteur.ASSISTANT, PRINCIPAL, entiteId, List.of(), 400);
            throw e;
        }
    }

    /**
     * What the tool acts upon, read off the argument that names it — so a
     * stand created by an assistant writes the same line as one created from
     * a screen, object column included, which was the point of having one
     * catalogue for both.
     *
     * <p>An {@code @EditionArg} is skipped even when it is named
     * {@code edition}: there it says where the line lands, not what the tool
     * acts on. On the cross-edition tools the same word is the subject —
     * {@code supprimer_edition(edition)} — and those carry no
     * {@code @EditionArg}, which is exactly what tells the two apart.</p>
     */
    private static String entiteId(InvocationContext context) {
        Parameter[] parametres = context.getMethod().getParameters();
        Object[] valeurs = context.getParameters();
        for (int i = 0; i < parametres.length && i < valeurs.length; i++) {
            Object valeur = valeurs[i];
            boolean nommable = valeur instanceof String || valeur instanceof Number;
            if (nommable
                    && !parametres[i].isAnnotationPresent(EditionArg.class)
                    && NOMS_ENTITE.contains(parametres[i].getName())) {
                return String.valueOf(valeur);
            }
        }
        return null;
    }

    /**
     * Argument names that designate what a tool acts upon — identifiers only,
     * never a display name, which would put a label where the screen expects
     * something it can look up. Compiled parameter names are available because
     * the build keeps them ({@code -parameters}, which the MCP extension itself
     * relies on to name its tool arguments).
     */
    private static final Set<String> NOMS_ENTITE =
            Set.of("id", "animateurId", "standId", "creneauId", "posteId", "edition", "contrainte", "name");

    /** The one identity an MCP call has: a shared key, no per-client name. */
    private static final String PRINCIPAL = "mcp";
}
