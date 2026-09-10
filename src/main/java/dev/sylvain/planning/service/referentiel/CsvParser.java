package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.service.BusinessError;
import java.util.ArrayList;
import java.util.List;

/**
 * A hand-written RFC 4180 reader, and deliberately nothing more.
 *
 * <p>The one file format an organiser arrives with is a spreadsheet export,
 * and every such export is a dialect: Excel in a French locale writes
 * {@code ;}, everything else writes {@code ,}; Windows writes CRLF, a Mac
 * export writes LF; a "Save as UTF-8 CSV" prepends a byte-order mark; a cell
 * holding an address holds a separator, so it comes quoted, and a quote inside
 * it comes doubled. Reading those four things wrong is how a working file
 * lands one column to the left without anyone noticing.</p>
 *
 * <p>No dependency was added for this: the parser is a hundred lines and it is
 * pure, so it is unit-tested without a container and without a database. What
 * it does <b>not</b> do is a spreadsheet: an {@code .xlsx} is a ZIP archive,
 * and the caller is expected to tell its user so rather than let this class
 * read {@code PK} as a first column.</p>
 *
 * <p>Every row carries the <b>physical line</b> it started on, counted from 1
 * and counting the newlines a quoted cell contains. That number is the only
 * thing the operator can act on: a report saying "row 34" of a file whose row
 * 12 spans three lines points at the wrong row.</p>
 */
public final class CsvParser {

    /** The separators a spreadsheet export is allowed to have chosen. */
    private static final char[] SEPARATORS = {',', ';', '\t'};

    private static final char DEFAULT_SEPARATOR = ',';

    private CsvParser() {}

    /** One record of the file: where it starts, and its cells in column order. */
    public record Row(int line, List<String> values) {

        /** The cell at that column index, empty when the row is shorter than the header. */
        public String value(int index) {
            return index >= 0 && index < values.size() ? values.get(index) : "";
        }

        /** True when every cell is blank — a spacer line, not data. */
        public boolean blank() {
            return values.stream().allMatch(value -> value.isBlank());
        }
    }

    /** The header row, the data rows, and which separator was recognised. */
    public record Table(char separator, List<String> columns, List<Row> rows) {}

    /**
     * Reads the whole text.
     *
     * @throws BusinessError.Invalid when there is nothing to read at all — an
     *         empty upload is a mistake worth naming, not an import of zero
     *         animateurs
     */
    public static Table parse(String content) {
        String text = stripByteOrderMark(content);
        if (text == null || text.isBlank()) {
            throw new BusinessError.Invalid("Le fichier est vide.");
        }
        char separator = detectSeparator(text);
        List<Row> records = split(text, separator);
        List<Row> data = new ArrayList<>(records);
        Row header = data.isEmpty() ? new Row(1, List.of()) : data.remove(0);
        List<String> columns = header.values().stream().map(String::trim).toList();
        List<Row> rows = data.stream().filter(row -> !row.blank()).toList();
        return new Table(separator, columns, rows);
    }

    /**
     * "Save as CSV UTF-8" prepends U+FEFF, which otherwise glues itself to the
     * first header and makes that column impossible to map by name.
     */
    private static String stripByteOrderMark(String content) {
        return content != null && content.startsWith("\uFEFF") ? content.substring(1) : content;
    }

    /**
     * The most frequent candidate outside quotes wins, over the whole file
     * rather than over the header alone: a header of two columns separated by
     * {@code ;} contains a single {@code ;}, which a comma appearing inside one
     * quoted cell would beat.
     */
    static char detectSeparator(String text) {
        int[] counts = new int[SEPARATORS.length];
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current == '"') {
                quoted = !quoted;
                continue;
            }
            if (quoted) {
                continue;
            }
            for (int candidate = 0; candidate < SEPARATORS.length; candidate++) {
                if (current == SEPARATORS[candidate]) {
                    counts[candidate]++;
                }
            }
        }
        int best = -1;
        for (int candidate = 0; candidate < SEPARATORS.length; candidate++) {
            if (counts[candidate] > 0 && (best < 0 || counts[candidate] > counts[best])) {
                best = candidate;
            }
        }
        return best < 0 ? DEFAULT_SEPARATOR : SEPARATORS[best];
    }

    /** The state machine proper: quotes, doubled quotes, CRLF, LF and a lone CR. */
    private static List<Row> split(String text, char separator) {
        List<Row> rows = new ArrayList<>();
        List<String> values = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        int line = 1;
        int startLine = 1;
        int index = 0;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (quoted) {
                if (current == '"') {
                    if (index + 1 < text.length() && text.charAt(index + 1) == '"') {
                        cell.append('"');
                        index += 2;
                        continue;
                    }
                    quoted = false;
                    index++;
                    continue;
                }
                if (current == '\n') {
                    line++;
                }
                cell.append(current);
                index++;
                continue;
            }
            if (current == '"') {
                quoted = true;
                index++;
                continue;
            }
            if (current == separator) {
                values.add(cell.toString());
                cell.setLength(0);
                index++;
                continue;
            }
            if (current == '\r' || current == '\n') {
                values.add(cell.toString());
                cell.setLength(0);
                rows.add(new Row(startLine, List.copyOf(values)));
                values.clear();
                if (current == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') {
                    index++;
                }
                line++;
                startLine = line;
                index++;
                continue;
            }
            cell.append(current);
            index++;
        }
        if (cell.length() > 0 || !values.isEmpty()) {
            values.add(cell.toString());
            rows.add(new Row(startLine, List.copyOf(values)));
        }
        return rows;
    }
}
