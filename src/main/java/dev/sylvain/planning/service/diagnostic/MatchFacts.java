package dev.sylvain.planning.service.diagnostic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The justification facts of one constraint match, in the order the constraint
 * declared them — an {@code Animateur}, a {@code PosteAffectation}, a
 * {@code ContrainteAdHoc}, a collection of {@code Creneau}, …
 *
 * <p>Facts and nothing else. A match also carries its own score, which nothing
 * downstream reads: violations are counted and formatted, never weighted one by
 * one. Carrying it would be a field kept in sync for no reader.</p>
 */
public record MatchFacts(List<Object> facts) {

    /**
     * Defensively copied, but <b>not</b> with {@code List.copyOf}: a
     * justification may legitimately carry a null fact (an unfilled seat has no
     * animateur), and {@code List.copyOf} rejects nulls.
     */
    public MatchFacts {
        facts = Collections.unmodifiableList(new ArrayList<>(facts));
    }
}
