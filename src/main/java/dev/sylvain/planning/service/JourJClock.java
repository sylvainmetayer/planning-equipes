package dev.sylvain.planning.service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalTime;

import dev.sylvain.planning.config.DevMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The source of "today" for the mode jour J screen — and for nothing else.
 *
 * <h2>Why it exists</h2>
 *
 * <p>That screen is entirely about what is still ahead <b>today</b>, so it can
 * only be exercised on the day of the event. Waiting for that day to find out
 * whether it works is not a test strategy. A fake {@code Date.now()} in the
 * browser would not do either: the screen deliberately reads the reference
 * moment from the server, and the scope of an absence is computed server-side
 * from the timeslots of that day. What has to be substitutable is therefore
 * this, the server's own notion of the date.</p>
 *
 * <h2>What it replaces, exactly</h2>
 *
 * <p><b>The date, and only for the mode jour J screen.</b> Concretely, the
 * three questions that screen asks: which day is being looked at, which of its
 * timeslots are still ahead, and which ones an absence covers.</p>
 *
 * <p>The <b>time of day is never mocked</b> — {@link #now()} always returns the
 * real wall clock. Freezing an hour as well would make the screen static, when
 * what it is being tested for is precisely that timeslots fall behind as the
 * afternoon goes on.</p>
 *
 * <p>Deliberately left alone, and none of them route through here:</p>
 *
 * <ul>
 *   <li><b>minor/adult status</b>, derived from the birth date against the
 *       <em>timeslot's</em> date rather than against today — a mock reaching
 *       into it could let the solver work a 15-year-old at night;</li>
 *   <li><b>the trace of what was written</b> ({@code creeLe} on an ad hoc
 *       exception, publication and snapshot timestamps): those record when
 *       something really happened, and a record that lies about that is worse
 *       than no record;</li>
 *   <li>the scheduled backup, the solver jobs, the espace animateur codes and
 *       every other deadline: they answer to the machine's clock.</li>
 * </ul>
 *
 * <h2>Why the guard is here and not in the browser</h2>
 *
 * <p>{@code /debug} is an ordinary admin route, reachable on a deployed
 * instance. A mock offered there would make the jour J screen lie about a real
 * event — and, once the day-before reminders ship, send them to real people on
 * the wrong day. Hiding the field client-side would not be a guard: it is this
 * write path that refuses, on any server not launched with
 * {@code quarkus:dev}.</p>
 */
@ApplicationScoped
public class JourJClock {

    /** Single row, mirroring how the manual solve budget is stored (V20). */
    private static final int LIGNE_UNIQUE = 1;

    @Inject
    JdbcEditionScope scope;

    @Inject
    DevMode devMode;

    /**
     * The date the mode jour J screen treats as today: the mocked one when a
     * developer has set one, the machine's otherwise.
     */
    public LocalDate today() {
        LocalDate fige = mockedDate();
        return fige != null ? fige : LocalDate.now();
    }

    /**
     * The time of day, always real. See the class javadoc: only the date is
     * ever substituted, so a frozen day still moves forward hour by hour.
     */
    public LocalTime now() {
        return LocalTime.now().withNano(0);
    }

    /** The mocked date, or {@code null} when the real one is in use. */
    public LocalDate mockedDate() {
        // Not prepareScoped: this table carries no edition_id — it describes the
        // server's clock, not an event.
        return scope.read("Failed to read the mocked date", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT date_du_jour FROM horloge_jour_j WHERE id = ?")) {
                ps.setInt(1, LIGNE_UNIQUE);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getObject("date_du_jour", LocalDate.class) : null;
                }
            }
        });
    }

    /** Whether a developer has frozen the date on this server. */
    public boolean isMocked() {
        return mockedDate() != null;
    }

    /**
     * Freezes the date the mode jour J screen reads, or hands it back to the
     * machine when {@code date} is {@code null}.
     *
     * @throws BusinessError.Invalid on any server not launched with
     *                               {@code quarkus:dev} — including the one that
     *                               matters, a deployed instance
     */
    public LocalDate setMockedDate(LocalDate date) {
        if (!devMode.isActive()) {
            throw new BusinessError.Invalid("Figer la date du jour n'est possible qu'en mode développement"
                    + " (quarkus:dev). Sur une instance déployée, l'écran jour J lit l'horloge réelle et"
                    + " ne peut pas en lire une autre.");
        }
        scope.write("Failed to save the mocked date", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    """
                    INSERT INTO horloge_jour_j (id, date_du_jour) VALUES (?, ?)
                    ON CONFLICT (id) DO UPDATE SET date_du_jour = EXCLUDED.date_du_jour""")) {
                ps.setInt(1, LIGNE_UNIQUE);
                ps.setObject(2, date);
                ps.executeUpdate();
            }
        });
        return date;
    }

    /** Whether this server would accept a mocked date at all. */
    public boolean isModifiable() {
        return devMode.isActive();
    }
}
