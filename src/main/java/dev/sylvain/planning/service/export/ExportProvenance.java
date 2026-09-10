package dev.sylvain.planning.service.export;

import java.time.Instant;

/**
 * Where an exported document's data comes from: which édition, and when the
 * plan it carries last moved.
 *
 * <p>The footer used to carry the generation date alone, which only says when
 * someone pressed the button. Two PDFs of the same animateur, downloaded a week
 * apart, could not be told apart — nor could a printed copy be checked against
 * the planning currently on screen.</p>
 *
 * <p>Since issue #245 two plans coexist, and a document must date <b>the one it
 * carries</b>. An individual PDF renders the published plan: dating it by the
 * last solve would announce a version its reader does not hold — an incremental
 * solve run after the publication would move the date without moving a single
 * line of the document. The administration's own exports still render the
 * working plan, and still date it by its solve.</p>
 *
 * <p>An interface rather than a class so a test can hand over a fixed
 * provenance without a datasource: reading it goes through the édition context,
 * the {@code planning_resolution} table and the published snapshot.</p>
 */
public interface ExportProvenance {

    /** Which plan a document carries, and therefore what its date means. */
    enum Nature {

        /** The working plan: dated by its last solve. */
        RESOLUTION,

        /** The published plan — what the animateurs were sent: dated by its publication. */
        PUBLICATION
    }

    /** The working plan of the édition the current request works in. */
    Provenance courante();

    /**
     * The published plan of that same édition. Its date is {@code null} while
     * nothing has ever been published — which is also when that plan is empty.
     */
    Provenance publiee();

    /**
     * @param editionNom the édition's display name, {@code null} when it cannot
     *                   be resolved — a document is still worth producing
     * @param date       when the plan this document carries last moved,
     *                   {@code null} when it never did
     * @param nature     which plan that is, and therefore how the date is said
     */
    record Provenance(String editionNom, Instant date, Nature nature) {
    }
}
