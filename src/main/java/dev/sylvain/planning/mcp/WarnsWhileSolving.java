package dev.sylvain.planning.mcp;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Marks an MCP write tool that is <b>accepted</b> while a solve holds its
 * edition, and binds {@link WarnsWhileSolvingInterceptor}, which then adds
 * {@link WarningCodes#RESOLUTION_EN_COURS} to its answer.
 *
 * <p>The screens lock these writes for the length of a solve
 * ({@code SolverJobService.editingLocked} on the frontend); an assistant has no
 * screen, and nothing on the server refuses them — the guard
 * {@code refuseIfSolving} sits on the writes whose data the landing of a solve
 * would bring back, and on no other. The write goes through, and the solve
 * that is running goes on with the referential and the plan it read at its
 * start: its result will not reflect it.
 * Warning, not refusing, is the trade-off of ADR 0020 — a write is answered
 * with what it did not achieve rather than turned down.</p>
 *
 * <p>On the method, not the class (the type target is for the interceptor
 * only): a tool class mixes reads, writes refused in
 * {@code 409} and writes accepted, and only the last ones warn.
 * {@code McpWarnsWhileSolvingStructuralTest} fails on a write tool that is
 * neither refused during a solve, nor annotated, nor excluded with its
 * reason — a new tool cannot leave the assistant uninformed by omission — and
 * on an annotated tool whose answer cannot carry the warning.</p>
 */
@InterceptorBinding
@Retention(RUNTIME)
@Target({TYPE, METHOD})
public @interface WarnsWhileSolving {}
