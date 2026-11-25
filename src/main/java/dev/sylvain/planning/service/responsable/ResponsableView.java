package dev.sylvain.planning.service.responsable;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What a responsable de stand reads: the <b>published</b> shifts of the stands
 * their rights cover on the active edition, and nothing else.
 *
 * <p>A projection, never the domain: no component of this record, nor of the
 * ones it holds, is an {@code Animateur}, a {@code Stand} or a plan —
 * {@code ResponsableProjectionStructurelleTest} reads it off the types. What is
 * not a component here cannot leak, whatever a later change of the domain adds
 * to it: a birth date, an address, a phone number, a token.</p>
 *
 * @param editionNom the active edition's name; {@code null} when no right of the caller opens anything now
 * @param publieLe   when the plan read was published; {@code null} while the edition has published nothing —
 *                   every stand then shows no shift at all, which the screen says rather than « personne »
 * @param stands     the stands of the caller's rights, by location then name; empty when nothing is open now
 */
@Schema(requiredProperties = {"stands"})
public record ResponsableView(String editionNom, Instant publieLe, List<StandResponsable> stands) {

    /**
     * One stand of the scope.
     *
     * @param nominatif    whether its shifts name who holds them, now
     * @param nomsAttendus when names are set to show but not yet: the instant they will; {@code null} otherwise
     * @param vacations    its published shifts, in start order
     */
    @Schema(requiredProperties = {"standId", "standNom", "nominatif", "vacations"})
    public record StandResponsable(
            String standId,
            String standNom,
            String emplacementNom,
            boolean nominatif,
            Instant nomsAttendus,
            List<VacationResponsable> vacations) {}

    /**
     * One shift of a stand: the seats sharing the same window.
     *
     * @param debut     on the calendar
     * @param fin       on the calendar — the next day past midnight
     * @param pourvus   seats somebody holds
     * @param vides     seats nobody holds
     * @param personnes who holds them, first and last name, when the stand is shown by name; always empty otherwise
     */
    @Schema(requiredProperties = {"debut", "fin", "pourvus", "vides", "personnes"})
    public record VacationResponsable(
            LocalDateTime debut, LocalDateTime fin, int pourvus, int vides, List<PersonneVacation> personnes) {}

    /** A name, and nothing else — no address, no phone, no date of birth. */
    @Schema(requiredProperties = {"prenom", "nom"})
    public record PersonneVacation(String prenom, String nom) {}
}
