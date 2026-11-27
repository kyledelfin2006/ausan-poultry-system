package kyle.poultry.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Map;
import java.util.function.Consumer;
import kyle.poultry.finance.EntryType.CashDirection;
import kyle.poultry.finance.FinanceApi.EntryView;
import kyle.poultry.identity.api.model.Actor;
import kyle.poultry.shared.csv.Csv;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Unit test (mocked ledger) that the CSV export reads the ledger with <em>one</em> query. Walking offset-based pages
 * would repeat or skip a row when an entry is recorded between two page reads; a single read cannot. A mock can count the
 * reads exactly, which an integration test cannot do without racing the database.
 */
class EntriesExportSnapshotTests {
    private static final Jwt OWNER = new Jwt("token", Instant.EPOCH, Instant.parse("2026-01-01T00:00:00Z"),
            Map.of("alg", "HS256"), Map.of("sub", "1", "role", "OWNER"));

    /** One read for any number of entries, so there are no page boundaries for a concurrent insert to shift. */
    @Test
    void exportsEverythingWithASingleLedgerRead() throws Exception {
        var exporter = mock(EntryExporter.class);
        var entries = new ArrayList<EntryView>();
        // More than the 1,000 a JSON page could hold.
        for (int i = 1; i <= 1500; i++)
            entries.add(new EntryView(i, EntryType.EXPENSE, CashDirection.OUT, new BigDecimal("10.00"), PaymentMethod.CASH,
                    LocalDate.parse("2026-01-10"), "row " + i, null, null, null, 1, Instant.parse("2026-01-10T01:00:00Z")));
        doAnswer(call -> {
            Consumer<EntryView> sink = call.getArgument(4);
            entries.forEach(sink);
            return null;
        }).when(exporter).forEach(any(), any(), any(), any(Actor.class), any());
        var controller = new FinanceController(mock(FinanceApi.class), exporter, new ExportLimiter(3));

        var response = controller.entriesCsv(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"), null, OWNER);
        var out = new ByteArrayOutputStream();
        response.getBody().writeTo(out);

        // Exactly one read for the whole period, made as the caller.
        verify(exporter, times(1)).forEach(eq(LocalDate.parse("2026-01-01")), eq(LocalDate.parse("2026-01-31")), eq(null),
                eq(new Actor(1, kyle.poultry.identity.api.model.Role.OWNER)), any());
        var text = out.toString(StandardCharsets.UTF_8);
        // Byte-order mark, then header plus 1,500 rows, each once.
        assertThat(text).startsWith(Csv.byteOrderMark() + "id,occurredOn,");
        assertThat(text.split("\r\n")).hasSize(1501);
        assertThat(text).contains(",row 1,").contains(",row 1500,");
    }

    /** A backwards period is refused before streaming starts, so the client still gets a normal 422. */
    @Test
    void refusesABackwardsPeriodBeforeAnyByteIsWritten() {
        var exporter = mock(EntryExporter.class);
        var controller = new FinanceController(mock(FinanceApi.class), exporter, new ExportLimiter(3));

        assertThatThrownBy(() -> controller.entriesCsv(LocalDate.parse("2026-02-01"), LocalDate.parse("2026-01-01"), null, OWNER))
                .isInstanceOf(kyle.poultry.shared.error.RuleViolation.class);
        verifyNoInteractions(exporter);
    }
}
