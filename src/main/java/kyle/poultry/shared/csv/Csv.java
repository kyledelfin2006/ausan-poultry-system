package kyle.poultry.shared.csv;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Builds CSV downloads (RFC 4180 quoting) for the export endpoints of every module. Pure functions, no state.
 *
 * <p>Two choices make the files safe to open in a spreadsheet:
 * <ul>
 *   <li>text that starts with {@code = + - @} (or a tab/CR) is prefixed with an apostrophe, so a user-typed
 *       description such as {@code =HYPERLINK(...)} cannot run as a formula (CSV injection);</li>
 *   <li>the file starts with a UTF-8 byte-order mark, so Excel reads names such as "Dela Peña" correctly.</li>
 * </ul>
 * Numbers are written with {@link BigDecimal#toPlainString()} and are never altered, so they stay numbers.
 */
public final class Csv {
    /** Excel needs this marker to recognise UTF-8; every other reader ignores it. */
    private static final String BOM = "﻿";
    /** CSV lines end with CRLF as RFC 4180 asks. */
    private static final String EOL = "\r\n";

    private Csv() {}

    /**
     * Incrementally builds a CSV document, one row at a time.
     */
    public static final class Builder {
        private final StringBuilder out = new StringBuilder(BOM);

        /**
         * Appends one row.
         *
         * @param cells the cell values: {@code null} becomes empty, numbers are written as they are, everything
         *              else is written as protected text
         * @return this builder
         */
        public Builder row(Object... cells) {
            try {
                writeRow(out, cells);
            } catch (IOException e) {
                throw new UncheckedIOException(e);   // a StringBuilder never throws; this only satisfies the signature
            }
            return this;
        }

        /** {@return the whole document so far} */
        public String build() { return out.toString(); }
    }

    /**
     * Writes one row (cells separated by commas, ending with CRLF) to any sink, so large exports can be written as
     * they are produced instead of being built in memory first. Cells are formatted exactly as in {@link Builder#row}.
     *
     * @param out   where to write
     * @param cells the cell values
     * @throws IOException when the sink fails (for example the client disconnected)
     */
    public static void writeRow(Appendable out, Object... cells) throws IOException {
        // One separator between cells, none after the last.
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) out.append(',');
            out.append(cell(cells[i]));
        }
        out.append(EOL);
    }

    /** {@return the UTF-8 byte-order mark a document must start with so Excel reads it correctly} */
    public static String byteOrderMark() { return BOM; }

    /** {@return a new, empty CSV document (it already contains the byte-order mark)} */
    public static Builder builder() { return new Builder(); }

    /**
     * Formats one cell.
     *
     * @param value the value; {@code null}, a number, or any object (written through {@code toString})
     * @return the cell text, quoted when it contains a comma, quote or line break
     */
    static String cell(Object value) {
        if (value == null) return "";
        // Numbers must stay machine-readable, so they are neither quoted nor prefixed.
        if (value instanceof BigDecimal d) return d.toPlainString();
        if (value instanceof Number n) return n.toString();
        String text = value.toString();
        // Neutralise spreadsheet formulas typed by users.
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) text = "'" + text;
        boolean needsQuotes = text.indexOf(',') >= 0 || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
        return needsQuotes ? '"' + text.replace("\"", "\"\"") + '"' : text;
    }

    /**
     * Wraps a CSV document in a download response.
     *
     * @param filename the suggested file name, e.g. {@code entries-2026-11-01-2026-11-30.csv}
     * @param csv      the document produced by {@link Builder#build()}
     * @return a 200 response with {@code text/csv; charset=UTF-8} and an attachment disposition
     */
    public static ResponseEntity<byte[]> download(String filename, String csv) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Wraps a CSV document that is written while the response is sent. The body must start with
     * {@link #byteOrderMark()}. Once the first byte is out the status can no longer change, so validate the request
     * before calling this.
     *
     * @param filename the suggested file name
     * @param body     writes the document to the response stream
     * @return a 200 response with {@code text/csv; charset=UTF-8} and an attachment disposition
     */
    public static ResponseEntity<StreamingResponseBody> download(String filename, StreamingResponseBody body) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(body);
    }
}
