package dev.sylvain.planning.api;

import jakarta.ws.rs.NameBinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a route as only meaningful while the foire au planning is open:
 * {@link FoireOpenFilter} answers 400 before the method runs, with the same
 * wording as the writes of the feature.
 *
 * <p>Meant for the <b>reads</b> that exist solely to feed the exchange screens
 * — today the colleague's-seats picker. The writes (submit, cancel, agree,
 * decline) keep their own check inside {@code DemandeEchangeService}: that one
 * is the real enforcement, it holds whoever calls, and it must not depend on a
 * route being annotated.</p>
 */
@NameBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface FoireOpenRequired {}
