package dev.sylvain.planning.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Uniform answer of every {@code supprimer_*} tool. A DELETE endpoint answers
 * 204 with an empty body, which an MCP client would render as "null" — saying
 * what was deleted instead gives the assistant something to report back.
 */
public record SuppressionResult(
        String id,
        boolean supprime,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> avertissements)
        implements WarningCarrier<SuppressionResult> {

    public SuppressionResult(String id, boolean supprime) {
        this(id, supprime, List.of());
    }

    @Override
    public SuppressionResult withWarning(String code) {
        return new SuppressionResult(id, supprime, WarningCodes.with(avertissements, code));
    }
}
