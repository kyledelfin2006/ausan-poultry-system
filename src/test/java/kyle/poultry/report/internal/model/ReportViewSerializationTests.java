package kyle.poultry.report.internal.model;

import kyle.poultry.report.internal.service.SeriesService;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import kyle.poultry.report.internal.model.Collections;
import kyle.poultry.report.internal.model.Period;
import kyle.poultry.report.internal.model.Sales;
import kyle.poultry.report.internal.model.Summary;
import kyle.poultry.report.internal.model.Series;
import kyle.poultry.report.internal.model.SeriesPoint;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

/** Confirms Jackson omits null owner-only cost fields from the report read models. */
class ReportViewSerializationTests {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void omitsCostAndCashFlowFieldsFromAssistantReadModels() {
        var period = new Period(LocalDate.of(2026, 11, 22), LocalDate.of(2026, 11, 22));
        var summary = new Summary(period, new Sales(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                new Collections(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), null, null, null);
        var point = new SeriesPoint(period.from(), period.to(), BigDecimal.ZERO, BigDecimal.ZERO, null, null, null);
        var series = new Series(period, Granularity.DAY, List.of(point), point);

        String summaryJson = json.writeValueAsString(summary);
        String seriesJson = json.writeValueAsString(series);

        assertThat(summaryJson).contains("sales", "collections").doesNotContain("stockPurchases", "otherExpenses", "cashMovement");
        assertThat(seriesJson).contains("netSales", "netCollected").doesNotContain("stockPurchases", "otherExpenses", "cashMovement");
    }
}
