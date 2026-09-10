package dev.sylvain.planning.scenario.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record FestivalDto(@NotNull LocalDate dateDebut) {}
