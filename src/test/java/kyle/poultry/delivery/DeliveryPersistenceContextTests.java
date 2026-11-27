package kyle.poultry.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import kyle.poultry.ApiTestClient;
import kyle.poultry.TestcontainersConfiguration;
import kyle.poultry.identity.Actor;
import kyle.poultry.identity.Role;
import kyle.poultry.shared.reference.SourceRef;
import kyle.poultry.stock.StockApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Evidence for audit item DBG-3: stock writes use {@code @Modifying(flushAutomatically = true, clearAutomatically = true)},
 * which writes pending changes and then <em>detaches every entity</em> in the transaction. These tests pin what that
 * means for a delivery that is loaded, passed through a stock call and used again, exactly the shape of
 * {@code DeliveryService.cancel}:
 * <ul>
 *   <li>a change made BEFORE the stock call is written (the flush comes first), which is why {@code cancel} works;</li>
 *   <li>a change made AFTER the stock call is silently lost (no error, no SQL), which is the latent trap;</li>
 *   <li>the delivery's lines stay readable after the clear, because they are loaded EAGERLY on purpose.</li>
 * </ul>
 * Each test reads the database row back with plain SQL, so it observes what was really written, not the cached entity.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class DeliveryPersistenceContextTests {
    private static final ZoneId MANILA = ZoneId.of("Asia/Manila");

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DeliveryRepository deliveries;
    @Autowired StockApi stock;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @PersistenceContext EntityManager em;

    ApiTestClient api;
    String owner;
    Actor ownerActor;
    long branch, pkg;

    /** A branch with stock and a one-line package; the owner as the acting user. */
    @BeforeEach
    void freshData() {
        api = new ApiTestClient(port);
        owner = api.owner();
        long ownerId = jdbc.queryForObject("select id from app_user where username = ?", Long.class,
                TestcontainersConfiguration.OWNER_USERNAME);
        ownerActor = new Actor(ownerId, Role.OWNER);
        String tag = UUID.randomUUID().toString();
        branch = jdbc.queryForObject("insert into branch (name) values (?) returning id", Long.class, "ctx-" + tag);
        long product = jdbc.queryForObject("insert into product (name, unit) values (?, 'head') returning id", Long.class, "ctx-" + tag);
        pkg = jdbc.queryForObject("insert into sales_package (sku, name, price) values (?, ?, 100) returning id", Long.class,
                "CTX-" + tag, "Ctx " + tag);
        jdbc.update("insert into package_item (package_id, product_id, quantity) values (?, ?, 1)", pkg, product);
        var bought = api.call("POST", "/api/stock/movements", owner, """
                {"productId":%d,"branchId":%d,"type":"PURCHASE","quantityDelta":100,"unitCost":10.00,"paymentMethod":"CASH","occurredOn":"2021-01-02"}"""
                .formatted(product, branch));
        assertThat(bought.statusCode()).isEqualTo(201);
    }

    /** Creates an ACTIVE delivery (with a stock-out) through the API and returns its id. */
    private long activeDelivery() {
        var r = api.call("POST", "/api/deliveries", owner, """
                {"customerName":"Ctx","driverName":"Pedro","fromBranchId":%d,"destination":"Davao",
                 "paymentMethod":"CASH","lines":[{"packageId":%d,"quantity":1}]}""".formatted(branch, pkg));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return ((Number) JsonPath.read(r.body(), "$.id")).longValue();
    }

    private String statusInDatabase(long id) {
        return jdbc.queryForObject("select status from delivery where id = ?", String.class, id);
    }

    /** The same stock call {@code cancel} makes, dated yesterday so the business-date rule always accepts it. */
    private void reverseStock(long id) {
        stock.reverseAllFor(new SourceRef("DELIVERY", id), "probe", LocalDate.now(MANILA).minusDays(1), ownerActor);
    }

    /** The order {@code cancel} uses: change first, then the stock call. The flush writes the change before the clear. */
    @Test
    void aChangeMadeBeforeTheStockCallIsWritten() {
        long id = activeDelivery();
        var tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> {
            Delivery d = deliveries.lockById(id).orElseThrow();
            Instant now = Instant.now();
            d.markDone(now, now);
            assertThat(em.contains(d)).as("managed before the stock call").isTrue();

            reverseStock(id);

            assertThat(em.contains(d)).as("detached by clearAutomatically").isFalse();
        });

        assertThat(statusInDatabase(id)).isEqualTo("DONE");
    }

    /** The trap: a change made after the stock call is on a detached entity, so nothing is written and nothing fails. */
    @Test
    void aChangeMadeAfterTheStockCallIsSilentlyLost() {
        long id = activeDelivery();
        var tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> {
            Delivery d = deliveries.lockById(id).orElseThrow();

            reverseStock(id);
            Instant now = Instant.now();
            d.markDone(now, now);   // the entity itself accepts the change...

            assertThat(d.status()).as("the in-memory copy changed").isEqualTo(DeliveryStatus.DONE);
            assertThat(em.contains(d)).as("...but it is no longer managed").isFalse();
        });

        assertThat(statusInDatabase(id)).as("the database never saw the change").isEqualTo("ACTIVE");
    }

    /** Lines are EAGER because of the clear: the view built after a stock call still has them. */
    @Test
    void linesStayReadableAfterTheContextIsCleared() {
        long id = activeDelivery();
        var tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> {
            Delivery d = deliveries.lockById(id).orElseThrow();
            reverseStock(id);

            assertThat(entityManagerFactory.getPersistenceUnitUtil().isLoaded(d, "lines")).isTrue();
            assertThat(d.lines()).hasSize(1);
        });
    }
}
