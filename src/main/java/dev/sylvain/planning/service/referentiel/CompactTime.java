package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.service.BusinessError;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * The hour as a person writes it, rather than as {@link LocalTime} demands it.
 *
 * <p>Nobody types {@code 09:00:00}. They type {@code 9h}, {@code 9h30},
 * {@code 9:00} or plain {@code 9} — and a spreadsheet, asked to save a time
 * column, writes {@code 09:00:00} back. All five mean the same instant of the
 * day, and reading only the canonical one is how a perfectly clear file gets
 * refused line by line.</p>
 *
 * <p>One rule, in one place: the compact vacation line
 * ({@link VacationsLigne}) and the timeslot CSV both read their hours here, so
 * a form accepted by one is accepted by the other.</p>
 */
public final class CompactTime {

    private CompactTime() {}

    /**
     * @throws BusinessError.Invalid when nothing readable comes out of it; the
     *         caller is expected to catch it and add its own context
     */
    public static LocalTime parse(String texte) {
        String valeur = texte == null ? "" : texte.strip().toLowerCase(Locale.ROOT);
        valeur = valeur.replace('h', ':');
        if (valeur.endsWith(":")) {
            valeur = valeur + "00";
        }
        if (valeur.matches("\\d{1,2}")) {
            valeur = valeur + ":00";
        }
        if (valeur.matches("\\d:\\d{2}(:\\d{2})?")) {
            valeur = "0" + valeur;
        }
        try {
            return LocalTime.parse(valeur);
        } catch (DateTimeParseException e) {
            throw new BusinessError.Invalid("Heure illisible « " + (texte == null ? "" : texte.strip())
                    + " » : attendu HH:MM, par exemple 09:00 ou 9h30.");
        }
    }
}
