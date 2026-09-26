package dev.sylvain.planning.service.referentiel;

import java.util.regex.Pattern;

/**
 * The quote a CSV cell carries so that a spreadsheet reads it as text and
 * never runs it as a formula — and the same quote taken off again when the
 * file comes back.
 *
 * <p>A cell starting with {@code =}, {@code +}, {@code -}, {@code @}, a tab or
 * a carriage return is a formula to Excel and LibreOffice: a name typed in a
 * form must not become one in the organiser's spreadsheet. A leading
 * {@code '} is the mark both read as « this is text ». The browser's
 * « Exporter cette liste » applies the same rule ({@code core/csv-export.ts})
 * and its paste takes the quote off ({@code core/paste-rows.ts}), so the
 * server's files and the browser's say the same thing.</p>
 *
 * <p>Everything this application writes into a CSV is already a string, so a
 * plain number is recognised by its shape: {@code -3} or a negative longitude
 * is a number, not a formula, and travels as it is.</p>
 */
public final class CsvFormulaGuard {

    /** What a spreadsheet runs when a cell starts with it. */
    private static final Pattern FORMULA_START = Pattern.compile("^[=+\\-@\\t\\r]");

    /** A cell {@link #neutralise} wrote: the quote, then a formula start. */
    private static final Pattern GUARDED = Pattern.compile("^'[=+\\-@\\t\\r]");

    /** A negative number as {@code String.valueOf} writes it — exponent included, as a {@code Double} may. */
    private static final Pattern PLAIN_NUMBER = Pattern.compile("-?\\d+(?:[.,]\\d+)?(?:[eE][-+]?\\d+)?");

    private CsvFormulaGuard() {}

    /** The cell behind a quote when a spreadsheet would run it; {@code null} and plain numbers unchanged. */
    public static String neutralise(String value) {
        if (value == null
                || !FORMULA_START.matcher(value).find()
                || PLAIN_NUMBER.matcher(value).matches()) {
            return value;
        }
        return "'" + value;
    }

    /** One leading quote taken off when {@link #neutralise} put it there; anything else unchanged. */
    public static String restore(String value) {
        return value != null && GUARDED.matcher(value).find() ? value.substring(1) : value;
    }
}
