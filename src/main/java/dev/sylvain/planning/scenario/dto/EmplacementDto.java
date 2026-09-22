package dev.sylvain.planning.scenario.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * An emplacement as a scenario file writes it.
 *
 * <p>{@code id} is a <b>local reference</b> (decision 0049, D3), like a
 * créneau's: it is what the stands of the same file cite, and it names nothing
 * outside that file — the mapper replaces it with a counter, and the database
 * mints the real id on import.</p>
 *
 * <p>{@code code} is the stable key the import reconciles on, so that
 * re-importing a file updates the emplacement it already describes instead of
 * dropping and recreating it. It is optional, and <b>falls back to the id</b>:
 * every file written before this decision carries a typed id
 * ({@code PLACE-DRAPEAU}) in exactly that role, which is also what the
 * migration turned those ids into.</p>
 */
public record EmplacementDto(
        @NotBlank String id, String code, @NotBlank String nom, Double latitude, Double longitude) {

    /** The key this line is reconciled on: its code, or the local reference for a file that predates it. */
    public String codeOrId() {
        return code == null || code.isBlank() ? id : code;
    }
}
