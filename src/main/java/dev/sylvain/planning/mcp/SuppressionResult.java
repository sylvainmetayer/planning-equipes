package dev.sylvain.planning.mcp;

/**
 * Uniform answer of every {@code supprimer_*} tool. A DELETE endpoint answers
 * 204 with an empty body, which an MCP client would render as "null" — saying
 * what was deleted instead gives the assistant something to report back.
 */
public record SuppressionResult(String id, boolean supprime) {
}
