package dev.sylvain.planning.service;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Numeric-aware id order ("A2" before "A10"), unlike SQL's {@code ORDER BY id}
 * which sorts ids as plain text ("A1", "A10", "A100", "A101", ..., "A11", ...).
 * Every referential list is re-sorted with it in Java.
 *
 * <p>It is not a cosmetic preference. Besides being confusing wherever these
 * lists reach the UI, the scrambled order also becomes the solver's
 * animateurRange value order, and the local search (fixed random seed) is
 * highly sensitive to it — an alphabetically-scrambled animateur list
 * measurably slowed convergence on scenario-complet.yaml (~25s to
 * hard-feasible with a natural order vs. not even converging within the full
 * 180s production time budget with the raw SQL order).</p>
 */
public final class NaturalOrder {

    public static final Comparator<String> OF_IDS = NaturalOrder::comparer;

    private static final Pattern MORCEAU = Pattern.compile("(\\d+)|(\\D+)");

    private NaturalOrder() {
    }

    private static int comparer(String a, String b) {
        Matcher ma = MORCEAU.matcher(a);
        Matcher mb = MORCEAU.matcher(b);
        while (ma.find() && mb.find()) {
            String morceauA = ma.group();
            String morceauB = mb.group();
            int comparaison = Character.isDigit(morceauA.charAt(0)) && Character.isDigit(morceauB.charAt(0))
                    ? new BigInteger(morceauA).compareTo(new BigInteger(morceauB))
                    : morceauA.compareTo(morceauB);
            if (comparaison != 0) {
                return comparaison;
            }
        }
        return a.length() - b.length();
    }
}
