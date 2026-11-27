package dev.sylvain.planning.service.referentiel;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.enterprise.util.Nonbinding;
import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Marks a service method that writes a family of the referential, and binds
 * {@link RefusedWhileFrozenInterceptor}, which refuses the call in
 * {@code 409 REFERENTIEL_FIGE} while the phase of the current edition freezes
 * any of {@link #value()} (ADR 0115) — every family when the list is empty,
 * for the operations that replace the whole edition. The name is the 3.x one,
 * kept: what the annotation says of a method — it writes these families — did
 * not change, only what decides they are frozen.
 *
 * <p>On the service, not on the resource or the tool: the service is the one
 * door every path goes through — the form, the bulk edit, a CSV import, an MCP
 * tool — so guarding it once is guarding all of them. On the method, not the
 * class (the type target is for the interceptor only): a service mixes reads,
 * writes of a frozen family and writes nothing freezes.</p>
 *
 * <p>ArC intercepts a call on {@code this} again, so the guard would run twice:
 * an annotated method is therefore never called from inside its own class, which
 * {@code GelReferentielStructuralTest} checks, together with every write method
 * of the guarded services carrying either this annotation, an explicit
 * {@link GelReferentielService#refuseIfFrozen} call, or an argued exclusion.</p>
 */
@InterceptorBinding
@Retention(RUNTIME)
@Target({TYPE, METHOD})
public @interface RefusedWhileFrozen {

    /** The families this method writes; empty means every family. */
    @Nonbinding
    ReferentialFamily[] value() default {};
}
