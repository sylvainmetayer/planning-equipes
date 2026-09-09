package dev.sylvain.planning.service;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What the browser sends to preview an import, and — unchanged — to apply it.
 *
 * <p>The two calls take the <b>same</b> body, the file included, and that is
 * the point: the write re-reads the file and recomputes everything, so nothing
 * it does depends on a preview the client could have edited, replayed or never
 * seen. A request carrying a list of "rows to write" would have made a refresh
 * of the page, or a crafted call, enough to write rows no validation ever
 * looked at.</p>
 *
 * @param fileName                   the name as picked, used only to recognise
 *                                   a spreadsheet and say so
 * @param content                    the file's text, held in memory and never
 *                                   written to disk (see {@code docs/rgpd.md})
 * @param mapping                    column-to-field mapping; {@code null} asks
 *                                   for the proposal
 * @param replaceAnimateurs          delete the animateurs the file does not
 *                                   name — off by default
 * @param replaceJoursIndisponibles  overwrite an existing fiche's off days
 *                                   instead of adding to them — off by default
 */
@Schema(requiredProperties = {"replaceAnimateurs", "replaceJoursIndisponibles"})
public record AnimateurCsvImportRequest(
        String fileName,
        String content,
        AnimateurCsvMapping mapping,
        boolean replaceAnimateurs,
        boolean replaceJoursIndisponibles) {
}
