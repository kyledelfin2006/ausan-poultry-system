package kyle.poultry.stock.api.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import kyle.poultry.stock.api.StockApi.BalanceView;
import kyle.poultry.stock.api.StockApi.BranchStockView;
import kyle.poultry.stock.api.StockApi.MovementView;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

/** Confirms Jackson omits null cost fields from the role-filtered stock DTOs. */
class StockViewSerializationTests {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void omitsNullCostsFromStockViews() {
        String balance = json.writeValueAsString(new BalanceView(1, "Chicken", "head", 2, 10, null));
        String branch = json.writeValueAsString(new BranchStockView(2, 10, null, false));
        String movement = json.writeValueAsString(new MovementView(3, 1, 2, MovementType.PURCHASE, 10, null,
                null, LocalDate.of(2026, 11, 22), null, null, null, 4, Instant.parse("2026-11-22T00:00:00Z")));

        assertThat(balance).doesNotContain("averageCost");
        assertThat(branch).doesNotContain("averageCost");
        assertThat(movement).doesNotContain("unitCost");
    }
}
