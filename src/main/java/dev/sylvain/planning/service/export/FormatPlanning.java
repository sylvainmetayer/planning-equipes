package dev.sylvain.planning.service.export;

import java.util.Locale;

/**
 * Which layout an individual planning is asked for.
 *
 * <p>Two documents, one content: the <b>booklet</b> is the one an animateur
 * reads from their espace, the <b>sheet</b> is the one the organisation prints
 * by the hundred — one folded page per person instead of five. Both render the
 * same {@link AnimateurPlanningView}.</p>
 */
public enum FormatPlanning {
    /** A4 portrait, several pages: overview, days, teams and places. */
    LIVRET,

    /** One A4 landscape sheet, printed on both sides: calendar, then teams and places. */
    FEUILLE;

    /** The default of every caller that names no format — the espace, the mails, the ZIPs. */
    public static final FormatPlanning DEFAUT = LIVRET;

    /**
     * Reads the {@code format} query parameter, tolerating case and absence:
     * an unknown value is a caller's typo, and answering it with the usual
     * document beats answering it with a 400 nobody expected on a download.
     */
    public static FormatPlanning fromParameter(String valeur) {
        if (valeur == null || valeur.isBlank()) {
            return DEFAUT;
        }
        return switch (valeur.trim().toLowerCase(Locale.ROOT)) {
            case "feuille", "sheet" -> FEUILLE;
            default -> LIVRET;
        };
    }
}
