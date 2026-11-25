package dev.sylvain.planning.mcp;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A tool that writes nothing ({@code readOnlyHint = true}) but <b>computes</b> rather than reads: a
 * simulation, a preview, a repair search, a fresh analysis — what the screens ask by a {@code POST}. A
 * lecteur is refused it as it is refused a write (ADR 0099): it reads, it never computes.
 * {@code McpLecteurStructurelleTest} derives which tools carry it from their name.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Computes {}
