package dev.sylvain.planning.scenario.dto;

import jakarta.validation.constraints.NotBlank;

public record EmplacementDto(@NotBlank String id, @NotBlank String nom, Double latitude, Double longitude) {}
