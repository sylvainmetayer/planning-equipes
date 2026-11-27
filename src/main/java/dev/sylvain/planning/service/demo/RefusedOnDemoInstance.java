package dev.sylvain.planning.service.demo;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.enterprise.util.Nonbinding;
import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Marks a service method a demo instance ({@code DEMO_INSTANCE}, ADR 0130)
 * refuses: creating, deactivating an account or changing its rights, choosing
 * or mailing a password, generating an MCP key, configuring or sending a
 * webhook. The four demo accounts are
 * printed on the showcase; one visitor changing a password or a role would
 * lock every other visitor out until the next reset.
 *
 * <p>Binds {@link RefusedOnDemoInstanceInterceptor}, which answers {@code 409}
 * with « Instance de démonstration : » and {@link #value()}. On the service,
 * like {@code RequiresActiveEdition}: the REST resource and any MCP tool both
 * go through it. {@code DemoInstanceStructuralTest} fails on a write method
 * of the account, MCP key and webhook services that carries neither this annotation
 * nor an argued exemption.</p>
 */
@InterceptorBinding
@Retention(RUNTIME)
@Target({TYPE, METHOD})
public @interface RefusedOnDemoInstance {

    /** What is refused, completing « Instance de démonstration : ». */
    @Nonbinding
    String value() default "";
}
