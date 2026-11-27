package dev.sylvain.planning.api;

import dev.sylvain.planning.service.BusinessError;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Every import route withdrawn in 4.0 — the CSV of each referential but the
 * animateurs', and the stand matrix — answers {@code 410} and points at the
 * YAML import, whatever it is sent. One place, before routing: nothing is read
 * or written, and no resource keeps a stub. Kept so a script written against
 * 3.x learns where to go; removed in 5.0.
 */
@Deprecated
@Provider
@PreMatching
public class ImportsRetiresFilter implements ContainerRequestFilter {

    private static final Pattern CSV = Pattern.compile(
            "/?(typologies|emplacements|stands|creneaux|journees-types)/import-csv(/analyse|/exemple)?");

    private static final Pattern GRILLE = Pattern.compile("/?stands/import-grille(/analyse|/exemple)?");

    @Override
    public void filter(ContainerRequestContext requete) throws IOException {
        String chemin = requete.getUriInfo().getPath();
        String ce = null;
        var csv = CSV.matcher(chemin);
        if (csv.matches()) {
            ce = "CSV "
                    + switch (csv.group(1)) {
                        case "typologies" -> "des typologies";
                        case "emplacements" -> "des emplacements";
                        case "stands" -> "des stands";
                        case "creneaux" -> "des créneaux";
                        default -> "des journées types";
                    };
        } else if (GRILLE.matcher(chemin).matches()) {
            ce = "de la grille des stands";
        }
        if (ce != null) {
            throw new BusinessError.Gone("L'import " + ce + " est retiré depuis la 4.0 : le fichier YAML est désormais "
                    + "le classeur complet d'une édition. Exportez l'édition en YAML (Fichiers › Exporter), "
                    + "modifiez-la, puis importez-la (Fichiers › Importer › Scénario, ou "
                    + "POST /api/reference-data/import-scenario-fichier).");
        }
    }
}
