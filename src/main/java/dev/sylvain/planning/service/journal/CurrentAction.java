package dev.sylvain.planning.service.journal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.RequestScoped;

/**
 * What the request learned about the action being carried out, handed to
 * whoever writes the line at the end of the call.
 *
 * <p>The entry points — {@code JournalActionFilter} for REST,
 * {@code JournalOutilInterceptor} for MCP — know <em>which route</em> ran and
 * how it ended. Two things they cannot know sit here.</p>
 *
 * <p><b>What an edit changed.</b> Only the service holds that: the referential
 * façade reads the fiche as it stood before the write (it already does, for
 * the write-time warnings) and can therefore say that this edit touched
 * {@code nom} and {@code email} and nothing else.</p>
 *
 * <p><b>Which action a route performed</b>, when the route alone does not say.
 * One method toggles a constraint both ways, so the catalogue — which keys on
 * the method — would have it record « Contrainte activée » for a
 * deactivation. The resource states the direction here instead, and the
 * journal stops asserting the opposite of what happened.</p>
 *
 * <p>Enrichment, never a requirement: a line nobody contributed to is written
 * all the same, with the catalogue's own action and an empty field list.</p>
 */
@RequestScoped
public class CurrentAction {

    private String action;
    private final Set<String> champs = new LinkedHashSet<>();

    /**
     * States which action of {@link CatalogueActions} this call really
     * performed, for a route whose method serves several.
     *
     * @param code a code of the catalogue; an unknown one is ignored rather
     *             than written, and {@code JournalCoverageStructurelleTest}
     *             checks that every code stated in the sources exists
     */
    public void action(String code) {
        this.action = code;
    }

    /** Adds the fields an edit really changed, in the order they are declared. */
    public void champsModifies(List<String> noms) {
        if (noms != null) {
            champs.addAll(noms);
        }
    }

    /** The action the request declared, {@code null} when the route's own is right. */
    public String action() {
        return action;
    }

    public List<String> champs() {
        return new ArrayList<>(champs);
    }
}
