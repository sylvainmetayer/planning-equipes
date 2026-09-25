package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.JoursFeries;
import dev.sylvain.planning.domain.JoursFeries.PublicHoliday;
import dev.sylvain.planning.service.BusinessError;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.LocalDate;
import java.util.List;

/**
 * The public holidays of a range of dates, for the entry screens that do not
 * read the opening report — the Créneaux page, the series preview, the
 * timeslot form. A calendar fact: it reads no edition and writes nothing.
 *
 * <p>The range is bounded because nothing on screen spans more than an
 * edition, and an unbounded one would be a loop an anonymous typo could make
 * as long as it likes.</p>
 */
@ApplicationScoped
public class JoursFeriesService {

    /** The widest range served, in years. */
    static final int PLAGE_MAXIMALE_ANNEES = 2;

    /** The holidays from {@code debut} to {@code fin}, both included; {@code 400} on a missing, inverted or too wide range. */
    public List<PublicHoliday> between(LocalDate debut, LocalDate fin) {
        if (debut == null || fin == null) {
            throw new BusinessError.Invalid("Les paramètres « debut » et « fin » sont requis (AAAA-MM-JJ).");
        }
        if (fin.isBefore(debut)) {
            throw new BusinessError.Invalid("La fin (" + fin + ") précède le début (" + debut + ").");
        }
        if (fin.isAfter(debut.plusYears(PLAGE_MAXIMALE_ANNEES))) {
            throw new BusinessError.Invalid(
                    "Plage trop longue : " + PLAGE_MAXIMALE_ANNEES + " ans au plus entre « debut » et « fin ».");
        }
        return JoursFeries.between(debut, fin);
    }
}
