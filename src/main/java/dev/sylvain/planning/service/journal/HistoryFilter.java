package dev.sylvain.planning.service.journal;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;

/**
 * What one read of the history keeps. Every part is optional: {@code null} is
 * no filter, never « nothing ».
 *
 * @param codes       the action codes kept, {@code null} for every action; an
 *                    empty selection keeps nothing
 * @param successOnly only the actions that went through — what a change
 *                    count reads, a refused write having changed nothing
 * @param since       exclusive lower bound, as {@code countSince} reads it
 * @param until       inclusive upper bound
 */
public record HistoryFilter(Collection<String> codes, boolean successOnly, Instant since, Instant until) {

    /** Every line, whenever. */
    public static final HistoryFilter ALL = new HistoryFilter(null, false, null, null);

    public HistoryFilter {
        codes = codes == null ? null : Set.copyOf(codes);
    }
}
