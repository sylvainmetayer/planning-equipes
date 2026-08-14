package dev.sylvain.planning.scenario.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * One entry of the optional top-level {@code typologies:} scenario section:
 * a {@code {id, label}} pair for the CRUD-managed {@code typologie}
 * referential (see {@code ReferenceDataService.TypologieItem}), letting a
 * scenario pin a human-readable label (e.g. {@code "ENF"} -&gt; {@code "Enfance"})
 * instead of falling back to the id-as-its-own-label default that
 * {@code ReferenceDataRepository#importFromPlanning} derives for any
 * typologie id referenced by a stand/animateur but never declared here.
 */
public record TypologieDto(@NotBlank String id, @NotBlank String label) {
}
