package dev.sylvain.planning.mcp;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.interceptor.InterceptorBinding;

/**
 * Marks a class of MCP tools whose writes belong in the history (issue #406).
 *
 * <p>A binding of its own rather than reusing {@link EditionCiblee}, which is
 * what the first implementation did and where it silently failed:
 * {@code EditionMcpTools} and {@code SauvegardeMcpTools} act <b>across</b>
 * editions, so they legitimately carry no {@code @EditionCiblee} — and six
 * write tools, deleting a whole edition among them, were journalled by the
 * catalogue and intercepted by nobody. Two questions were riding on one
 * annotation; they now have one each.</p>
 *
 * <p>{@code JournalCoverageStructurelleTest} fails on any class declaring a
 * journalled tool without this annotation, so the hole cannot come back.</p>
 */
@Inherited
@InterceptorBinding
@Retention(RUNTIME)
@Target({ TYPE, METHOD })
public @interface Journalise {
}
