package dev.sylvain.planning.scenario.dto;

import dev.sylvain.planning.domain.NiveauCompetence;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * One animateur of a scenario file.
 *
 * @param telephone optional; travels in the file like the e-mail address —
 *                  a scenario is a full copy of the referential, never an
 *                  export meant for somebody else (docs/rgpd.md)
 */
public record AnimateurDto(
        @NotBlank String id,
        @NotBlank String prenom,
        @NotBlank String nom,
        @NotNull LocalDate dateNaissance,
        Boolean manager,
        @Email String email,
        String telephone,
        @NotNull Map<String, NiveauCompetence> competences,
        List<LocalDate> joursIndisponibles,
        List<String> souhaits) {}
