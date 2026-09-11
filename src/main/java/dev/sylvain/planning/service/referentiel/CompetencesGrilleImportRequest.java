package dev.sylvain.planning.service.referentiel;

/**
 * What the browser sends to preview an import of the competences matrix, and
 * — unchanged — to apply it. Same contract as the stand grid import: the write
 * re-reads the file and recomputes everything.
 *
 * @param fileName the name as picked, used only to recognise a spreadsheet and say so
 * @param content  the file's text, held in memory and never written to disk
 */
public record CompetencesGrilleImportRequest(String fileName, String content) {}
