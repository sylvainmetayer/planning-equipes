package dev.sylvain.planning.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * When reference data (stands, animateurs, créneaux, constraint toggles, ...)
 * was last mutated, per {@code edition} — the one thing that says whether a
 * plan computed earlier still describes today's referential.
 *
 * <p>It feeds two readers that do not tolerate the same thing. The shell's
 * staleness banner only hints that the planning on screen may be old; the
 * Instantanés screen decides whether restoring a capture is safe (issue #170),
 * and there a wrong "à jour" is worse than no badge at all. That is why the
 * marker is <b>persisted</b> ({@code edition.reference_modifie_le}, V79) rather
 * than held in memory as it was: a restart used to empty the map, and every
 * snapshot then passed for fresh.</p>
 *
 * <p>Tracked per {@code edition}: editing 2026 must not make 2025's persisted
 * planning look stale. {@code edition} is a global table (it carries the
 * editions themselves), so its statements are keyed on {@code id} and not on
 * {@code edition_id} — see {@code IsolationEditionStructurelleTest}.</p>
 */
@ApplicationScoped
public class ReferenceDataChangeTracker {

    @Inject
    EditionContext editionContext;

    @Inject
    JdbcEditionScope scope;

    /**
     * Records that the current edition's referential just changed.
     *
     * <p>Called by the referential services <b>after</b> their write has
     * committed, so a refused write never marks anything. A failure here is not
     * swallowed: the marker is what a restore is judged against, and a silent
     * loss would leave a stale snapshot looking fresh — the very trap this
     * exists to close.</p>
     */
    public void markModified() {
        String editionId = editionId();
        scope.write("Failed to mark the referential of edition " + editionId + " as modified", connection -> {
            try (PreparedStatement ps =
                    connection.prepareStatement("UPDATE edition SET reference_modifie_le = ? WHERE id = ?")) {
                ps.setObject(1, OffsetDateTime.now(ZoneOffset.UTC));
                ps.setString(2, editionId);
                ps.executeUpdate();
            }
        });
    }

    /** Last mutation of the current edition's referential, {@code null} when none is known. */
    public Instant lastModifiedAt() {
        return lastModifiedAt(editionId());
    }

    /**
     * Same for any edition — the A/B comparator lists snapshots across editions
     * (issue #70), so freshness cannot be answered from the current one alone.
     */
    public Instant lastModifiedAt(String editionId) {
        return scope.read("Failed to read the last referential change of edition " + editionId, connection -> {
            try (PreparedStatement ps =
                    connection.prepareStatement("SELECT reference_modifie_le FROM edition WHERE id = ?")) {
                ps.setString(1, editionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    OffsetDateTime moment = rs.getObject("reference_modifie_le", OffsetDateTime.class);
                    return moment == null ? null : moment.toInstant();
                }
            }
        });
    }

    private String editionId() {
        return editionContext.editionIdCourant();
    }
}
