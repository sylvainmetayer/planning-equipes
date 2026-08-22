package dev.sylvain.planning.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.ws.rs.NameBinding;

/**
 * Marks an espace-animateur route as requiring the e-mail-code session on top
 * of the URL token: {@link SessionEspaceFilter} then answers 404 (unknown
 * token) or 401 (no live session of the token's animateur) before the method
 * runs. The two bootstrap routes — code request and session opening — stay
 * unannotated: they are how a session comes to exist.
 */
@NameBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD })
public @interface EspaceSessionRequired {
}
