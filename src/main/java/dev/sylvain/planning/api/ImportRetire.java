package dev.sylvain.planning.api;

import dev.sylvain.planning.service.BusinessError;

/**
 * The answer of every import route withdrawn in 4.0 — the CSV of each
 * referential but the animateurs', and the stand matrix. The routes stay so a
 * script written against 3.x is told where to go instead of meeting a bare
 * {@code 404}; they read nothing and write nothing.
 */
final class ImportRetire {

    private ImportRetire() {}

    /** {@code 410}, with the way out: the YAML file is the workbook of a whole edition. */
    static BusinessError.Gone gone(String ce) {
        return new BusinessError.Gone(
                "L'import " + ce + " est retiré depuis la 4.0 : le fichier YAML est désormais le "
                        + "classeur complet d'une édition. Exportez l'édition en YAML (Fichiers › Exporter), modifiez-la, "
                        + "puis importez-la (Fichiers › Importer › Scénario, ou POST /api/reference-data/import-scenario-fichier).");
    }
}
