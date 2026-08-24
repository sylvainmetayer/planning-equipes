package dev.sylvain.planning.service;

import java.time.Instant;

/**
 * Where an exported document's data comes from: which édition, and when that
 * édition was last solved.
 *
 * <p>The footer used to carry the generation date alone, which only says when
 * someone pressed the button. Two PDFs of the same animateur, downloaded a week
 * apart, could not be told apart — nor could a printed copy be checked against
 * the planning currently on screen. The solve date is what actually changes
 * when the schedule changes.</p>
 *
 * <p>An interface rather than a class so a test can hand over a fixed
 * provenance without a datasource: reading it goes through the édition context
 * and the {@code planning_resolution} table.</p>
 */
public interface ExportProvenance {

    /** The provenance of the édition the current request works in. */
    Provenance courante();

    /**
     * @param editionNom the édition's display name, {@code null} when it cannot
     *                   be resolved — a document is still worth producing
     * @param resoluLe   when that édition was last solved and persisted,
     *                   {@code null} when it never was
     */
    record Provenance(String editionNom, Instant resoluLe) {
    }
}
