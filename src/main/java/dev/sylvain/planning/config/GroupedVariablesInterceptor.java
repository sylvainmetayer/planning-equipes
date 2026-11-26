package dev.sylvain.planning.config;

import io.smallrye.config.ConfigSourceInterceptor;
import io.smallrye.config.ConfigSourceInterceptorContext;
import io.smallrye.config.ConfigValue;

/**
 * Answers the {@code planning.legal.*} and {@code planning.branding.*}
 * properties from the grouped variables of {@link GroupedVariables}, falling
 * back to the old variable names. Registered in
 * {@code META-INF/services/io.smallrye.config.ConfigSourceInterceptor}. It
 * asks the other sources for the variable by name, so it reads the environment
 * exactly as {@code application.properties} placeholders do.
 */
public class GroupedVariablesInterceptor implements ConfigSourceInterceptor {

    @Override
    public ConfigValue getValue(ConfigSourceInterceptorContext context, String name) {
        String value = GroupedVariables.resolve(name, variable -> {
            ConfigValue found = context.proceed(variable);
            return found == null ? null : found.getValue();
        });
        return value == null
                ? context.proceed(name)
                : ConfigValue.builder()
                        .withName(name)
                        .withValue(value)
                        .withConfigSourceName("grouped variables")
                        .build();
    }
}
