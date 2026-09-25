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
 * @param alerts       empty seats within two hours, breaks without relay
 * @param consigne     the consigne of the day, {@code null} when none
 */
@Schema(requiredProperties = {"edition", "jour", "now", "stands", "alerts"})
public record AffichageMuralView(
        String edition,
        String libelle,
        LocalDate jour,
        LocalDateTime now,
        LocalDate nextDay,
        List<MuralStand> stands,
        List<MuralAlert> alerts,
        MuralConsigne consigne) {

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
     * @param emptySeats seats nobody holds
     */
    @Schema(requiredProperties = {"start", "end", "noms", "emptySeats"})
    public record MuralShift(LocalDateTime start, LocalDateTime end, List<String> noms, int emptySeats) {}

    /** What the bottom band shouts about. */
    public enum MuralAlertType {
        /** Seats nobody holds on a shift under way or starting within two hours. */
        EMPTY_SEATS,
        /** A break owed on a stand where nobody else can take the relay. */
        BREAK_WITHOUT_RELAY
    }

    /**
     * One alert.
     *
     * @param count empty seats, for {@link MuralAlertType#EMPTY_SEATS}
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
