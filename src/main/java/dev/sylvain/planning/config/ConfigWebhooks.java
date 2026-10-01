package dev.sylvain.planning.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.List;
import java.util.Optional;

/**
 * The outgoing webhooks of the instance (ADR 0074): whether anything may leave
 * at all, how often the retries are swept, the key that encrypts their secrets
 * at rest, and the internal networks an operator opened to them. See
 * {@code docs/exploitation.md} and the « Appels sortants » section of
 * {@code docs/securite.md}.
 */
@ConfigMapping(prefix = "planning.webhooks")
public interface ConfigWebhooks {

    /** {@code WEBHOOKS_ENABLED}: false cuts every outgoing call — nothing is queued, nothing sent. */
    @WithDefault("true")
    boolean enabled();

    /** {@code WEBHOOKS_CRON}: the sweep that re-delivers the due retries. */
    @WithDefault("0 * * * * ?")
    String cron();

    /**
     * {@code WEBHOOKS_SECRET_KEY}: 32 bytes in base64, the AES-GCM key of the
     * stored secrets. Absent, no webhook can be created, and the application
     * refuses to start once one exists.
     */
    Optional<String> secretKey();

    /**
     * {@code WEBHOOKS_RESEAUX_AUTORISES}: addresses and CIDR blocks a webhook
     * may target although the guard refuses them by default — parsed like the
     * trusted proxies, a malformed entry failing the boot.
     */
    Optional<List<String>> reseauxAutorises();
}
