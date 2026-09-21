package dev.sylvain.planning.api;

import dev.sylvain.planning.config.TrustedProxies;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.RoutingContext;
import java.util.Arrays;
import java.util.List;

/**
 * The address a per-address guard counts a request against.
 *
 * <p>Extracted from {@link AdminLoginLimiter}, which used to own it alone, the
 * day {@link McpRateLimiter} needed the very same answer. It is the subtle half
 * of both guards — reading the header from the wrong end makes them count
 * nothing at all (see below) — and a second hand-rolled copy is how the two
 * would drift apart.</p>
 *
 * <p>Two steps, and the first is the one that matters. <b>The peer must be a
 * declared proxy</b> — read from {@code connection().remoteAddress()}, not
 * {@code request().remoteAddress()}, which {@code proxy-address-forwarding} has
 * already rewritten with the client's own forged value. If the machine actually
 * connecting is not declared — as a literal address or within one of its CIDR
 * blocks — {@code X-Forwarded-For} is whatever that machine chose to write, so
 * it is ignored entirely and the connection address is counted. Declaring
 * nothing — the default — trusts no header at all, which is right for a
 * deployment with no proxy and safe for one whose proxies have not been
 * declared.</p>
 *
 * <p>When the peer <em>is</em> a declared proxy, the header is walked from the
 * <b>right</b>, skipping further declared proxies, and the first remaining entry
 * wins: that is the address the last trusted hop actually observed, appended by
 * it. Everything to its left is client-supplied text.</p>
 *
 * <p>Reading from the left is what made the login lock useless. A proxy
 * <em>appends</em> its entry rather than replacing the header, so the leftmost
 * element is the client's own. Both the first version of that lock and
 * {@code remoteAddress()} under {@code proxy-address-forwarding} take exactly
 * that one — Quarkus's {@code ForwardedParser} calls
 * {@code getFirstElement(forHeader)} — so one forged header per attempt bought a
 * fresh counter. {@code quarkus.http.proxy.trusted-proxies} does not help: it
 * decides whether the header is read at all, never which element is kept.</p>
 */
final class ClientAddress {

    private ClientAddress() {}

    /** The address of {@code context}, given the proxies this deployment trusts. */
    static String of(RoutingContext context, TrustedProxies trusted) {
        // connection(), not request(): proxy-address-forwarding has already
        // rewritten remoteAddress() with the client's own forged value. Only the
        // TCP peer says who is really speaking.
        String peer = hostOf(context.request().connection().remoteAddress());
        if (!trusted.contains(peer)) {
            return peer;
        }
        List<String> forwarded = forwardedFor(context);
        for (int i = forwarded.size() - 1; i >= 0; i--) {
            String candidate = forwarded.get(i);
            if (!trusted.contains(candidate)) {
                return candidate;
            }
        }
        return peer;
    }

    /** {@code X-Forwarded-For} split into its entries, empty when absent. */
    private static List<String> forwardedFor(RoutingContext context) {
        String header = context.request().getHeader("X-Forwarded-For");
        if (header == null || header.isBlank()) {
            return List.of();
        }
        return Arrays.stream(header.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    /**
     * Vert.x renders a non-IP host as a {@code null} {@code hostAddress()} — a
     * proxy emitting {@code unknown}, which Squid does, used to reach the
     * counter map as a null key and answer 500 on {@code /j_security_check}.
     */
    private static String hostOf(SocketAddress address) {
        if (address == null) {
            return "inconnue";
        }
        String ip = address.hostAddress();
        if (ip != null && !ip.isBlank()) {
            return ip;
        }
        String host = address.host();
        return host == null || host.isBlank() ? "inconnue" : host;
    }
}
