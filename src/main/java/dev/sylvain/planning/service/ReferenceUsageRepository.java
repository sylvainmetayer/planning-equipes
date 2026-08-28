package dev.sylvain.planning.service;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Counts what references a referential entity, across the three tables that
 * outlive it: the seats of the persisted plan, the ad hoc constraints and the
 * locks.
 *
 * <p>Deliberately not split per family. A "how much would this delete take
 * with it" question spans tables no single family repository owns, and the
 * three statements of a stand differ from the three of an animateur only by
 * the column they filter on — spreading those nine near-identical counts over
 * three repositories would hide that symmetry instead of showing it.</p>
 *
 * <p>Every statement takes the <b>whole selection at once</b> ({@code = ANY(?)}
 * over a bound array): a bulk delete of fifty rows must cost one round trip per
 * counter, not fifty. The ids travel bound, never concatenated.</p>
 */
@ApplicationScoped
public class ReferenceUsageRepository {

    @Inject
    JdbcEditionScope scope;

    /**
     * Counters for a set of stands.
     *
     * <p>Only <em>filled</em> seats are counted here and for timeslots: an
     * empty seat references nothing a user recognises, and reporting the whole
     * grid would drown the number that matters — how many people currently
     * have this stand on their planning.</p>
     */
    public ReferenceUsage forStands(Collection<String> ids) {
        return count(ids, "varchar",
                """
                SELECT COUNT(*) FROM poste_affectation
                WHERE edition_id = ? AND animateur_id IS NOT NULL AND stand_id = ANY(?)""",
                """
                SELECT COUNT(*) FROM contrainte_ad_hoc
                WHERE edition_id = ? AND stand_id = ANY(?)""",
                """
                SELECT COUNT(*) FROM verrouillage_planning
                WHERE edition_id = ? AND stand_id = ANY(?)""");
    }

    /**
     * Counters for a set of animateurs. An ad hoc constraint names its
     * animateurs through {@code contrainte_animateur}, so the count is over
     * distinct constraints: one exception naming two of the selected people is
     * one exception, not two.
     */
    public ReferenceUsage forAnimateurs(Collection<String> ids) {
        return count(ids, "varchar",
                """
                SELECT COUNT(*) FROM poste_affectation
                WHERE edition_id = ? AND animateur_id = ANY(?)""",
                """
                SELECT COUNT(DISTINCT contrainte_id) FROM contrainte_animateur
                WHERE edition_id = ? AND animateur_id = ANY(?)""",
                """
                SELECT COUNT(*) FROM verrouillage_planning
                WHERE edition_id = ? AND animateur_id = ANY(?)""");
    }

    /** Counters for a set of timeslots; their ids are database-generated, hence the {@code bigint} array. */
    public ReferenceUsage forCreneaux(Collection<Long> ids) {
        return count(ids, "bigint",
                """
                SELECT COUNT(*) FROM poste_affectation
                WHERE edition_id = ? AND animateur_id IS NOT NULL AND creneau_id = ANY(?)""",
                """
                SELECT COUNT(*) FROM contrainte_ad_hoc
                WHERE edition_id = ? AND creneau_id = ANY(?)""",
                """
                SELECT COUNT(*) FROM verrouillage_planning
                WHERE edition_id = ? AND creneau_id = ANY(?)""");
    }

    /** The three counters on one borrowed connection: three round trips, whatever the size of the selection. */
    private ReferenceUsage count(Collection<?> ids, String sqlType, String seats, String adHoc, String locks) {
        if (ids.isEmpty()) {
            return ReferenceUsage.AUCUN;
        }
        Object[] values = ids.toArray();
        return scope.read("Failed to count what references the selection", connection -> {
            Array bound = connection.createArrayOf(sqlType, values);
            try {
                return new ReferenceUsage(
                        countOne(connection, seats, bound),
                        countOne(connection, adHoc, bound),
                        countOne(connection, locks, bound));
            } finally {
                bound.free();
            }
        });
    }

    private int countOne(Connection connection, String sql, Array ids) throws SQLException {
        try (PreparedStatement ps = scope.prepareScoped(connection, sql)) {
            ps.setArray(2, ids);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }
}
