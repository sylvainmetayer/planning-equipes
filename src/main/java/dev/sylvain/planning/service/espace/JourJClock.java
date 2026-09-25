package dev.sylvain.planning.service.espace;

import dev.sylvain.planning.config.SimulatedClockPermission;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.JdbcEditionScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * The source of "today" for the mode jour J screen, the espace animateur's
 * day marker, the consignes' « jours à venir seulement » — and, since ADR
 * 0044, for the solver's frozen past.
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
 * <p><b>And the espace animateur's day marker</b> — the seat in progress, the
 * next one, the elapsed days folded away. That one is computed in the browser,
 * so the espace view carries {@link #mockedDate()} and the page puts it in
 * place of the phone's date. Left out, a frozen date showed jour J on one day
 * and the espace of the very people it moves on another.</p>
 *
 * <p>The <b>time of day is optional</b>. Left empty, {@link #now()} returns the
 * real wall clock, so a frozen day still sees its timeslots fall behind as the
 * afternoon goes on. Set, the moment is fixed — the same 14:00 on every reload,
 * which is how a seat « en cours » is checked on purpose rather than by waiting
 * for it. A time needs a date: « 14:00 » against the machine's date would be a
 * moment sliding by one day at midnight.</p>
 *
 * <h2>And the solver's past</h2>
 *
 * <p>« Le passé est figé » (ADR 0044): when a problem is built, every seat of
 * a timeslot already started — a date before {@link #today()}, or today's
 * date with a start at or before {@link #now()} — is re-seeded from the
 * persisted plan and pinned, and the constraints count it without reproaching
 * it. The solver used to be deliberately excluded from this clock, on the
 * ground that a mock reaching into it could distort a plan. It reads it now,
 * and that is safe for the same reason the screens are: the mocked value is
 * <b>ignored wherever the permission is not granted</b>, so a production
 * solve always judges the past against the machine's date, whatever the
 * table holds. Where it is granted — {@code quarkus:dev}, a staging server —
 * a frozen date is exactly what lets a solve during the event be rehearsed
 * out of season, and what the acceptance tests of the rule are written on.
 * The kill-switch {@code planning.solver.passe-fige} turns the reading off
 * without touching this class.</p>
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
 *   <li>the scheduled backup, the solver jobs' own timestamps, the espace
 *       animateur codes and every other deadline: they answer to the
 *       machine's clock.</li>
 * </ul>
 *
 * <h2>Why the guard is here and not in the browser</h2>
 *
 * <p>{@code /debug} is an ordinary admin route, reachable on a deployed
 * instance. A mock offered there would make the jour J screen lie about a real
 * event — and, once the day-before reminders ship, send them to real people on
 * the wrong day. Hiding the field client-side would not be a guard: it is this
 * write path that refuses, on any server where {@link SimulatedClockPermission}
 * is not granted — neither {@code quarkus:dev} nor a staging server launched
 * with {@code HORLOGE_SIMULEE_AUTORISEE=true}.</p>
 */
@ApplicationScoped
public class JourJClock {

    /** Single row, mirroring how the manual solve budget is stored (V20). */
    private static final int LIGNE_UNIQUE = 1;

    @Inject
    JdbcEditionScope scope;

    @Inject
    SimulatedClockPermission permission;

    /**
     * The date the mode jour J screen treats as today: the mocked one when a
     * developer has set one on a server allowed to, the machine's otherwise.
     *
     * <p><b>The guard is on the read too, not only on the write.</b> Refusing to
     * <em>set</em> the value where it is not allowed is not enough, because the row is
     * an ordinary one and arrives by paths the application does not police: a
     * {@code pg_dump} restore (ADR 0015), a copied volume, a {@code psql}
     * session. A date frozen on a laptop would stick on the instance that
     * received it — and the way back is deliberately shut, since clearing it is
     * refused there as well. Ignoring the value where it may not be set closes
     * that door: on a production instance this always answers the machine's date,
     * whatever the table holds.</p>
     *
     * <p>The application's own dump is <em>not</em> one of those paths, and
     * never was: {@code horloge_jour_j} is outside
     * {@code DatabaseDumpService.TABLES}, so an export skips it and an import
     * rejects it outright. That exclusion is deliberate — see the comment at
     * the end of that list.</p>
     */
    public LocalDate today() {
        LocalDate fige = mockedDate();
        return fige != null ? fige : LocalDate.now(ZoneId.systemDefault());
    }

    /**
     * The time of day: the mocked one when a developer froze it too, the wall
     * clock otherwise — including under a frozen date alone, which still moves
     * forward hour by hour. Same guard as {@link #today()}.
     */
    public LocalTime now() {
        LocalTime fige = mocked().heure();
        return fige != null ? fige : LocalTime.now(ZoneId.systemDefault()).withNano(0);
    }

    /**
     * Today and the time of day in <b>one</b> reading — for a rule that
     * compares both, such as the solver's frozen past (ADR 0044): two
     * separate calls to {@link #today()} and {@link #now()} could straddle
     * midnight and pair one day with the first minute of the next. Same
     * substitutions as the two of them: a frozen date, a frozen time of day
     * when there is one, the wall clock otherwise.
     */
    public LocalDateTime dateTime() {
        Horloge fige = mocked();
        if (fige.date() == null) {
            return LocalDateTime.now(ZoneId.systemDefault()).withNano(0);
        }
        return LocalDateTime.of(
                fige.date(),
                fige.heure() != null
                        ? fige.heure()
                        : LocalTime.now(ZoneId.systemDefault()).withNano(0));
    }

    /**
     * The frozen date and time of day, each {@code null} when not frozen.
     *
     * @param heure never set without {@code date}: the write refuses it, and so
     *              does the table
     */
    public record Horloge(LocalDate date, LocalTime heure) {

        static final Horloge REELLE = new Horloge(null, null);
    }

    /**
     * The mocked date, or {@code null} when the real one is in use.
     *
     * <p>Always {@code null} where the permission is not granted, whatever the table holds: see
     * {@link #today()} for the dump-replay path that makes this necessary. The
     * row is left alone rather than deleted — this is a read, and a read that
     * quietly repairs data is a read nobody can reason about.</p>
     */
    public LocalDate mockedDate() {
        return mocked().date();
    }

    /**
     * The mocked date and time of day, both {@code null} when the real clock is
     * in use — and always both {@code null} where the permission is not granted, see
     * {@link #today()}.
     */
    public Horloge mocked() {
        if (!permission.isGranted()) {
            return Horloge.REELLE;
        }
        // Not prepareScoped: this table carries no edition_id — it describes the
        // server's clock, not an event.
        return scope.read("Failed to read the mocked date", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT date_du_jour, heure_du_jour FROM horloge_jour_j WHERE id = ?")) {
                ps.setInt(1, LIGNE_UNIQUE);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next()
                            ? new Horloge(
                                    rs.getObject("date_du_jour", LocalDate.class),
                                    rs.getObject("heure_du_jour", LocalTime.class))
                            : Horloge.REELLE;
                }
            }
        });
    }

    /** Whether a developer has frozen the date on this server. */
    public boolean isMocked() {
        return mockedDate() != null;
    }

    /**
     * Freezes the date — and, when {@code heure} is given, the time of day — or
     * hands both back to the machine when {@code date} is {@code null}.
     *
     * @throws BusinessError.Invalid on any server without the permission —
     *                               including the one that matters, production —
     *                               and for a time without a date
     */
    public Horloge setMocked(LocalDate date, LocalTime heure) {
        if (!permission.isGranted()) {
            throw new BusinessError.Invalid("Figer la date du jour n'est possible qu'en mode développement"
                    + " (quarkus:dev) ou sur une instance lancée avec HORLOGE_SIMULEE_AUTORISEE=true."
                    + " Ailleurs, l'écran jour J lit l'horloge réelle et"
                    + " ne peut pas en lire une autre.");
        }
        if (heure != null && date == null) {
            throw new BusinessError.Invalid("Une heure figée demande une date : sans elle, « " + heure
                    + " » se lirait sur la date de la machine, qui change à minuit.");
        }
        scope.write("Failed to save the mocked date", connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO horloge_jour_j (id, date_du_jour, heure_du_jour) VALUES (?, ?, ?)
                    ON CONFLICT (id) DO UPDATE
                    SET date_du_jour = EXCLUDED.date_du_jour, heure_du_jour = EXCLUDED.heure_du_jour""")) {
                ps.setInt(1, LIGNE_UNIQUE);
                ps.setObject(2, date);
                ps.setObject(3, heure);
                ps.executeUpdate();
            }
        });
        return new Horloge(date, heure);
    }

    /** Whether this server would accept a mocked date at all: dev mode, or a staging server that asked. */
    public boolean isModifiable() {
        return permission.isGranted();
    }
}
