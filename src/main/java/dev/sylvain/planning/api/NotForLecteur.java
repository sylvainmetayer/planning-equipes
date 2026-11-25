package dev.sylvain.planning.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A {@code GET} that hands out raw data (a CSV, an archive, a scenario file) and
 * is therefore refused to a lecteur (ADR 0099). Every other {@code GET} is open
 * to them; every write is refused, annotated or not.
 *
 * <p>{@code RoutesLecteurStructuralTest} holds the inventory of what stays open
 * and fails on a raw export left unannotated.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NotForLecteur {}
