package dev.sylvain.planning.mcp;

import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An MCP tool that settles something for the <b>instance</b> and not for one
 * edition - creating, renaming, activating or deleting an edition, the backups
 * - and is therefore refused to an organiser reaching {@code /mcp} through the
 * OAuth tenant (ADR 0097): the counterpart of the REST side's
 * {@code api/InstanceOnly}. The API key and the administrators are not narrowed.
 */
@InterceptorBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@interface InstanceOnlyTool {}
