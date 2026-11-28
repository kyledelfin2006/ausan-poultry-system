package kyle.poultry.report.internal.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import kyle.poultry.stock.api.StockApi;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The dashboard.
 *
 * @param asOf                          today's business date
 * @param today                         figures for today
 * @param monthToDate                   figures from the first of this month to today
 * @param inventoryValue                value of all stock on hand now (quantity x average cost); {@code null} for an assistant
 * @param lowStockProducts              how many products are low (see {@link StockApi#summary(boolean)})
 * @param retainedOnCancelledDeliveries money still held for cancelled deliveries as of today
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Dashboard(LocalDate asOf, Figures today, Figures monthToDate, BigDecimal inventoryValue, int lowStockProducts,
                        BigDecimal retainedOnCancelledDeliveries) {}
