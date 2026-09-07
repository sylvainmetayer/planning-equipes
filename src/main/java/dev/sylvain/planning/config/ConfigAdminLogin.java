package dev.sylvain.planning.config;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import io.smallrye.config.ConfigMapping;

/** Rate limit on the admin login form, per source address. */
@ConfigMapping(prefix = "planning.auth.connexion")
public interface ConfigAdminLogin {

    int maxEchecs();

    Duration dureeBlocage();

    /**
     * Addresses of the reverse proxies in front of this deployment, in
     * {@code X-Forwarded-For} terms.
     *
     * <p>The lock walks that header from the right and stops at the first entry
     * that is <b>not</b> in this list: that is the address the last trusted hop
     * observed, and the one value in the chain a client cannot choose. Left
     * empty — the default — the whole header is untrusted and the connection
     * address is counted, which is right for a deployment with no proxy and safe
     * for one whose proxies have not been declared.</p>
     *
     * <p>Distinct from {@code quarkus.http.proxy.trusted-proxies}, which decides
     * whether Quarkus rewrites {@code remoteAddress()} at all. That setting
     * cannot help here: it never chooses <em>which</em> element of the header is
     * kept, and Quarkus always keeps the leftmost — the forged one.</p>
     */
    Optional<List<String>> proxysFiables();
}
