package dev.sylvain.planning.service.journal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.RequestScoped;

/**
 * What the services learned about the action being carried out, handed to
 * whoever writes the line at the end of the call.
 *
 * <p>The entry points — {@code JournalActionFilter} for REST,
 * {@code JournalOutilInterceptor} for MCP — know <em>which</em> action ran and
 * how it ended, but not what it changed. Only the service holds that: the
 * referential façade reads the fiche as it stood before the write (it already
 * does, for the write-time warnings) and can therefore say that this edit
 * touched {@code nom} and {@code email} and nothing else. It deposits that
 * here, and the entry point picks it up.</p>
 *
 * <p>Enrichment, never a requirement: a line whose fields nobody contributed
 * is written all the same, with an empty list. That matters because the
 * request context is not guaranteed on every path — an MCP call activates its
 * own, and a scheduled job has none at all — and an action must be recorded
 * even where its detail cannot be.</p>
 */
@RequestScoped
public class CurrentAction {

    private String entiteId;
    private final Set<String> champs = new LinkedHashSet<>();

    /**
     * Names what the action bears upon, when the service knows it better than
     * the URL does — a créneau created without an id in the path, typically.
     */
    public void surEntite(String entiteId) {
        this.entiteId = entiteId;
    }

    /** Adds the fields an edit really changed, in the order they are declared. */
    public void champsModifies(List<String> noms) {
        if (noms != null) {
            champs.addAll(noms);
        }
    }

    public String entiteId() {
        return entiteId;
    }

    public List<String> champs() {
        return new ArrayList<>(champs);
    }
}
