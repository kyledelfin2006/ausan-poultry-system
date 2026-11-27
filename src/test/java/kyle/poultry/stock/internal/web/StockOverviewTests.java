package kyle.poultry.stock.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kyle.poultry.ApiTestClient;
import kyle.poultry.TestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * HTTP tests of the stock overview against a real PostgreSQL: the all-branch summary, the low-stock rule (at or below
 * the reorder level, per branch) and the owner-only reorder-level endpoints. Each test builds its own products and
 * branches and removes the reorder levels it set, so the shared database stays free of leftover "low" products.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class StockOverviewTests {

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    ApiTestClient api;
    String owner, assistant;
    long branchA, branchB, feed, water;

    /** Fresh branches and two products per test; both products start with no stock and no reorder level. */
    @BeforeEach
    void freshData() {
        api = new ApiTestClient(port);
        owner = api.owner();
        assistant = api.assistant();
        String tag = UUID.randomUUID().toString();
        branchA = jdbc.queryForObject("insert into branch (name) values (?) returning id", Long.class, "a-" + tag);
        branchB = jdbc.queryForObject("insert into branch (name) values (?) returning id", Long.class, "b-" + tag);
        feed = jdbc.queryForObject("insert into product (name, unit) values (?, 'bag') returning id", Long.class, "Zfeed-" + tag);
        water = jdbc.queryForObject("insert into product (name, unit) values (?, 'pc') returning id", Long.class, "Awater-" + tag);
    }

    /** Removes the reorder levels this test created so no product stays flagged in the shared database. */
    @AfterEach
    void removeLevels() {
        jdbc.update("delete from stock_reorder_level where product_id in (?, ?)", feed, water);
    }

    /** Records an opening-stock movement as the owner. */
    void stock(long product, long branch, int qty) {
        var r = api.call("POST", "/api/stock/movements", owner, """
                {"productId":%d,"branchId":%d,"type":"OPENING","quantityDelta":%d,"occurredOn":"2021-01-02"}""".formatted(product, branch, qty));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
    }

    /** Sets a reorder level over HTTP as the owner. */
    void level(long product, long level) {
        var r = api.call("PUT", "/api/stock/reorder-levels/" + product, owner, "{\"level\":" + level + "}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    }

    /** Finds one product in a summary response, or null when it is not listed. */
    static Map<String, Object> product(HttpResponse<String> r, long productId) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        List<Map<String, Object>> found = JsonPath.read(r.body(), "$[?(@.productId == " + productId + ")]");
        return found.isEmpty() ? null : found.getFirst();
    }

    /** The branch rows of a summary entry, as maps. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> branches(Map<String, Object> product) { return (List<Map<String, Object>>) product.get("branches"); }

    // ---- summary and the low-stock rule ----------------------------------------------------------------------------

    /** Totals add the branches and every branch is listed with its quantity and average cost. */
    @Test
    void summaryAddsUpBranchesAndListsEachOne() {
        stock(feed, branchA, 30);
        stock(feed, branchB, 12);

        var p = product(api.call("GET", "/api/stock/summary", assistant, null), feed);

        assertThat(p.get("productName").toString()).startsWith("Zfeed-");
        assertThat(p.get("unit")).isEqualTo("bag");
        assertThat(((Number) p.get("totalQuantity")).longValue()).isEqualTo(42);
        assertThat(p.get("reorderLevel")).isNull();
        assertThat(p.get("low")).isEqualTo(false);
        assertThat(branches(p)).extracting(b -> ((Number) b.get("branchId")).longValue()).containsExactly(branchA, branchB);
        assertThat(branches(p)).extracting(b -> ((Number) b.get("quantity")).longValue()).containsExactly(30L, 12L);
    }

    /** A branch is low at exactly the reorder level and above it is not; the product is low when any branch is. */
    @Test
    void aBranchIsLowAtOrBelowTheLevelAndTheProductWhenAnyBranchIs() {
        stock(feed, branchA, 10);   // exactly at the level
        stock(feed, branchB, 11);   // one above
        level(feed, 10);

        var p = product(api.call("GET", "/api/stock/summary", assistant, null), feed);

        assertThat(((Number) p.get("reorderLevel")).longValue()).isEqualTo(10);
        assertThat(branches(p)).extracting(b -> b.get("low")).containsExactly(true, false);
        assertThat(p.get("low")).isEqualTo(true);

        // Lower the level by one: nothing is at or below it any more.
        level(feed, 9);
        var after = product(api.call("GET", "/api/stock/summary", assistant, null), feed);
        assertThat(branches(after)).extracting(b -> b.get("low")).containsExactly(false, false);
        assertThat(after.get("low")).isEqualTo(false);
    }

    /** A level of 0 flags a branch that has run out completely, but not one that still has stock. */
    @Test
    void levelZeroFlagsOnlyAnEmptyBranch() {
        stock(feed, branchA, 5);
        stock(feed, branchB, 5);
        var usage = api.call("POST", "/api/stock/movements", owner, """
                {"productId":%d,"branchId":%d,"type":"USAGE","quantityDelta":-5,"reason":"fed","occurredOn":"2021-01-03"}"""
                .formatted(feed, branchB));
        assertThat(usage.statusCode()).isEqualTo(201);
        level(feed, 0);

        var p = product(api.call("GET", "/api/stock/summary", assistant, null), feed);

        assertThat(branches(p)).extracting(b -> b.get("low")).containsExactly(false, true);
        assertThat(p.get("low")).isEqualTo(true);
    }

    /** A product with a level that no branch ever held is low, and listed with no branches. */
    @Test
    void aProductWithALevelAndNoStockAnywhereIsLow() {
        level(water, 3);

        var p = product(api.call("GET", "/api/stock/summary", assistant, null), water);

        assertThat(p).isNotNull();
        assertThat(((Number) p.get("totalQuantity")).longValue()).isZero();
        assertThat(branches(p)).isEmpty();
        assertThat(p.get("low")).isEqualTo(true);
    }

    /** Without a level a product is never low, however empty; with {@code lowOnly} only low products come back. */
    @Test
    void lowOnlyKeepsJustTheLowProducts() {
        stock(feed, branchA, 2);       // no level: never low
        stock(water, branchA, 2);
        level(water, 5);               // low

        var low = api.call("GET", "/api/stock/summary?lowOnly=true", assistant, null);

        assertThat(product(low, water)).isNotNull();
        assertThat(product(low, feed)).isNull();
        var all = api.call("GET", "/api/stock/summary", assistant, null);
        assertThat(product(all, feed)).isNotNull();
        // Products come ordered by name: "Awater-..." before "Zfeed-...".
        List<String> names = JsonPath.read(all.body(), "$[*].productName");
        assertThat(names.indexOf(product(all, water).get("productName"))).isLessThan(names.indexOf(product(all, feed).get("productName")));
    }

    /** A retired product disappears once its stock is gone, but stays visible while some branch still holds it. */
    @Test
    void inactiveProductsAreListedOnlyWhileTheyHoldStock() {
        stock(feed, branchA, 4);
        jdbc.update("update product set active = false where id in (?, ?)", feed, water);

        var r = api.call("GET", "/api/stock/summary", assistant, null);

        assertThat(product(r, feed)).isNotNull();
        assertThat(product(r, water)).isNull();
    }

    /** The summary needs a token and is open to assistants. */
    @Test
    void summaryRequiresAuthentication() {
        assertThat(api.call("GET", "/api/stock/summary", null, null).statusCode()).isEqualTo(401);
        assertThat(api.call("GET", "/api/stock/summary", assistant, null).statusCode()).isEqualTo(200);
        assertThat(api.call("GET", "/api/stock/reorder-levels", null, null).statusCode()).isEqualTo(401);
    }

    // ---- reorder-level endpoints -----------------------------------------------------------------------------------

    /** Set, replace, list and clear a level; the list shows the product name and who changed it last. */
    @Test
    void ownerSetsReplacesListsAndClearsALevel() {
        level(feed, 7);
        level(feed, 12);   // replaces, not a second row

        var list = api.call("GET", "/api/stock/reorder-levels", assistant, null);
        assertThat(list.statusCode()).isEqualTo(200);
        List<Map<String, Object>> mine = JsonPath.read(list.body(), "$[?(@.productId == " + feed + ")]");
        assertThat(mine).hasSize(1);
        assertThat(((Number) mine.getFirst().get("level")).longValue()).isEqualTo(12);
        assertThat(mine.getFirst().get("productName").toString()).startsWith("Zfeed-");
        assertThat(mine.getFirst().get("updatedAt")).isNotNull();

        var cleared = api.call("DELETE", "/api/stock/reorder-levels/" + feed, owner, null);
        assertThat(cleared.statusCode()).isEqualTo(204);
        // An active product stays listed, now without a level and therefore not low.
        var afterClear = product(api.call("GET", "/api/stock/summary", assistant, null), feed);
        assertThat(afterClear.get("reorderLevel")).isNull();
        assertThat(afterClear.get("low")).isEqualTo(false);
        // Clearing again: there is nothing to clear.
        assertThat(api.call("DELETE", "/api/stock/reorder-levels/" + feed, owner, null).statusCode()).isEqualTo(404);
    }

    /** Only the owner may change levels; an assistant gets 403 on both writes and the level is untouched. */
    @Test
    void assistantCannotChangeLevels() {
        level(feed, 7);

        assertThat(api.call("PUT", "/api/stock/reorder-levels/" + feed, assistant, "{\"level\":1}").statusCode()).isEqualTo(403);
        assertThat(api.call("DELETE", "/api/stock/reorder-levels/" + feed, assistant, null).statusCode()).isEqualTo(403);
        assertThat(jdbc.queryForObject("select reorder_level from stock_reorder_level where product_id = ?", Long.class, feed)).isEqualTo(7L);
    }

    /** Bad input is refused: negative or missing level (400), unknown product (404), retired product (422). */
    @Test
    void invalidLevelsAreRejected() {
        assertThat(api.call("PUT", "/api/stock/reorder-levels/" + feed, owner, "{\"level\":-1}").statusCode()).isEqualTo(400);
        assertThat(api.call("PUT", "/api/stock/reorder-levels/" + feed, owner, "{}").statusCode()).isEqualTo(400);
        assertThat(api.call("PUT", "/api/stock/reorder-levels/999999999", owner, "{\"level\":1}").statusCode()).isEqualTo(404);
        jdbc.update("update product set active = false where id = ?", feed);
        assertThat(api.call("PUT", "/api/stock/reorder-levels/" + feed, owner, "{\"level\":1}").statusCode()).isEqualTo(422);
        assertThat(api.call("PUT", "/api/stock/reorder-levels/" + feed, null, "{\"level\":1}").statusCode()).isEqualTo(401);
    }
}
