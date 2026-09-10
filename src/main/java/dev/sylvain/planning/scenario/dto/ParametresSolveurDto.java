package dev.sylvain.planning.scenario.dto;

import jakarta.validation.constraints.Positive;

public record ParametresSolveurDto(@Positive Integer dureeResolutionSecondes) {}
