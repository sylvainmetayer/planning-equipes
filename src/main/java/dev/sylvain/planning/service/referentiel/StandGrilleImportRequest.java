package dev.sylvain.planning.service.referentiel;

/**
 * What the browser sends to preview an import of the stand matrix, and —
 * unchanged — to apply it. Same contract as the animateur CSV import: the
 * write re-reads the file and recomputes everything.
 *
 * @param fileName the name as picked, used only to recognise a spreadsheet and say so
 * @param content  the file's text, held in memory and never written to disk
 */
public record StandGrilleImportRequest(String fileName, String content) {
}
