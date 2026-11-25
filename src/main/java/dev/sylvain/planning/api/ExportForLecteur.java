package dev.sylvain.planning.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A non-{@code GET} route that only <b>renders</b> a PDF or an ICS from the plan
 * the caller sends — nothing is written, nothing is solved — and stays open to a
 * lecteur although it is a {@code POST} (ADR 0099: PDF and ICS exports are
 * allowed). The one exception to « a lecteur's writes are refused »; the
 * structural test lists each one and checks it produces nothing else.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ExportForLecteur {}
