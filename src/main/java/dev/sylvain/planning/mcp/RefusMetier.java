package dev.sylvain.planning.mcp;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Binds {@link RefusMetierInterceptor} to a class holding MCP tools, so that a
 * refusal from the domain reaches the assistant as a refusal instead of
 * « Internal error » (issue #529).
 *
 * <p>A binding of its own, next to {@link EditionCiblee} and {@code Journalise}
 * rather than folded into either: what an edition argument means, what belongs
 * in the history and what an error looks like on the wire are three questions,
 * and the history binding already exists because two of them once rode on one
 * annotation. {@code SauvegardeMcpTools} carries no {@code @EditionCiblee} at
 * all, so reusing that one would have left a whole family answering
 * « Internal error ».</p>
 *
 * <p>Placed on the class, like its two neighbours: a tool added later inherits
 * it instead of losing its message. {@code McpRefusMetierStructurelleTest}
 * fails on any class declaring a {@code @Tool} without it.</p>
 */
@Inherited
@InterceptorBinding
@Retention(RUNTIME)
@Target({TYPE, METHOD})
public @interface RefusMetier {}
