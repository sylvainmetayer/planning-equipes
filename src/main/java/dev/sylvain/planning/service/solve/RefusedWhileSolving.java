package dev.sylvain.planning.service.solve;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Marks a service method whose write the landing of a running solve would
 * undo, and binds {@link RefusedWhileSolvingInterceptor}, which refuses the
 * call through {@link SolverJobService#refuseIfSolving} — a
 * {@link SolverJobService.SolverBusyException}, the {@code 409} naming the job
 * — while a solve holds the current edition.
 *
 * <p>A solve builds its problem from the referential and the plan as they
 * stand at its start, and its landing re-upserts what that problem names and
 * rewrites every seat: a stand, an animateur or a timeslot written meanwhile,
 * a seat reassigned, a snapshot restored would quietly come back as they were
 * minutes later. Refusing is the only honest answer — turning one of these
 * into a write accepted with a warning would let the landing resurrect
 * data.</p>
 *
 * <p>On the service, not on the resource or the tool, for the reason
 * {@code RefusedWhileFrozen} gives: the service is the one door the REST
 * resource, the MCP tool and the bulk edit all go through. And an annotation
 * rather than a call at the head of the method because an annotation can be
 * checked: {@code RefusedWhileSolvingStructuralTest} fails on a write method
 * of the guarded services that neither carries it nor is argued open, and on
 * an annotated method called on {@code this}, which bypasses the
 * interceptor. A method that refuses only in one branch (a dry run reads
 * freely) keeps the explicit call, and is argued in that test too.</p>
 *
 * <p>On the method, not the class (the type target is for the interceptor
 * only): a service mixes reads, writes a solve overwrites and writes it never
 * reads.</p>
 */
@InterceptorBinding
@Retention(RUNTIME)
@Target({TYPE, METHOD})
public @interface RefusedWhileSolving {}
