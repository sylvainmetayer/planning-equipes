package dev.sylvain.planning.api;

import dev.sylvain.planning.service.BusinessError;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * A day passed as a query parameter ({@code ?du=2026-07-11}), read the same way
 * by every resource that takes one: blank is no bound, anything else must be
 * {@code AAAA-MM-JJ} or the request is refused in 400 naming the parameter.
 */
final class DateQueryParam {

    private DateQueryParam() {}

    static LocalDate parse(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException _) {
            throw new BusinessError.Invalid(
                    "Paramètre « " + name + " » illisible : " + value + " (attendu AAAA-MM-JJ)");
        }
    }
}
