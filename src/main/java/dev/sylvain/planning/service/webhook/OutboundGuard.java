package dev.sylvain.planning.service.webhook;

import dev.sylvain.planning.config.ConfigWebhooks;
import dev.sylvain.planning.config.TrustedProxies;
import dev.sylvain.planning.service.BusinessError;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Decides whether a webhook address may be called, and <b>which IP</b> the
 * call goes to (see the « Appels sortants » section of
 * {@code docs/securite.md}).
 *
 * <p>An admin types the URL, and the server calls it: without a guard, a
 * webhook is a way to make the server reach what only it can reach — the
 * PostgreSQL port, the cloud metadata service at {@code 169.254.169.254}, an
 * admin interface bound to the loopback. So the host is resolved here, every
 * address it resolves to is checked, and the delivery then connects to the
 * address that was checked rather than letting the HTTP client resolve the
 * name a second time: a name that answered a public address to the check and
 * a private one to the connection (DNS rebinding) has nothing left to
 * exploit. TLS still verifies the certificate against the host name.</p>
 *
 * <p>The rules: {@code https} only, ports 443 and 8443 only, and no loopback,
 * private (RFC 1918), link-local, unique-local, carrier-grade NAT, multicast
 * or unspecified address — unless the address falls in
 * {@code WEBHOOKS_RESEAUX_AUTORISES}, where any port and plain {@code http} are
 * accepted too (an n8n on the same Docker network). Checked when a webhook is
 * saved and again at every send.</p>
 *
 * <p>Not applied to {@code METEO_URL}: that one is set by the operator in the
 * environment, not typed by a user, and a self-hosted Open-Meteo sits on the
 * private network by design.</p>
 */
@ApplicationScoped
public class OutboundGuard {

    private static final Set<Integer> PUBLIC_PORTS = Set.of(443, 8443);

