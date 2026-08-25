package dev.sylvain.planning.service;

import dev.sylvain.planning.domain.Creneau;

/**
 * What a timeslot must satisfy before it is written, as a pure function:
 * nothing here reads the database, so the rule can be read — and tested — on
 * its own, like {@link StandValidator} next door.
 *
 * <p>Without it a timeslot missing a date or an hour reached the {@code NOT
 * NULL} columns, and the {@link java.sql.SQLException} came back as an
 * {@link IllegalStateException} — a {@code 500} and a Sentry alert for what is
 * only a bad request, the very inversion {@link BusinessError} exists to
 * prevent.</p>
 *
 * <p><b>An end at or before the start is deliberately accepted</b>: that is how
 * the domain writes a timeslot running past midnight —
 * {@link Creneau#getDureeMinutes()} counts 20:00→00:00 as 240 minutes, and only
 * such a timeslot reads a stand window dated the next day. Refusing it here
 * would make the night timeslot unwritable.</p>
 */
final class CreneauValidator {

    private CreneauValidator() {
    }

    static void check(Creneau creneau) {
        if (creneau == null || creneau.getDate() == null || creneau.getHeureDebut() == null
                || creneau.getHeureFin() == null) {
            throw new BusinessError.Invalid(
                    "Un créneau requiert une date, une heure de début et une heure de fin");
        }
    }
}
