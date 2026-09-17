package dev.sylvain.planning.scenario.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

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
 *
 * <p>{@code maxCreneauxParAnimateur} (optional) is the quota of issue #594: how
 * many créneaux one animateur may hold on this typologie over the whole
 * edition. Absent means no cap, which is what the referential carries until
 * somebody sets one.</p>
 */
public record TypologieDto(
        @NotBlank String id,
        @NotBlank String label,
        Boolean ninja,
        @Positive Integer maxCreneauxParAnimateur) {}
