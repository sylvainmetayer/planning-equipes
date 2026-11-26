package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.VacationType;
import dev.sylvain.planning.service.BusinessError;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The vacations of a day template on one line — « 09:00-12:00, 12:00-13:00,
 * 13:00-14:00, 14:00-20:00 » — the form an assistant types over MCP and a
 * scenario comment quotes. The screen
 * sends the structured list; this is the same vocabulary for the caller who
 * has a sentence rather than a form.
 */
public final class VacationsLigne {

    private VacationsLigne() {}

    public static List<VacationType> parse(String ligne) {
        if (ligne == null || ligne.isBlank()) {
            throw new BusinessError.Invalid(
                    "vacations est requis, ex. « 09:00-12:00, 12:00-13:00, 13:00-14:00, 14:00-20:00 »");
        }
        List<VacationType> vacations = new ArrayList<>();
        for (String morceau : ligne.split("[,;]")) {
            String vacation = morceau.strip();
            if (vacation.isEmpty()) {
                continue;
            }
            int separateur = vacation.indexOf('-');
            if (separateur <= 0 || separateur == vacation.length() - 1) {
                throw new BusinessError.Invalid(
                        "Vacation invalide « " + morceau.strip() + " » : attendu « HH:MM-HH:MM »");
            }
            vacations.add(new VacationType(
                    heure(vacation.substring(0, separateur), morceau),
                    heure(vacation.substring(separateur + 1), morceau)));
        }
        if (vacations.isEmpty()) {
            throw new BusinessError.Invalid("Aucune vacation lue dans « " + ligne + " »");
        }
        return vacations;
    }

    /** The inverse: what {@link #parse} reads, for the views an assistant gets back. */
    public static String format(List<VacationType> vacations) {
        StringBuilder ligne = new StringBuilder();
        for (VacationType vacation : vacations) {
            if (!ligne.isEmpty()) {
                ligne.append(", ");
            }
            ligne.append(court(vacation.heureDebut())).append('-').append(court(vacation.heureFin()));
        }
        return ligne.toString();
    }

    private static String court(LocalTime heure) {
        return String.format("%02d:%02d", heure.getHour(), heure.getMinute());
    }

    /** The lenient forms are {@link CompactTime}'s; only the sentence is this class's. */
    private static LocalTime heure(String texte, String morceau) {
        try {
            return CompactTime.parse(texte);
        } catch (BusinessError.Invalid _) {
            throw new BusinessError.Invalid("Vacation invalide « " + morceau.strip() + " » : heure « " + texte.strip()
                    + "» illisible, attendu HH:MM");
        }
    }
}
