package dev.sylvain.planning.mcp;

/**
 * The answer of an MCP write tool that can carry a warning code, which is what
 * {@link WarnsWhileSolvingInterceptor} needs to add
 * {@link WarningCodes#RESOLUTION_EN_COURS} to it.
 *
 * <p>An interceptor cannot change the type a tool returns — the proxy casts
 * what it hands back to the declared type — so the answer itself says how it
 * takes one more code. The views are records: {@link #withWarning} returns a
 * copy, and an answer that never warns serialises exactly as before, its
 * {@code avertissements} left out when empty.</p>
 */
public interface WarningCarrier<T extends WarningCarrier<T>> {

    /** A copy of this answer with {@code code} among its warnings, once. */
    T withWarning(String code);
}
