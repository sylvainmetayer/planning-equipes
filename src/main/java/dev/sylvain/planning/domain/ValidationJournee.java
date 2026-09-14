package dev.sylvain.planning.domain;

import java.time.Instant;
import java.time.LocalDate;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * One day marked « relu et accepté ». Persistent
 * state (one row per validation in {@code validation_journee}), not a journal:
 * deleting the row un-validates.
 *
 * <p><b>A validation is not a lock, and that is its whole point.</b> A lock is
 * a mechanism the solver obeys: it freezes seats, it says nothing about anyone
 * having read them. A validation says a human went over that day and accepted
 * it, and freezes nothing at all — the screen offers to lay a lock down at the
 * same time, and never does it on its own.</p>
 *
 * <p>The two nonetheless meet once: a solve that moves a seat of a validated
 * day <b>withdraws</b> the validation, since nobody has read what the solver
 * just wrote — unless that day also carries a {@link TypeVerrouillage#JOUR}
 * lock, in which case the reading still describes what is there.</p>
 *
 * @param validePar   the admin account's principal name, {@code null} when the
 *                    caller carried no identity (an assistant over MCP). Named
 *                    users will fill it without migrating what is already
 *                    written
 * @param commentaire free text the reviewer left, kept as-is
 */
@Schema(requiredProperties = {"id", "jour", "valideLe"})
public record ValidationJournee(String id, LocalDate jour, Instant valideLe, String validePar, String commentaire) {}
