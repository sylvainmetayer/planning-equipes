package dev.sylvain.planning.scenario.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;
import java.util.List;

/**
 * One entry of the optional top-level {@code prereglagesConsigne:} section: a
 * named preset for a consigne (ADR 0043) — « Plan canicule : 12h-18h fermé,
 * soir 18h-22h » — band, motif, default compensation windows and, when it
 * restates them, meal windows.
 *
 * <p>Applied like the consignes: present, the section replaces the edition's
 * presets wholesale; absent, the edition keeps its own. The {@code id} is
 * optional — a preset written by hand gets one on import — and a consigne
 * names its preset by {@code nom}, never by id.</p>
 */
public record PrereglageConsigneDto(
        String id,
        @NotBlank String nom,
        @NotNull LocalTime fermetureDebut,
        LocalTime fermetureFin,
        @NotBlank String motif,
        List<@Valid FenetreConsigneDto> fenetres,
        @Valid RepasConsigneDto repas) {}
