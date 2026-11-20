package kyle.poultry.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * Plain unit tests of {@link Csv}: quoting, formula protection, numbers, byte-order mark and download headers.
 */
class CsvTests {

    /** Quotes only when needed and doubles inner quotes, as RFC 4180 asks. */
    @Test
    void quotesCellsWithCommasQuotesAndLineBreaks() {
        assertThat(Csv.cell("plain")).isEqualTo("plain");
        assertThat(Csv.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(Csv.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(Csv.cell("two\nlines")).isEqualTo("\"two\nlines\"");
        assertThat(Csv.cell(null)).isEmpty();
    }

    /** Text a user typed that a spreadsheet would run as a formula is neutralised with a leading apostrophe. */
    @Test
    void neutralisesSpreadsheetFormulas() {
        assertThat(Csv.cell("=HYPERLINK(\"http://evil\")")).startsWith("\"'=HYPERLINK");
        assertThat(Csv.cell("+1")).isEqualTo("'+1");
        assertThat(Csv.cell("-2")).isEqualTo("'-2");
        assertThat(Csv.cell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        // A dash inside the text is harmless and left alone.
        assertThat(Csv.cell("a-b")).isEqualTo("a-b");
    }

    /** Numbers are never quoted or prefixed, so a negative amount stays a number; BigDecimal never uses exponents. */
    @Test
    void writesNumbersAsNumbers() {
        assertThat(Csv.cell(new BigDecimal("-1000.00"))).isEqualTo("-1000.00");
        assertThat(Csv.cell(new BigDecimal("0E-2"))).isEqualTo("0.00");
        assertThat(Csv.cell(42L)).isEqualTo("42");
    }

    /** The document starts with the byte-order mark and rows end with CRLF. */
    @Test
    void buildsRowsWithBomAndCrlf() {
        var text = Csv.builder().row("a", "b").row(1, null).build();

        assertThat(text).isEqualTo("﻿a,b\r\n1,\r\n");
    }

    /** The response is a UTF-8 CSV attachment carrying the BOM bytes and non-ASCII names intact. */
    @Test
    void downloadIsAnAttachmentInUtf8() {
        var response = Csv.download("x.csv", Csv.builder().row("Dela Peña").build());

        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/csv").containsIgnoringCase("utf-8");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("attachment").contains("x.csv");
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).contains("Dela Peña");
        assertThat(response.getBody()).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
    }
}
