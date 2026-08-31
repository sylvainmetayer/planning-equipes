package dev.sylvain.planning.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.ws.rs.NameBinding;

/**
 * Marks a route whose only credential is the ICS subscription token carried by
 * its {@code {token}} path segment: {@link AbonnementTokenFilter} resolves it,
 * answers 404 on an unknown one, and binds the owner's edition to the request.
 *
 * <p>Deliberately not reusable elsewhere. It is bound to exactly one route —
 * the calendar feed — and that is what makes the perimeter of this token
 * readable: a second annotated route would silently widen what a leaked
 * calendar URL opens.</p>
 */
@NameBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD })
public @interface AbonnementTokenRequired {
}
