package dev.sylvain.planning.scenario.dto;

import dev.sylvain.planning.domain.NiveauCompetence;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record AnimateurDto(
        @NotBlank String id,
        @NotBlank String prenom,
        @NotBlank String nom,
        @NotNull LocalDate dateNaissance,
        Boolean manager,
        @NotNull Map<String, NiveauCompetence> competences,
        List<LocalDate> joursIndisponibles,
        List<String> souhaits) {
}
