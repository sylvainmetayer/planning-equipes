package dev.sylvain.planning.scenario.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;

/**
 * One stretch of a day under a consigne, {@code [debut, fin)}: a default
 * compensation window of a {@link ConsigneDto} or a {@link PrereglageConsigneDto}.
 * {@code fin} is optional and reads « jusqu'à minuit ».
 */
public record FenetreConsigneDto(@NotNull LocalTime debut, LocalTime fin) {}
