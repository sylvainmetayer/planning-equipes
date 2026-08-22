package dev.sylvain.planning.mcp;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the {@code @ToolArg} through which an MCP client names the edition a
 * tool call must work in (issue #181).
 *
 * <p>The argument is read by {@link EditionCibleeInterceptor}, not by the tool
 * method itself: the body stays written as if it ran in one edition, which is
 * exactly what it does — the interceptor binds the edition around the call
 * through {@code EditionContext.executeIn}.</p>
 *
 * <p>Marking the parameter rather than matching its name keeps the two ends
 * tied together: renaming the argument cannot silently detach it from the
 * mechanism that gives it its effect.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@interface EditionArg {

    /** The one wording every tool repeats, so an assistant reads the same sentence everywhere. */
    String DESCRIPTION = "Édition ciblée : son id ou son nom (voir lister_editions). "
            + "Par défaut, l'édition courante (voir edition_courante).";
}
