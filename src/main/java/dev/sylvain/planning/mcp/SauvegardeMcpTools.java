package dev.sylvain.planning.mcp;

import dev.sylvain.planning.service.backup.BackupService;
import dev.sylvain.planning.service.backup.BackupState;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * MCP tools over the nightly backup ({@code BackupResource}): whether it is
 * configured at all, when it last ran, whether it worked, and the one switch
 * that suspends it.
 *
 * <p>Exposed because a backup nobody looks at is a backup nobody has: an
 * assistant about to run {@code importer_scenario} or
 * {@code reinitialiser_donnees} can now check what the last dump was worth
 * before overwriting an edition.</p>
 *
 * <p><b>Reading the state is all there is</b>, deliberately. The dumps are
 * never served — they hold every animateur's name, birth date and address —
 * and restoring one is a {@code pg_restore} on the cluster, not an endpoint;
 * the destination and the retention come from the environment. What the
 * application writes is the boolean that suspends the run, and nothing
 * else.</p>
 *
 * <p>Not edition-scoped: the dump covers the whole cluster, every edition at
 * once. An {@code edition} argument would suggest a per-edition backup that
 * does not exist.</p>
 */
@RefusMetier
@Journalise
@ApplicationScoped
public class SauvegardeMcpTools {

    @Inject
    BackupService backupService;

    @Tool(
            description = "État de la sauvegarde nocturne, toutes éditions confondues : si elle est configurée, "
                    + "où elle écrit, sa périodicité, sa rétention, sa prochaine exécution, comment s'est passée la "
                    + "dernière et les fichiers présents. Les dumps eux-mêmes ne sont jamais servis : ils portent "
                    + "toutes les données personnelles.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    BackupState etat_sauvegardes() {
        return backupService.state();
    }

    @Tool(
            description = "Suspend ou relance la sauvegarde nocturne. C'est le seul réglage modifiable : la "
                    + "destination et la rétention sont des variables d'environnement, et la restauration est une "
                    + "opération d'infrastructure hors application.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    BackupState modifier_sauvegardes(@ToolArg(description = "Sauvegarde nocturne active ou suspendue") boolean active) {
        return backupService.setActive(active);
    }
}
