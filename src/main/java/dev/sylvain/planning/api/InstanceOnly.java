package dev.sylvain.planning.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A route (or a whole resource) that settles something for the <b>instance</b>
 * and not for one edition: accounts, webhooks, the MCP key, backups, the
 * debugging screen, the whole-database export, creating or deleting an
 * edition. An account that is only an organiser is refused there
 * ({@link InstanceOnlyFilter}); the administrators are not.
 *
 * <p>{@code InstanceRoutesStructuralTest} holds the inventory and refuses a
 * resource nobody classified.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface InstanceOnly {}
