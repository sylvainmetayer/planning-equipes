package dev.sylvain.planning.mcp;

import dev.sylvain.planning.service.referentiel.Avertissement;
import java.util.ArrayList;
import java.util.List;

/**
 * What an assistant gets of a write-time warning: the code, not the sentence.
 *
 * <p>The REST side returns {@code Avertissement(type, message)} and the screen
 * shows the message. The message of {@code MINEUR_PENDANT_EVENEMENT} says on
 * which day the animateur comes of age — which is the date of birth, moved by
 * eighteen years, and the one field the MCP views deliberately withhold
 * ({@code docs/mcp.md}). Rather than decide per type which sentence is safe,
 * every warning crosses to MCP as its {@code TypeAvertissement} name — whose
 * javadoc says what each one means — and {@code consulter_*} gives the detail
 * the assistant is allowed to read.</p>
 *
 * <p>The anomalies of the opening report are not warnings of a write and do
 * not cross through here: {@code analyser_ouvertures_stands} returns them
 * whole, since they name stands and hours, never a person. Their codes are
 * the {@code OuvertureStandsAnalyzer.AnomalyType} names, the last three of
 * which — {@code REGLES_CHEVAUCHANTES}, {@code REGLE_MASQUEE},
 * {@code FENETRES_CHEVAUCHANTES} — are for information only: the resolver
 * settles them, the tool says so.</p>
 */
final class WarningCodes {

    /**
     * The write was accepted while a solve holds its edition: the solve works
     * on the referential — and the plan — it read at its start, so its result
     * will not reflect this write, and a solve launched afterwards will.
     *
     * <p>MCP only, and on purpose: the screens lock these writes for the length
     * of a solve, so the REST side never needs to say it. Added by
     * {@link WarnsWhileSolvingInterceptor}, never by a service; the job
     * holding the edition is what {@code statut_solveur} reads.</p>
     */
    static final String RESOLUTION_EN_COURS = "RESOLUTION_EN_COURS";

    private WarningCodes() {}

    /** {@code codes} with {@code code} added at the end, once — a new list, the records stay immutable. */
    static List<String> with(List<String> codes, String code) {
        List<String> all = new ArrayList<>(codes == null ? List.of() : codes);
        if (!all.contains(code)) {
            all.add(code);
        }
        return List.copyOf(all);
    }

    static List<String> of(List<Avertissement> avertissements) {
        return avertissements.stream()
                .map(avertissement -> avertissement.type().name())
                .toList();
    }
}
