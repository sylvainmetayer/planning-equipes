package dev.sylvain.planning.mcp;

import io.quarkiverse.mcp.server.Prompt;
import io.quarkiverse.mcp.server.Tool;
import java.lang.reflect.Method;

/**
 * The name the MCP server publishes for a tool or a prompt method.
 *
 * <p>The published name is the snake_case French one an assistant picks from
 * ({@code lister_stands}), declared on the annotation; the Java method carries
 * an English camelCase name. Anything keyed on the published name — the
 * journal catalogue, the structural guards — must read it here, never from
 * {@link Method#getName()}.</p>
 */
final class FeatureNames {

    private FeatureNames() {}

    /** The published name of {@code method}, falling back to the method name when the annotation names nothing. */
    static String of(Method method) {
        String declared = null;
        Tool tool = method.getAnnotation(Tool.class);
        if (tool != null) {
            declared = tool.name();
        } else {
            Prompt prompt = method.getAnnotation(Prompt.class);
            if (prompt != null) {
                declared = prompt.name();
            }
        }
        return declared == null || Tool.ELEMENT_NAME.equals(declared) || Prompt.ELEMENT_NAME.equals(declared)
                ? method.getName()
                : declared;
    }
}
