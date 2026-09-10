package dev.sylvain.planning.service.referentiel;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which column of the uploaded file feeds which field of an {@link
 * dev.sylvain.planning.domain.Animateur} — by <b>column index</b>, never by
 * header name.
 *
 * <p>An index is what makes the two spreadsheet accidents harmless: a file
 * carrying the same header twice, and a file whose header cell is empty. A
 * name-keyed mapping has to arbitrate both, and whichever way it arbitrates,
 * the operator cannot see it. An index also lets the whole header row be
 * cosmetic: a file with no usable header at all is still mappable, column by
 * column, from the screen.</p>
 *
 * <p>{@code null} means "this field is not in the file", which is not the same
 * as "this field is empty in the file": an unmapped field never touches an
 * animateur who already exists. That distinction is the whole safety of a
 * catch-up import — a file listing three columns must not blank the eight
 * others.</p>
 *
 * @param id                 the animateur's business id, when the file carries one
 * @param prenom             first name
 * @param nom                last name
 * @param dateNaissance      birth date — {@code JJ/MM/AAAA} or {@code AAAA-MM-JJ}
 * @param email              contact address, also the espace's second factor
 * @param manager            truthy cell, see {@code AnimateurCsvImportService}
 * @param competences        multi-valued, {@code typologie} or {@code typologie:NIVEAU}
 * @param souhaits           multi-valued typologie ids
 * @param joursIndisponibles multi-valued dates
 */
public record AnimateurCsvMapping(
        Integer id,
        Integer prenom,
        Integer nom,
        Integer dateNaissance,
        Integer email,
        Integer manager,
        Integer competences,
        Integer souhaits,
        Integer joursIndisponibles) {

    /**
     * The header spellings each field answers to, accents and case removed.
     *
     * <p>This is a <b>proposal</b>, not a contract: the screen shows the
     * mapping it produced and lets the operator change every line of it before
     * anything is read. An imposed header would have been less code and would
     * have failed on the first file whose author called the column
     * « Date de naiss. ».</p>
     */
    private static final Map<String, List<String>> ALIASES = Map.of(
            "id", List.of("id", "identifiant", "matricule", "code"),
            "prenom", List.of("prenom", "firstname", "first name", "given name"),
            "nom", List.of("nom", "nom de famille", "lastname", "last name", "surname", "name"),
            "dateNaissance", List.of("date de naissance", "datenaissance", "date naissance",
                    "naissance", "ne le", "nee le", "birthdate", "birth date", "date of birth"),
            "email", List.of("email", "e mail", "mail", "adresse mail", "courriel", "adresse electronique"),
            "manager", List.of("manager", "responsable", "encadrant", "chef"),
            "competences", List.of("competences", "competence", "typologies", "typologie", "skills"),
            "souhaits", List.of("souhaits", "souhait", "voeux", "preferences", "wishes"),
            "joursIndisponibles", List.of("jours indisponibles", "joursindisponibles", "indisponibilites",
                    "indisponibilite", "jours indispo", "indispo", "absences", "jours d absence",
                    "unavailable days"));

    /**
     * Everything unmapped — what a file whose headers say nothing recognisable
     * starts from, and what the screen sends back when the operator clears
     * every field to remap by hand. That request is honoured as such: only a
     * <b>missing</b> mapping asks {@link #propose} for a guess.
     */
    public static AnimateurCsvMapping empty() {
        return new AnimateurCsvMapping(null, null, null, null, null, null, null, null, null);
    }

    /**
     * The best guess for these headers. The first column matching a field's
     * aliases wins, and a column already taken is not offered twice — a file
     * with both « Nom » and « Nom de famille » must not feed the same field
     * from two places.
     */
    public static AnimateurCsvMapping propose(List<String> columns) {
        List<String> normalised = columns.stream().map(AnimateurCsvMapping::normalise).toList();
        boolean[] taken = new boolean[normalised.size()];
        return new AnimateurCsvMapping(
                match(normalised, taken, "id"),
                match(normalised, taken, "prenom"),
                match(normalised, taken, "nom"),
                match(normalised, taken, "dateNaissance"),
                match(normalised, taken, "email"),
                match(normalised, taken, "manager"),
                match(normalised, taken, "competences"),
                match(normalised, taken, "souhaits"),
                match(normalised, taken, "joursIndisponibles"));
    }

    /**
     * Fields are matched most specific first, so « date de naissance » is not
     * eaten by the « nom » aliases: the order of this list is the arbitration.
     */
    private static Integer match(List<String> columns, boolean[] taken, String field) {
        List<String> aliases = ALIASES.get(field);
        for (int index = 0; index < columns.size(); index++) {
            if (!taken[index] && aliases.contains(columns.get(index))) {
                taken[index] = true;
                return index;
            }
        }
        return null;
    }

    /** Lower case, accents removed, punctuation turned into single spaces. */
    private static String normalise(String header) {
        if (header == null) {
            return "";
        }
        String withoutAccents = Normalizer.normalize(header, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return withoutAccents.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }
}
