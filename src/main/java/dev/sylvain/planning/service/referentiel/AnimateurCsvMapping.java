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
 * <p>There is no id field: an id is drawn per edition (ADR 0050), so the same
 * {@code A3} is somebody else in another edition, and a file matches its rows
 * by e-mail, then by name. A column headed « identifiant » is left unmapped.</p>
 *
 * <p>{@code null} means "this field is not in the file", which is not the same
 * as "this field is empty in the file": an unmapped field never touches an
 * animateur who already exists. That distinction is the whole safety of a
 * catch-up import — a file listing three columns must not blank the eight
 * others.</p>
 *
 * @param prenom             first name
 * @param nom                last name
 * @param dateNaissance      birth date — {@code JJ/MM/AAAA} or {@code AAAA-MM-JJ}
 * @param email              contact address, also the espace's second factor
 * @param manager            truthy cell, see {@code AnimateurCsvImportService}
 * @param competences        multi-valued, {@code typologie} or {@code typologie:NIVEAU}
 * @param souhaits           multi-valued typologie ids
 * @param joursIndisponibles multi-valued dates
 * @param telephone          phone number, to call a replacement on the day
 */
public record AnimateurCsvMapping(
        Integer prenom,
        Integer nom,
        Integer dateNaissance,
        Integer email,
        Integer manager,
        Integer competences,
        Integer souhaits,
        Integer joursIndisponibles,
        Integer telephone) {

    /* The field keys of ALIASES, as {@link #match} is asked for them. */
    private static final String FIELD_PRENOM = "prenom";
    private static final String FIELD_NOM = "nom";
    private static final String FIELD_DATE_NAISSANCE = "dateNaissance";
    private static final String FIELD_EMAIL = "email";
    private static final String FIELD_MANAGER = "manager";
    private static final String FIELD_COMPETENCES = "competences";
    private static final String FIELD_SOUHAITS = "souhaits";
    private static final String FIELD_JOURS_INDISPONIBLES = "joursIndisponibles";
    private static final String FIELD_TELEPHONE = "telephone";

    /**
     * The header spellings each field answers to, accents and case removed.
     *
     * <p>This is a <b>proposal</b>, not a contract: the screen shows the
     * mapping it produced and lets the operator change every line of it before
     * anything is read. An imposed header would have been less code and would
     * have failed on the first file whose author called the column
     * « Date de naiss. ».</p>
     */
    private static final Map<String, List<String>> ALIASES = Map.ofEntries(
            Map.entry(FIELD_PRENOM, List.of("prenom", "firstname", "first name", "given name")),
            Map.entry(FIELD_NOM, List.of("nom", "nom de famille", "lastname", "last name", "surname", "name")),
            Map.entry(
                    FIELD_DATE_NAISSANCE,
                    List.of(
                            "date de naissance",
                            "datenaissance",
                            "date naissance",
                            "naissance",
                            "ne le",
                            "nee le",
                            "birthdate",
                            "birth date",
                            "date of birth")),
            Map.entry(
                    FIELD_EMAIL,
                    List.of("email", "e mail", "mail", "adresse mail", "courriel", "adresse electronique")),
            Map.entry(FIELD_MANAGER, List.of("manager", "responsable", "encadrant", "chef")),
            Map.entry(FIELD_COMPETENCES, List.of("competences", "competence", "typologies", "typologie", "skills")),
            Map.entry(FIELD_SOUHAITS, List.of("souhaits", "souhait", "voeux", "preferences", "wishes")),
            Map.entry(
                    FIELD_JOURS_INDISPONIBLES,
                    List.of(
                            "jours indisponibles",
                            "joursindisponibles",
                            "indisponibilites",
                            "indisponibilite",
                            "jours indispo",
                            "indispo",
                            "absences",
                            "jours d absence",
                            "unavailable days")),
            Map.entry(
                    FIELD_TELEPHONE,
                    List.of("telephone", "tel", "portable", "mobile", "numero de telephone", "phone", "phone number")));

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
        List<String> normalised =
                columns.stream().map(AnimateurCsvMapping::normalise).toList();
        boolean[] taken = new boolean[normalised.size()];
        return new AnimateurCsvMapping(
                match(normalised, taken, FIELD_PRENOM),
                match(normalised, taken, FIELD_NOM),
                match(normalised, taken, FIELD_DATE_NAISSANCE),
                match(normalised, taken, FIELD_EMAIL),
                match(normalised, taken, FIELD_MANAGER),
                match(normalised, taken, FIELD_COMPETENCES),
                match(normalised, taken, FIELD_SOUHAITS),
                match(normalised, taken, FIELD_JOURS_INDISPONIBLES),
                match(normalised, taken, FIELD_TELEPHONE));
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
        String withoutAccents =
                Normalizer.normalize(header, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return withoutAccents
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }
}
