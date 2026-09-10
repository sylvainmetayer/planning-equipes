package dev.sylvain.planning.mcp;

import dev.sylvain.planning.service.referentiel.Avertissement;
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
 */
final class WarningCodes {

    private WarningCodes() {
    }

    static List<String> of(List<Avertissement> avertissements) {
        return avertissements.stream().map(avertissement -> avertissement.type().name()).toList();
    }
}
