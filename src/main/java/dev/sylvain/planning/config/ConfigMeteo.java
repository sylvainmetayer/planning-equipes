package dev.sylvain.planning.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * The weather alert of the instance (ADR 0074): whether it may query at all,
 * where, and when. The thresholds and presets are an edition's settings; these
 * are the machine's.
 */
@ConfigMapping(prefix = "planning.meteo")
public interface ConfigMeteo {

    /** {@code METEO_ENABLED}: false cuts the feature for the whole instance — no query leaves. */
    @WithDefault("true")
    boolean enabled();

    /**
     * {@code METEO_URL}: the forecast endpoint — Open-Meteo's free API by
     * default, a self-hosted instance or the commercial offer otherwise. Set
     * by the operator, so the SSRF guard of the webhooks does not apply.
     */
    @WithDefault("https://api.open-meteo.com/v1/forecast")
    String url();

    /** {@code METEO_CRON}: once a day, in {@code NOTIFICATIONS_TIMEZONE}. */
    @WithDefault("0 0 6 * * ?")
    String cron();
}
