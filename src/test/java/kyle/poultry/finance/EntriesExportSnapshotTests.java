package kyle.poultry.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Map;
import kyle.poultry.finance.EntryType.CashDirection;
import kyle.poultry.finance.FinanceApi.EntryView;
import kyle.poultry.identity.Actor;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
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
    void exportsEverythingWithASingleLedgerRead() {
        var finance = mock(FinanceApi.class);
        var entries = new ArrayList<EntryView>();
        // More than the 1,000 a JSON page could hold.
        for (int i = 1; i <= 1500; i++)
            entries.add(new EntryView(i, EntryType.EXPENSE, CashDirection.OUT, new BigDecimal("10.00"), PaymentMethod.CASH,
                    LocalDate.parse("2026-01-10"), "row " + i, null, null, null, 1, Instant.parse("2026-01-10T01:00:00Z")));
        when(finance.entries(any(), any(), any(), any(Pageable.class), any(Actor.class))).thenReturn(new PageImpl<>(entries));
        var controller = new FinanceController(finance);

        var response = controller.entriesCsv(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"), null, OWNER);

        // Exactly one read, and it is the unpaged one.
        verify(finance, times(1)).entries(any(), any(), any(), any(Pageable.class), any(Actor.class));
        verify(finance).entries(eq(LocalDate.parse("2026-01-01")), eq(LocalDate.parse("2026-01-31")), eq(null),
                eq(Pageable.unpaged()), eq(new Actor(1, kyle.poultry.identity.Role.OWNER)));
        var text = new String(response.getBody(), StandardCharsets.UTF_8);
        // Header plus 1,500 rows, each once.
        assertThat(text.split("\r\n")).hasSize(1501);
        assertThat(text).contains(",row 1,").contains(",row 1500,");
    }
}
