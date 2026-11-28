package kyle.poultry.report.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import kyle.poultry.finance.api.model.EntryType;
import kyle.poultry.finance.api.FinanceApi;
import kyle.poultry.finance.api.model.PaymentMethod;
import kyle.poultry.stock.api.StockApi;
import kyle.poultry.stock.api.model.ProductStockView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link DashboardService} with a fixed clock and mocked finance and stock modules (no Spring, no
 * database). They pin down what "today" and "this month" mean around midnight in Manila, which an integration test on
 * the real clock cannot control, and who gets profit figures.
 */
class DashboardServiceTests {
    private static final ZoneId MANILA = ZoneId.of("Asia/Manila");

    FinanceApi finance = mock(FinanceApi.class);
    StockApi stock = mock(StockApi.class);

    /** Every finance call answers all zeros, and the stock module reports two low products. */
    @BeforeEach
    void stubModules() {
        Map<EntryType, BigDecimal> zeros = new EnumMap<>(EntryType.class);
        for (EntryType t : EntryType.values()) zeros.put(t, new BigDecimal("0.00"));
        when(finance.totalsByType(any(), any())).thenReturn(zeros);
        Map<PaymentMethod, BigDecimal> cash = new EnumMap<>(PaymentMethod.class);
        for (PaymentMethod m : PaymentMethod.values()) cash.put(m, new BigDecimal("0.00"));
        when(finance.cashByMethod(any(), any())).thenReturn(cash);
        when(finance.retainedOnCancelledSources(any())).thenReturn(new BigDecimal("12.00"));
        when(stock.balances(null)).thenReturn(List.of());
        when(stock.summary(true)).thenReturn(List.of(mock(ProductStockView.class), mock(ProductStockView.class)));
    }

    /** Builds the service with the clock fixed at the given instant, read in Manila time. */
    private DashboardService serviceAt(String instant) {
        var clock = Clock.fixed(Instant.parse(instant), MANILA);
        return new DashboardService(new ReportService(finance, stock), stock, clock);
    }

    /** 00:30 on 1 April in Manila is still 31 March in UTC; the dashboard must use the Manila date. */
    @Test
    void justAfterManilaMidnightIsAlreadyTheNextDayAndMonth() {
        var d = serviceAt("2026-03-31T16:30:00Z").dashboard(true);

        assertThat(d.asOf()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(d.today().from()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(d.today().to()).isEqualTo(LocalDate.of(2026, 4, 1));
        // On the first of the month the month so far is a single day.
        assertThat(d.monthToDate().from()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(d.monthToDate().to()).isEqualTo(LocalDate.of(2026, 4, 1));
    }

    /** One second before Manila midnight is still the old day and month. */
    @Test
    void lastSecondOfTheMonthStaysInThatMonth() {
        var d = serviceAt("2026-03-31T15:59:59Z").dashboard(true);

        assertThat(d.asOf()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(d.monthToDate().from()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(d.monthToDate().to()).isEqualTo(LocalDate.of(2026, 3, 31));
    }

    /** The month starts on the 1st even in a leap-year February, and today's period is one day. */
    @Test
    void monthToDateStartsOnTheFirst() {
        var d = serviceAt("2028-02-29T05:00:00Z").dashboard(true);

        assertThat(d.today().from()).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(d.monthToDate().from()).isEqualTo(LocalDate.of(2028, 2, 1));
        assertThat(d.monthToDate().to()).isEqualTo(LocalDate.of(2028, 2, 29));
    }

    /** The owner gets profit figures; an assistant gets null and the profit calculation is not even run. */
    @Test
    void profitIsForTheOwnerOnly() {
        var owner = serviceAt("2026-06-10T04:00:00Z").dashboard(true);
        assertThat(owner.today().profit()).isEqualByComparingTo("0.00");
        assertThat(owner.monthToDate().profit()).isEqualByComparingTo("0.00");

        var assistant = serviceAt("2026-06-10T04:00:00Z").dashboard(false);
        assertThat(assistant.today().profit()).isNull();
        assertThat(assistant.monthToDate().profit()).isNull();
        // The ledger is asked for the same periods either way; only profit is skipped.
        assertThat(assistant.today().netSales()).isEqualByComparingTo("0.00");
    }

    /** Stock figures and the retained payment come from the modules that own them. */
    @Test
    void assistantsKeepSafeStockAndRetainedFiguresButNoInventoryValuation() {
        var d = serviceAt("2026-06-10T04:00:00Z").dashboard(false);

        assertThat(d.lowStockProducts()).isEqualTo(2);
        assertThat(d.inventoryValue()).isNull();
        assertThat(d.retainedOnCancelledDeliveries()).isEqualByComparingTo("12.00");
        verify(stock, never()).balances(null);
        // The retained amount is measured as of today.
        verify(finance, atLeastOnce()).retainedOnCancelledSources(LocalDate.of(2026, 6, 10));
        verify(finance, never()).totalsByType(LocalDate.of(2026, 6, 11), LocalDate.of(2026, 6, 11));
    }

    /** PERF-3: the owner dashboard reads each period's totals once and scans for retained money once. */
    @Test
    void ledgerIsQueriedOncePerPeriodAndRetainedMoneyOnce() {
        serviceAt("2026-06-10T04:00:00Z").dashboard(true);

        verify(finance, times(1)).totalsByType(LocalDate.of(2026, 6, 10), LocalDate.of(2026, 6, 10));
        verify(finance, times(1)).totalsByType(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 10));
        verify(finance, times(1)).retainedOnCancelledSources(LocalDate.of(2026, 6, 10));
    }
}
