package dev.sylvain.planning.config;

import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Error reporting and web analytics. Both are off unless a token is
 * configured, so a local run sends nothing anywhere.
 */
@ConfigMapping(prefix = "observability")
public interface ConfigObservabilite {

    Sentry sentry();

    Cloudflare cloudflare();

    interface Sentry {

        /** Empty disables error reporting entirely. */
        Optional<String> dsn();

        @WithDefault("local")
        String environment();
    }

    interface Cloudflare {

        Optional<String> webAnalyticsToken();
    }
}
