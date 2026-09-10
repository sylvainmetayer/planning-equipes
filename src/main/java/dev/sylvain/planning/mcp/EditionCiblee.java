package dev.sylvain.planning.mcp;

import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds {@link EditionCibleeInterceptor} to a class holding MCP tools, so that
 * every tool of that class honours its {@link EditionArg} argument.
 *
 * <p>Placed on the class rather than on each method on purpose: a tool added
 * later inherits the behaviour instead of quietly writing to the default
 * edition, which is the failure mode issue #181 exists to close. Methods
 * without an {@link EditionArg} parameter are left strictly alone.</p>
 */
@InterceptorBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@interface EditionCiblee {}
