package dev.sylvain.planning.config;

/**
 * The sentence that makes the 4.0 break of {@code PUBLIC_INSTANCE} loud (ADR
 * 0110): a 3.x instance open to the public applied its solve quota and refused
 * to boot without a legal notice on its own, and in 4.0 it does neither unless
 * it says it is public. Said only when the variable is <b>absent</b>: an
 * operator who wrote {@code PUBLIC_INSTANCE=false} has decided.
 */
public final class PublicInstance {

    private PublicInstance() {}

    /**
     * The warning for an undeclared instance on which {@code lost} — what a
     * public one would have applied — no longer holds.
     */
    public static String undeclared(String lost) {
        return "PUBLIC_INSTANCE n'est pas posée : depuis la 4.0, cette instance n'est plus traitée comme publique, "
                + lost + ". Posez PUBLIC_INSTANCE=true si elle est ouverte au public, ou PUBLIC_INSTANCE=false "
                + "pour confirmer qu'elle ne l'est pas et faire taire cet avertissement "
                + "(site/src/docs/installer/passer-a-la-4-0.md).";
    }
}
