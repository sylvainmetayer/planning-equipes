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
 * <p>{@code id} is a reference local to the file — what stands and
 * animateurs cite — and {@code code} the readable key the import matches an
 * existing typologie on (ADR 0050); a file without codes is matched on its
 * ids, read as codes.</p>
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
 *
 * <p>{@code description} (optional) is the organiser's own note — « cette
 * typologie nécessite d'apprendre 45 jeux ». It is read on the Typologies
 * screen and in the plan-by-typologie view, nowhere else: a scenario carries
 * it so that a seeded edition arrives with the notes its author wrote.</p>
 */
public record TypologieDto(
        @NotBlank String id,
        String code,
        @NotBlank String label,
        Boolean ninja,
        @Positive Integer maxCreneauxParAnimateur,
        String description) {}
