package dev.sylvain.planning.service.referentiel;

/**
 * A referential CSV as the screen submits it: the file itself, never a preview
 * the browser computed. The write re-reads it and re-runs every check, so a
 * replayed call cannot get a row past a validation.
 *
 * @param fileName what the operator picked, echoed in the messages
 * @param content  the file, decoded as text
 */
public record ReferentielCsvImportRequest(String fileName, String content) {}
