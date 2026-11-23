package dev.sylvain.planning.service.responsable;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What a responsable de stand reads of one edition (issue #295): the
 * <b>published</b> plan of the stands in their scope, and nothing else.
 *
 * <p>A projection, never the domain: no component of this record, nor of the
 * ones it holds, is an {@code Animateur}, a {@code Stand} or a plan — the
 * {@code ResponsableProjectionStructurelleTest} reads it off the types. What
 * is not a component here cannot leak, whatever a later change of the domain
 * adds to it: a birth date, an address, a phone number, a token.</p>
 *
 * <p>Built from the seats of the scope, <b>never by aggregating over a
 * person</b>: someone's hours, rest days or fairness include their seats
 * elsewhere, and serving them would say how much, hence that, they work
 * elsewhere (#295 §3). What a team member does outside the scope shows as
 * « occupé », with no stand named.</p>
 *
 * @param editionId  the edition read
 * @param editionNom its name, for the header
 * @param publieLe   when the plan read was published; {@code null} while the
 *                   edition has published nothing — every stand then shows
 *                   no shift at all, which the screen says rather than
 *                   « personne n'est prévu »
 * @param stands     the stands of the scope still in the edition, by
 *                   location then name — one of them only when the request
 *                   named it
 * @param equipe     the people holding a seat on a stand shown by name,
 *                   sorted by name; empty when no stand of the scope is
 */
@Schema(requiredProperties = {"editionId", "editionNom", "stands", "equipe"})
public record ResponsableView(
        String editionId,
        String editionNom,
        Instant publieLe,
        List<StandResponsable> stands,
        List<MembreEquipe> equipe) {

    /**
     * One stand of the scope.
     *
     * @param nominatif whether its shifts name who holds them — the edition's
     *                  setting, or the override of a right covering the stand
     * @param vacations its published shifts, in start order
     */
    @Schema(requiredProperties = {"standId", "standNom", "nominatif", "vacations"})
    public record StandResponsable(
            String standId,
            String standNom,
            String emplacementNom,
            boolean nominatif,
            List<VacationResponsable> vacations) {}

    /**
     * One shift of a stand: the seats sharing the same window.
     *
     * @param debut     on the calendar
     * @param fin       on the calendar — the next day past midnight
     * @param pourvus   seats somebody holds
     * @param vides     seats nobody holds
     * @param personnes who holds them, first and last name, when the stand is
     *                  shown by name; always empty otherwise
     */
    @Schema(requiredProperties = {"debut", "fin", "pourvus", "vides", "personnes"})
    public record VacationResponsable(
            LocalDateTime debut, LocalDateTime fin, int pourvus, int vides, List<PersonneVacation> personnes) {}

    /** A name, and nothing else — no address, no phone, no date of birth. */
    @Schema(requiredProperties = {"prenom", "nom"})
    public record PersonneVacation(String prenom, String nom) {}

    /**
     * One person of the team, for the « Mon équipe » view.
     *
     * @param cle    a key for this answer only — never the animateur's id,
     *               which would let two answers be joined to the referential
     * @param plages their seats on the stands shown by name, and the windows
     *               they are taken elsewhere on the same days
     */
    @Schema(requiredProperties = {"cle", "prenom", "nom", "plages"})
    public record MembreEquipe(String cle, String prenom, String nom, List<PlageMembre> plages) {}

    /**
     * A window of a team member's day.
     *
     * @param standNom the stand of the scope they hold it on; {@code null}
     *                 for « occupé » — a seat elsewhere, or on a stand of the
     *                 scope shown by head count, never named
     */
    @Schema(requiredProperties = {"debut", "fin"})
    public record PlageMembre(LocalDateTime debut, LocalDateTime fin, String standNom) {}
}
