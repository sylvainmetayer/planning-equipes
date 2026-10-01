package dev.sylvain.planning.service.edition;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Marks a service method that reaches outside the application — publishes,
 * mails an animateur, opens a collection or the swap fair to the people — and
 * binds {@link RequiresActiveEditionInterceptor}, which refuses the call in
 * {@code 409 EDITION_INACTIVE} unless the edition it works in is the active
 * one (ADR 0072).
 *
 * <p>An inactive edition stays entirely usable inside: referential, solve,
 * locks, simulations, snapshots. Only what leaves is closed, so that two
 * editions alive at once — the one under way and the next one being
 * prepared — can never both write to the same people.</p>
 *
 * <p>On the service, for the reason {@code RefusedWhileSolving} gives: it is
 * the one door the REST resource and the MCP tool both go through. Held by
 * {@code ActiveEditionEmissionStructuralTest}, which fails on a mail of
 * {@code MailService} or an outward MCP tool whose service method carries
 * neither this annotation nor an argued exemption.</p>
 */
@InterceptorBinding
@Retention(RUNTIME)
@Target({TYPE, METHOD})
public @interface RequiresActiveEdition {}
