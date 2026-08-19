package dev.sylvain.planning.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.ws.rs.NameBinding;

/**
 * Marks an espace-animateur route as requiring (only) a valid URL token: the
 * bootstrap routes of the session — code request and session opening — which
 * cannot demand the session they exist to create. {@link JetonEspaceFilter}
 * resolves the token (404 unknown) and binds the owner's edition to the
 * request; every other espace route wants {@link SessionEspaceRequise}
 * instead, which adds the session check on top.
 */
@NameBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD })
public @interface JetonRequis {
}
