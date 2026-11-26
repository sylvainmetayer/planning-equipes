package dev.sylvain.planning.domain;

import java.time.Instant;
import java.time.LocalDate;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * One day marked « relu et accepté » — the reading behind the {@link
 * DayState#RELUE} and {@link DayState#FIGEE} states of a day (ADR 0121).
 * Persistent state (one row per day in {@code validation_journee}), not a
 * journal: deleting the row un-reads the day.
 *
 * <p><b>A reading is not a lock.</b> The frozen state is the
 * {@link TypeVerrouillage#JOUR} lock itself, never a flag here: a reading
 * freezes nothing, and a solve that recomputes the day withdraws it (ADR 0039,
 * 0069). It survives one only where nothing was recomputed — the frozen past,
 * a frozen day, a day a solve started from the plan in place gave back
 * unchanged.</p>
 *
 * @param validePar   the id of the account that validated ({@code "admin"} for the
 *                    configuration's), {@code "mcp"} for an assistant, {@code null}
 *                    when the caller carried no identity — never an address
 * @param commentaire free text the reviewer left, kept as-is
 */
@Schema(requiredProperties = {"id", "jour", "valideLe"})
public record ValidationJournee(String id, LocalDate jour, Instant valideLe, String validePar, String commentaire) {}
