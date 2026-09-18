package dev.sylvain.planning.scenario.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * One entry of the optional top-level {@code consignes:} section: what the
 * organiser imposed on the whole event for one date (ADR 0043) — the band
 * every stand is shut on, why, and the compensation chosen stand by stand.
 *
 * <p>The section is applied <b>after</b> the stands and the créneaux landed,
 * since the openings name stands and the added créneaux name créneaux, and it
 * replaces the edition's consignes wholesale. Absent, the edition keeps the
 * ones it had. It is written straight into the referential, never through the
 * gesture of the Consignes screen: a file may carry a date already worked,
 * and the créneaux it added are in the {@code creneaux:} section already.</p>
 *
 * @param fermetureFin    {@code null} reads « jusqu'à minuit », like every dated window
 * @param prereglage      name of the preset the consigne was made from, kept as a name
 * @param fenetres        the day's default compensation windows
 * @param ouvertures      the stands opened, one entry per window
 * @param creneauxAjoutes the créneaux the consigne added to the grid, named by
 *                        their day and hours: a créneau's id does not survive
 *                        an export, its date and hours do. Each one must be
 *                        listed under {@code creneaux:} as well — the import
 *                        ties the two back together by that key
 * @param repas           the meal windows the date runs under instead of the
 *                        edition's, with the reason; absent for the edition's
 */
public record ConsigneDto(
        @NotNull LocalDate date,
        @NotNull LocalTime fermetureDebut,
        LocalTime fermetureFin,
        @NotBlank String motif,
        String prereglage,
        List<@Valid FenetreConsigneDto> fenetres,
        List<@Valid OuvertureConsigneDto> ouvertures,
        List<@Valid CreneauAjouteDto> creneauxAjoutes,
        @Valid RepasConsigneDto repas) {}
