package dev.sylvain.planning.config;

import java.net.InetAddress;
import java.util.List;
import java.util.Objects;

/**
 * The reverse proxies a deployment trusts, as literal addresses or CIDR blocks.
 *
 * <p>A single address is enough when the proxy has a stable one. It usually has
 * not: behind a container runtime the proxy is a container on a bridge network,
 * and its address is handed out at attach time — a reboot that starts the
 * stacks in another order moves it. The <b>subnet</b>, on the other hand, is
 * fixed when the network is created and does not move, which is why a block is
 * accepted here. See {@code docs/exploitation.md} for the deployment note.</p>
 *
 * <p>Trusting a block means trusting every machine in it. That is the right
 * granularity for a private bridge whose members are the deployment's own
 * services, and the wrong one for a public range — the documentation says so
 * where an operator will read it.</p>
 *
 * <p>Entries are parsed once, at startup, and a malformed one fails the boot.
 * The alternative — skipping what does not parse — would leave a deployment
 * believing it had declared its proxy while the lock silently counted every
 * visitor on one shared counter.</p>
 */
public final class TrustedProxies {

    /** Trusts nothing: every {@code X-Forwarded-For} is then client-supplied text. */
    public static final TrustedProxies NONE = new TrustedProxies(List.of());

    /** One declared entry: a single address, or the fixed prefix of a block. */
    private record Entry(byte[] address, int prefixBits) {

        boolean matches(byte[] candidate) {
            // Never across families: an IPv4 block cannot contain an IPv6 host,
            // and the byte lengths differ, so comparing them would run off the end.
            if (candidate.length != address.length) {
                return false;
            }
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != address[i]) {
                    return false;
                }
            }
            int remainingBits = prefixBits % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainingBits);
            return (candidate[fullBytes] & mask) == (address[fullBytes] & mask);
        }
    }

    private final List<Entry> entries;

    private TrustedProxies(List<Entry> entries) {
        this.entries = entries;
    }

    /**
     * Parses the configured entries, rejecting anything that is not an address
     * literal or a {@code address/prefix} block.
     *
     * @throws IllegalArgumentException on a malformed entry, naming it
     */
    public static TrustedProxies of(List<String> declared) {
        Objects.requireNonNull(declared, "declared");
        return new TrustedProxies(declared.stream()
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .map(TrustedProxies::parse)
                .toList());
    }

    private static Entry parse(String declared) {
        int barre = declared.indexOf('/');
        if (barre < 0) {
            byte[] adresse = literal(declared, declared);
            return new Entry(adresse, adresse.length * 8);
        }
        byte[] adresse = literal(declared.substring(0, barre), declared);
        int maximum = adresse.length * 8;
        int prefixe;
        try {
            prefixe = Integer.parseInt(declared.substring(barre + 1).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Proxy fiable invalide : « " + declared + " » — la longueur de préfixe n'est pas un entier.", e);
        }
        if (prefixe < 0 || prefixe > maximum) {
            throw new IllegalArgumentException("Proxy fiable invalide : « " + declared
                    + " » — la longueur de préfixe doit être comprise entre 0 et " + maximum + ".");
        }
        return new Entry(adresse, prefixe);
    }

    /**
     * The bytes of an address literal.
     *
     * <p>{@link InetAddress#ofLiteral} rather than {@code getByName}: the latter
     * resolves a name it does not recognise as a literal, which would put a DNS
     * lookup — and whatever it answers today — inside a trust decision.</p>
     */
    private static byte[] literal(String texte, String declared) {
        try {
            return InetAddress.ofLiteral(texte.trim()).getAddress();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Proxy fiable invalide : « " + declared
                            + " » — attendu une adresse IP littérale, éventuellement suivie de /préfixe.",
                    e);
        }
    }

    /** Whether nothing at all is trusted, in which case no header is ever read. */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * Whether an address is one of the declared proxies.
     *
     * <p>Anything that is not an address literal answers {@code false} rather
     * than throwing: the candidates come from {@code X-Forwarded-For}, which is
     * attacker-controlled text and holds obfuscated identifiers, ports and
     * plain rubbish in the wild.</p>
     */
    public boolean contains(String candidate) {
        if (candidate == null || entries.isEmpty()) {
            return false;
        }
        byte[] adresse;
        try {
            adresse = InetAddress.ofLiteral(candidate.trim()).getAddress();
        } catch (IllegalArgumentException e) {
            return false;
        }
        return entries.stream().anyMatch(entry -> entry.matches(adresse));
    }
}
