package dev.sylvain.planning.scenario.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * One entry of the optional top-level {@code typologies:} scenario section:
 * a {@code {id, label}} pair for the CRUD-managed {@code typologie}
 * referential (see {@code TypologieItem}), letting a
 * scenario pin a human-readable label (e.g. {@code "ENF"} -&gt; {@code "Enfance"})
 * instead of falling back to the id-as-its-own-label default that
 * {@code ReferenceDataImportRepository#importFromPlanning} derives for any
 * typologie id referenced by a stand/animateur but never declared here.
 *
 * <p>{@code ninja} (optional, {@code false} by default) marks the single
 * typologie whose holders are polyvalent — dispatchable on any stand. At most
 * one entry of the list should carry it; the referential keeps only the last
 * one applied.</p>
 */
public record TypologieDto(@NotBlank String id, @NotBlank String label, Boolean ninja) {}
