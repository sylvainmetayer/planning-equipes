package dev.sylvain.planning.api;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;

/**
 * The one door a CSV leaves the application through, so that what an organiser
 * opens is decided once rather than three times.
 *
 * <p>Every file goes out with a <b>byte order mark</b>. Excel ignores the
 * {@code charset=utf-8} of the response when it opens a file that has been
 * saved to disk — the header travelled with the download, not with the file —
 * and falls back to the system's legacy code page: an accented name then shows
 * as {@code Métayer}. The mark is three bytes that say « this is UTF-8 », and
 * it is the only thing Excel reads. LibreOffice does not need it and is not
 * disturbed by it.</p>
 *
 * <p>It is safe on the files that come back to us: {@code CsvParser} strips a
 * leading mark before reading the header, so a template downloaded and
 * re-uploaded untouched imports exactly as it did.</p>
 */
final class CsvDownload {

    /** Written as an escape on purpose: an invisible character in the source is a character that gets lost. */
    private static final String BOM = "\uFEFF";

    private CsvDownload() {}

    /** The response that makes a browser save {@code fileName}, mark included. */
    static Response attachment(String csv, String fileName) {
        return Response.ok(BOM + csv)
                .type("text/csv; charset=utf-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .build();
    }
}
