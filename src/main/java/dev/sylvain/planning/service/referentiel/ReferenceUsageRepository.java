package dev.sylvain.planning.service.referentiel;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import dev.sylvain.planning.service.JdbcEditionScope;

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
 *
 * <p><b>Each statement is written at its own {@code prepareScoped} call</b>,
 * and the nine of them are spelled out rather than passed to one shared helper.
 * That repetition is the price of being <em>seen</em>:
 * {@code IsolationEditionStructurelleTest} reads the SQL of the backend by
 * collecting the literals between the parentheses of a {@code prepareScoped(}
 * call, so a statement handed over as a variable is invisible to it — and a
 * future edit dropping an {@code edition_id} predicate would then aggregate
 * across every edition with the guard built for exactly that saying
 * nothing.</p>
 */
@ApplicationScoped
public class ReferenceUsageRepository {

    private static final String FAILURE = "Failed to count what references the selection";

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
        if (ids.isEmpty()) {
            return ReferenceUsage.AUCUN;
        }
        Object[] values = ids.toArray();
        return scope.read(FAILURE, connection -> {
            Array bound = connection.createArrayOf("varchar", values);
            try (PreparedStatement seats = scope.prepareScoped(connection,
                    """
                    SELECT COUNT(*) FROM poste_affectation
                    WHERE edition_id = ? AND animateur_id IS NOT NULL AND stand_id = ANY(?)""");
                    PreparedStatement adHoc = scope.prepareScoped(connection,
                            """
                            SELECT COUNT(*) FROM contrainte_ad_hoc
                            WHERE edition_id = ? AND stand_id = ANY(?)""");
                    PreparedStatement locks = scope.prepareScoped(connection,
                            """
                            SELECT COUNT(*) FROM verrouillage_planning
                            WHERE edition_id = ? AND stand_id = ANY(?)""")) {
                return new ReferenceUsage(count(seats, bound), count(adHoc, bound), count(locks, bound));
            } finally {
                bound.free();
            }
        });
    }

    /**
     * Counters for a set of animateurs. An ad hoc constraint names its
     * animateurs through {@code contrainte_animateur}, so the count is over
     * distinct constraints: one exception naming two of the selected people is
     * one exception, not two.
     */
    public ReferenceUsage forAnimateurs(Collection<String> ids) {
        if (ids.isEmpty()) {
            return ReferenceUsage.AUCUN;
        }
        Object[] values = ids.toArray();
        return scope.read(FAILURE, connection -> {
            Array bound = connection.createArrayOf("varchar", values);
            try (PreparedStatement seats = scope.prepareScoped(connection,
                    """
                    SELECT COUNT(*) FROM poste_affectation
                    WHERE edition_id = ? AND animateur_id = ANY(?)""");
                    PreparedStatement adHoc = scope.prepareScoped(connection,
                            """
                            SELECT COUNT(DISTINCT contrainte_id) FROM contrainte_animateur
                            WHERE edition_id = ? AND animateur_id = ANY(?)""");
                    PreparedStatement locks = scope.prepareScoped(connection,
                            """
                            SELECT COUNT(*) FROM verrouillage_planning
                            WHERE edition_id = ? AND animateur_id = ANY(?)""")) {
                return new ReferenceUsage(count(seats, bound), count(adHoc, bound), count(locks, bound));
            } finally {
                bound.free();
            }
        });
    }

    /** Counters for a set of timeslots; their ids are database-generated, hence the {@code bigint} array. */
    public ReferenceUsage forCreneaux(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return ReferenceUsage.AUCUN;
        }
        Object[] values = ids.toArray();
        return scope.read(FAILURE, connection -> {
            Array bound = connection.createArrayOf("bigint", values);
            try (PreparedStatement seats = scope.prepareScoped(connection,
                    """
                    SELECT COUNT(*) FROM poste_affectation
                    WHERE edition_id = ? AND animateur_id IS NOT NULL AND creneau_id = ANY(?)""");
                    PreparedStatement adHoc = scope.prepareScoped(connection,
                            """
                            SELECT COUNT(*) FROM contrainte_ad_hoc
                            WHERE edition_id = ? AND creneau_id = ANY(?)""");
                    PreparedStatement locks = scope.prepareScoped(connection,
                            """
                            SELECT COUNT(*) FROM verrouillage_planning
                            WHERE edition_id = ? AND creneau_id = ANY(?)""")) {
                return new ReferenceUsage(count(seats, bound), count(adHoc, bound), count(locks, bound));
            } finally {
                bound.free();
            }
        });
    }

    /** Binds the selection to a statement already prepared above, and reads its single row. */
    private static int count(PreparedStatement statement, Array ids) throws SQLException {
        statement.setArray(2, ids);
        try (ResultSet rs = statement.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