    /** IPv4-compatible {@code ::a.b.c.d}. */
    private static final int[] IPV4_COMPATIBLE = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};

    /** IPv4-mapped {@code ::ffff:a.b.c.d}. */
    private static final int[] IPV4_MAPPED = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF, 0xFF};

    /** NAT64 well-known prefix {@code 64:ff9b::/96}. */
    private static final int[] NAT64_WELL_KNOWN = {0x00, 0x64, 0xFF, 0x9B, 0, 0, 0, 0, 0, 0, 0, 0};

    /** Local-use NAT64 {@code 64:ff9b:1::/48}. */
    private static final int[] NAT64_LOCAL_USE = {0x00, 0x64, 0xFF, 0x9B, 0, 1};

    /** 6to4 {@code 2002::/16}. */
    private static final int[] SIX_TO_FOUR = {0x20, 0x02};

    /** Teredo {@code 2001:0::/32}. */
    private static final int[] TEREDO = {0x20, 0x01, 0, 0};

    /** No IPv4 carried. */
    private static final byte[] NONE = new byte[0];

    /** The longest a name may take to resolve: the system resolver has no bound of its own. */
    static final Duration RESOLUTION_TIMEOUT = Duration.ofSeconds(5);

    /** Resolves a host name; replaced in the unit tests, which must not depend on a DNS. */
    @FunctionalInterface
    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /**
     * The name did not resolve — now. Unlike every other refusal of the guard,
     * which says something about the address itself, this one may be a DNS
     * hiccup: a send meeting it is retried, while saving a webhook is still
     * refused.
     */
    public static final class UnresolvedHost extends Exception {

        private static final long serialVersionUID = 1L;

        UnresolvedHost(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Where a checked call goes: the address to connect to, and what TLS verifies against. */
    public record Target(URI uri, String host, InetAddress address, int port, boolean https) {

        /** Path and query, what the request line carries. */
        public String requestUri() {
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
        }
    }

    private final TrustedProxies allowed;

    private final Resolver resolver;

    @Inject
    public OutboundGuard(ConfigWebhooks config) {
        this(
                TrustedProxies.of(config.reseauxAutorises().orElse(List.of()), "WEBHOOKS_RESEAUX_AUTORISES invalide"),
                host -> resolveWithin(host, RESOLUTION_TIMEOUT));
    }

    OutboundGuard(TrustedProxies allowed, Resolver resolver) {
        this.allowed = allowed;
        this.resolver = resolver;
    }

    /** Whether the operator opened internal networks to the webhooks — said once at startup. */
    public boolean opensInternalNetworks() {
        return !allowed.isEmpty();
    }

    /**
     * Checks {@code url} and resolves it to the address the call must use —
     * the check of a webhook being saved, where a name that does not resolve
     * is refused like any other mistake.
     *
     * @throws BusinessError.Invalid naming the rule that refused it — never
     *         quoting the URL itself, which is a secret for three formats
     */
    public Target check(String url) {
        try {
            return checkForSend(url);
        } catch (UnresolvedHost e) {
            throw new BusinessError.Invalid(e.getMessage(), e);
        }
    }

    /**
     * The same check, at the time of a send: a name that does not resolve
     * is told apart, so the delivery is retried rather than given up on a
     * DNS failure that may last a minute.
     *
     * @throws BusinessError.Invalid on an address refused for what it is
     * @throws UnresolvedHost        when the name does not resolve now
     */
    public Target checkForSend(String url) throws UnresolvedHost {
        URI uri = parse(url);
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        boolean https = scheme.equals("https");
        String host = uri.getHost();
        int port = uri.getPort();
        if (port == -1) {
            port = https ? 443 : 80;
        }
        InetAddress[] addresses = resolve(host);
        boolean allInAllowedNetworks = true;
        for (InetAddress address : addresses) {
            boolean permitted = allowed.containsAddress(address);
            allInAllowedNetworks &= permitted;
            if (!permitted && isInternal(address)) {
                throw new BusinessError.Invalid("« " + host + " » désigne une adresse interne ("
                        + address.getHostAddress()
                        + ") : refusée, sauf réseau déclaré dans WEBHOOKS_RESEAUX_AUTORISES.");
            }
        }
        if (!https && !allInAllowedNetworks) {
            throw new BusinessError.Invalid(
                    "L'adresse doit commencer par https:// — http:// n'est accepté que vers un réseau déclaré dans "
                            + "WEBHOOKS_RESEAUX_AUTORISES.");
        }
        if (!allInAllowedNetworks && !PUBLIC_PORTS.contains(port)) {
            throw new BusinessError.Invalid(
                    "Port " + port + " refusé : seuls 443 et 8443 sont acceptés hors des réseaux autorisés.");
        }
        return new Target(uri, host, addresses[0], port, https);
    }

    private static URI parse(String url) {
        if (url == null || url.isBlank()) {
            throw new BusinessError.Invalid("L'adresse du webhook est obligatoire.");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new BusinessError.Invalid("L'adresse du webhook n'est pas une URL valide.", e);
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))
                || uri.getHost() == null) {
            throw new BusinessError.Invalid("L'adresse du webhook doit être une URL https://.");
        }
        if (uri.getRawUserInfo() != null) {
            throw new BusinessError.Invalid(
                    "L'adresse du webhook ne doit pas porter d'identifiants (utilisateur:motdepasse@).");
        }
        return uri;
    }

    private InetAddress[] resolve(String host) throws UnresolvedHost {
        // [::1] arrives bracketed from URI.getHost(): the literal goes through as is.
        String name = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(name);
        } catch (UnknownHostException e) {
            throw new UnresolvedHost("« " + host + " » est introuvable (DNS).", e);
        }
        if (addresses == null || addresses.length == 0) {
            throw new UnresolvedHost("« " + host + " » ne se résout vers aucune adresse.", null);
        }
        return addresses;
    }

    /**
     * The system resolver, bounded: {@code getAllByName} waits as long as the
     * resolver configuration says, which is not ours to choose. The lookup
     * runs on a virtual thread of its own, abandoned past {@code limit}.
     */
    static InetAddress[] resolveWithin(String host, Duration limit) throws UnknownHostException {
        FutureTask<InetAddress[]> lookup = new FutureTask<>(() -> InetAddress.getAllByName(host));
        Thread.ofVirtual().name("webhook-dns").start(lookup);
        try {
            return lookup.get(limit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException _) {
            lookup.cancel(true);
            throw new UnknownHostException("DNS timeout");
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            lookup.cancel(true);
            throw new UnknownHostException("DNS lookup interrupted");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnknownHostException unknown) {
                throw unknown;
            }
            UnknownHostException failure = new UnknownHostException("DNS lookup failed");
            failure.initCause(e.getCause());
            throw failure;
        }
    }

    /**
     * Whether an address is one the server must not be made to call: what
     * only the server, or its own network, can reach.
     */
    static boolean isInternal(InetAddress address) {
        if (address.isLoopbackAddress()
                || address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            return isInternalIpv4(bytes);
        }
        if (address instanceof Inet6Address) {
            return isInternalIpv6(bytes);
        }
        return false;
    }

    /** The IPv4 ranges the {@link InetAddress} predicates leave out. */
    private static boolean isInternalIpv4(byte[] bytes) {
        int first = bytes[0] & 0xFF;
        int second = bytes[1] & 0xFF;
        return first == 0 // "this network"
                || (first == 100 && second >= 64 && second <= 127) // carrier-grade NAT, RFC 6598
                || (first == 192 && second == 0 && (bytes[2] & 0xFF) == 0) // IETF protocol assignments
                || first >= 240; // reserved and broadcast
    }

    /** The IPv6 ranges the {@link InetAddress} predicates leave out, and the IPv4 an address carries. */
    private static boolean isInternalIpv6(byte[] bytes) {
        if (((bytes[0] & 0xFF) & 0xFE) == 0xFC) {
            return true; // unique local fc00::/7
        }
        if (hasPrefix(bytes, NAT64_LOCAL_USE)) {
            // Local-use NAT64 64:ff9b:1::/48 (RFC 8215): a translator of
            // the site's own, whose prefix length — and so where the IPv4
            // sits — is not knowable from here.
            return true;
        }
        byte[] embedded = embeddedIpv4(bytes);
        if (embedded.length == 0) {
            return false;
        }
        try {
            return isInternal(InetAddress.getByAddress(embedded));
        } catch (UnknownHostException _) {
            return true;
        }
    }

    /**
     * The IPv4 address an IPv6 one carries, judged in its place: what the
     * connection really reaches is that IPv4 — through the host's own stack,
     * a NAT64 translator or a 6to4 relay. Empty when it carries none.
     *
     * <ul>
     *   <li>IPv4-mapped {@code ::ffff:a.b.c.d} and IPv4-compatible
     *       {@code ::a.b.c.d}: the last four bytes;</li>
     *   <li>NAT64 well-known prefix {@code 64:ff9b::/96} (RFC 6052): the last
     *       four bytes;</li>
     *   <li>6to4 {@code 2002::/16} (RFC 3056): bytes 2 to 5;</li>
     *   <li>Teredo {@code 2001:0::/32} (RFC 4380): the server in bytes 4 to 7
     *       and the client, inverted, in bytes 12 to 15 — the internal one of
     *       the two is returned, either is enough to refuse.</li>
     * </ul>
     */
    static byte[] embeddedIpv4(byte[] v6) {
        if (hasPrefix(v6, IPV4_COMPATIBLE) || hasPrefix(v6, IPV4_MAPPED) || hasPrefix(v6, NAT64_WELL_KNOWN)) {
            return Arrays.copyOfRange(v6, 12, 16);
        }
        if (hasPrefix(v6, SIX_TO_FOUR)) {
            return Arrays.copyOfRange(v6, 2, 6);
        }
        if (hasPrefix(v6, TEREDO)) {
            return teredoIpv4(v6);
        }
        return NONE;
    }

    /** Of the Teredo server and client, the internal one — the server when neither is. */
    private static byte[] teredoIpv4(byte[] v6) {
        byte[] server = Arrays.copyOfRange(v6, 4, 8);
        byte[] client = new byte[4];
        for (int i = 0; i < 4; i++) {
            client[i] = (byte) ~v6[12 + i];
        }
        try {
            return isInternal(InetAddress.getByAddress(server)) ? server : client;
        } catch (UnknownHostException _) {
            return server;
        }
    }

    /** Whether {@code bytes} starts with {@code prefix}, each byte read unsigned. */
    private static boolean hasPrefix(byte[] bytes, int[] prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
