package dev.sylvain.planning.scenario.dto;

import dev.sylvain.planning.domain.ModeGrilleCreneaux;
import dev.sylvain.planning.domain.ParametresDecoupage.PauseCoverageStrategy;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.LocalTime;

public record ParametresDecoupageDto(
        @Positive Integer dureeVacationCibleMinutes,
        @Positive Integer dureeVacationMinMinutes,
        @Positive Integer dureeVacationMaxMinutes,
        @PositiveOrZero Integer dureeChevauchementMinutes,
        @PositiveOrZero Integer dureePauseRepasMinutes,
        LocalTime fenetreRepasMidiDebut,
        LocalTime fenetreRepasMidiFin,
        LocalTime fenetreRepasSoirDebut,
        LocalTime fenetreRepasSoirFin,
        PauseCoverageStrategy strategieCouverturePendantPause,
        @Positive Integer nombreFamillesDecalage,
        @PositiveOrZero Integer dureeDecalageMaxMinutes,
        ModeGrilleCreneaux modeGrille) {}
