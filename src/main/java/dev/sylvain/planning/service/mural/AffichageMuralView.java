package dev.sylvain.planning.service.mural;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What the wall display of the control room shows: the stands open on the day
 * under way, each with every shift of that day, and the alerts of the moment.
 *
 * <p>The whole day travels, not only the shift in progress and the next one:
 * the screen picks those two against {@link #now()} and keeps doing so
 * between two reads, and its print version lays the whole day out. Which shift
 * is current is therefore decided on the server's clock, never on the
 * television's.</p>
 *
 * @param edition      the edition's name, for the header
 * @param libelle      the link's own label (« TV PC sécurité »)
 * @param jour         the day under way — the evening that opened a shift
 *                     still running after midnight, until it ends
 * @param now          the server's moment, simulated date included
 * @param nextDay      the next day carrying a timeslot, {@code null} when none
 * @param stands       the stands holding at least one seat that day, by
 *                     location then name
 * @param alerts       what is new since the plan was published — a seat
 *                     opened by an absence —, the shift starting within half
 *                     an hour with an empty seat, the break without relay
 *                     within the hour. The holes the published plan already
 *                     had stay in the tiles, faded, never in the band
 * @param consigne     the consigne of the day, {@code null} when none
 * @param unpublishedChanges people whose schedule differs from the published
 *                     plan: the screen says « peut différer de celui envoyé »
 *                     only when this is not zero, with the number
 */
@Schema(requiredProperties = {"edition", "jour", "now", "stands", "alerts", "unpublishedChanges"})
public record AffichageMuralView(
        String edition,
        String libelle,
        LocalDate jour,
        LocalDateTime now,
        LocalDate nextDay,
        List<MuralStand> stands,
        List<MuralAlert> alerts,
        MuralConsigne consigne,
        int unpublishedChanges) {

    /** One stand of the day, and its shifts in start order. */
    @Schema(requiredProperties = {"standId", "standNom", "vacations"})
    public record MuralStand(
            String standId, String standNom, String emplacementId, String emplacementNom, List<MuralShift> vacations) {}

    /**
     * One shift of a stand: the seats sharing the same window.
     *
     * @param start      on the day it opens
     * @param end        the next day for a shift crossing midnight
     * @param noms       who holds a seat, as the link allows names to be shown
     *                   — disambiguated among the day's holders: a second
     *                   letter of the last name, then the whole of it
     * @param emptySeats seats nobody holds
     * @param newEmptySeats among them, the seats the published plan had
     *                   somebody on — opened since, by an absence: « nouveau »,
     *                   where the others are the holes everybody already knew
     */
    @Schema(requiredProperties = {"start", "end", "noms", "emptySeats", "newEmptySeats"})
    public record MuralShift(
            LocalDateTime start, LocalDateTime end, List<String> noms, int emptySeats, int newEmptySeats) {}

    /** What the bottom band shouts about. */
    public enum MuralAlertType {
        /** Seats opened since the publication — an absence — on a shift not over yet. */
        NEW_EMPTY_SEATS,
        /** A shift starting within half an hour with a seat nobody holds, new or known. */
        STARTING_SOON,
        /** A break owed within the hour on a stand where nobody else can take the relay. */
        BREAK_WITHOUT_RELAY
    }

    /**
     * One alert.
     *
     * @param count empty seats, for {@link MuralAlertType#NEW_EMPTY_SEATS} and
     *              {@link MuralAlertType#STARTING_SOON}
     * @param nom   who takes the break, for {@link MuralAlertType#BREAK_WITHOUT_RELAY}
     */
    @Schema(requiredProperties = {"type", "standNom", "start", "end", "count"})
    public record MuralAlert(
            MuralAlertType type, String standNom, LocalDateTime start, LocalDateTime end, int count, String nom) {}

    /**
     * The band an authority closed that day, as the consigne states it.
     *
     * @param closedUntil end of the band, {@code null} for « until midnight » —
     *                    the consigne's own way of saying it
     */
    @Schema(requiredProperties = {"closedFrom"})
    public record MuralConsigne(LocalTime closedFrom, LocalTime closedUntil, String motif) {}
}
