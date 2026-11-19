# Design: modular monolith, build order, and the first slices (Foundation → Catalog → Stock)

**Status:** Proposal for senior review. Code below is *proposed and not yet compiled or run*. Nothing here is in `src/` yet.
**Builds on:** [business-and-system-proposal.md](business-and-system-proposal.md). Currency is Philippine peso (PHP, single currency, no FX). Time zone is `Asia/Manila`.

---

## 1. Build order and why

| # | Slice | Delivers | Why in this position |
|---|---|---|---|
| 1 | **Foundation** | Postgres, Flyway, `compose.yaml`, users + 2 roles, audit fields, error format, Testcontainers harness | Everything else needs persistence, identity (`created_by`) and migrations |
| 2 | **Catalog + Stock** | Branches, products, stock ledger, balances, opening/purchase/donation/usage/adjustment | Delivery and finance both *depend* on stock. It carries the hardest invariant (never negative, always explainable), so it goes first and gets the most tests |
| 3 | **Finance ledger (write side)** | Financial entries for stock purchase, expense, sale, payment, refund; hook purchases into it | Delivery needs somewhere to record sale/collection in the same transaction |
| 4 | **Delivery** | Paid delivery → stock-out + sale + payment; Done; Cancel → reversal | Pure orchestration over slices 2 and 3 |
| 5 | **Reports** | Sales collected, purchases, expenses, cash movement, profit | Read-only queries over the ledgers; needs costing decision |
| 6 | **UI** | React/Vite/TS | Last, as requested |

**This session's target: slice 1 + 2.** Slices 3–4 are sketched in §6–7 only so slice 2's shape doesn't block them.

## 2. Shape: modular monolith, package-by-domain

One deployable, one database, one transaction per business action. That is the point of not using microservices here: "paid delivery = stock-out + sale + payment" must commit or roll back as one unit.

```
kyle.poultry
├── PoultryManagementSystemApplication
├── shared/                     // tiny: Clock bean, error handling, audit base, Actor
├── identity/                   // AppUser, Role, login, CurrentUser
├── catalog/                    // Branch, Product   (reference data)
├── stock/                      // StockMovement, StockBalance, StockApi
├── finance/                    // FinancialEntry, FinanceApi        (slice 3)
└── delivery/                   // Delivery, DeliveryLine, Payment   (slice 4)
```

Inside a module: `web` (controller + request/response records), the service, `persistence` (entities + repositories). Entities and repositories are package-private; **only the `*Api` interface and its command/result records are public**. Other modules may call only that.

### Dependency rule (enforced by a test)

```
delivery ──► stock ──► catalog
   │           │
   └──► finance ◄┘ (stock purchases only)
all ──► identity (actor), shared
finance ──► (nothing; references sources by type+id, no foreign keys into other modules' tables)
```

No cycles. `finance` is a leaf, so reports never reach into delivery or stock internals. I'd enforce this with **Spring Modulith `ApplicationModules.verify()`** (one test class, one dependency) or ArchUnit if you prefer. Without enforcement, a monolith drifts into a ball of mud.

## 3. Slice 1 — Foundation

### Build changes (`pom.xml`)

| Change | Reason |
|---|---|
| add `org.postgresql:postgresql` (runtime) | No JDBC driver today |
| add `spring-boot-starter-flyway` (Boot 4 name; I will confirm against the BOM) | Versioned schema; Hibernate `ddl-auto=validate` only |
| add `spring-boot-starter-validation` | Request validation |
| add Testcontainers PostgreSQL module (Boot 4 / Testcontainers 2 artifact name to be confirmed with `dependency:tree`) | Real Postgres in tests: the stock invariant relies on SQL semantics, so H2 would lie |
| add `spring-boot-docker-compose` + `compose.yaml` | One-command local DB |
| **remove** `spring-boot-starter-security-oauth2-client` (+ its test starter) *unless you want Google login* | Roles are 2 people; username/password is simpler and offline-friendly (see Q5) |
| add `spring-modulith-starter-test` (test) | Boundary check |

Lombok stays only for what already works; entities are written without it (JPA + Lombok `@Data` is a known trap).

### `application.yaml` essentials

```yaml
spring:
  jpa:
    hibernate.ddl-auto: validate
    open-in-view: false
  jackson.time-zone: Asia/Manila
  flyway.enabled: true
app:
  zone: Asia/Manila
```

### Identity

```sql
-- V1__identity.sql
create table app_user (
  id            bigint generated always as identity primary key,
  username      varchar(50)  not null unique,
  password_hash varchar(100) not null,           -- BCrypt
  display_name  varchar(100) not null,
  role          varchar(20)  not null check (role in ('OWNER','ASSISTANT')),
  active        boolean      not null default true,
  created_at    timestamptz  not null default now()
);
```

```java
public enum Role { OWNER, ASSISTANT }

/** Who is doing this. Passed explicitly into services so domain code stays testable. */
public record Actor(long userId, Role role) {}
```

Rules: no self-registration; the owner creates users. No hard deletes anywhere in the system (deactivate instead). Method security with `@PreAuthorize("hasRole('OWNER')")` on sensitive operations.

**Provisional permission matrix** (Q4 confirms):

| Action | OWNER | ASSISTANT |
|---|---|---|
| Manage users, branches, products | ✅ | ❌ |
| Record purchase / donation / usage | ✅ | ✅ |
| Opening stock, stock **adjustment** | ✅ | ❌ |
| Create delivery, mark Done | ✅ | ✅ |
| Cancel delivery, refunds | ✅ | ❌ |
| Enter expenses | ✅ | ✅ |
| View profit | ✅ | ❌ |

### Cross-cutting constraints

- **Money:** `NUMERIC(14,2)` in DB, `BigDecimal` scale 2, `RoundingMode.HALF_UP`, always `>= 0` with a type that says direction. No `double` anywhere.
- **Time:** `timestamptz` ↔ `Instant` for "when it was recorded"; `date` ↔ `LocalDate` for "business date" (what reports filter on, interpreted in `Asia/Manila`). One injected `Clock`; no `Instant.now()` calls in code.
- **Audit:** every business row has `created_by`, `created_at`. Ledger tables are **append-only**: no UPDATE/DELETE code path; later, the app's DB role has `UPDATE/DELETE` revoked on them.
- **Errors:** one `@RestControllerAdvice` returning RFC 9457 `ProblemDetail` (409 for insufficient stock, 422 for rule violations, 404, 400).

## 4. Slice 2 — Catalog

```sql
-- V2__catalog.sql
create table branch (
  id     bigint generated always as identity primary key,
  name   varchar(80) not null unique,
  active boolean not null default true
);

create table product (
  id     bigint generated always as identity primary key,
  name   varchar(120) not null unique,
  unit   varchar(20)  not null,          -- 'head', 'pc'
  active boolean not null default true
);
```

Products are **data, not an enum**. Cage, chicken, nipple and water jug are rows. Adding feed or medicine later is an insert, not a deployment.

```java
// catalog/Product.java  (package-private entity; public read model below)
@Entity @Table(name = "product")
class Product {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true) private String name;
    @Column(nullable = false) private String unit;
    private boolean active = true;

    protected Product() {}
    Product(String name, String unit) { this.name = requireText(name); this.unit = requireText(unit); }
    void rename(String name) { this.name = requireText(name); }
    void deactivate() { this.active = false; }
    // getters only
}

public record ProductView(long id, String name, String unit, boolean active) {}
public interface CatalogApi {
    ProductView product(long id);        // throws NotFoundException; rejects inactive for new movements
    boolean isActiveBranch(long id);
}
```

## 5. Slice 2 — Stock (the core)

### Model

```
 stock_movement  (append-only: the WHY)        stock_balance (current: the HOW MUCH)
 ───────────────────────────────────           ─────────────────────────────────────
 id, product_id, branch_id                     (product_id, branch_id)  PK
 type, quantity_delta (signed, ≠ 0)            quantity >= 0  (DB CHECK)
 unit_cost (nullable), reason
 occurred_on (business date)
 source_type, source_id  (e.g. DELIVERY, 42)
 reverses_id  UNIQUE  → another movement
 created_by, created_at

 Invariant:  balance(p,b) == SUM(quantity_delta) over movements(p,b)   — tested
```

Two tables, one transaction. The ledger explains; the balance row answers "how many now?" in O(1) and is what we lock and guard.

```sql
-- V3__stock.sql
create table stock_balance (
  product_id bigint not null references product(id),
  branch_id  bigint not null references branch(id),
  quantity   bigint not null check (quantity >= 0),     -- last line of defence
  primary key (product_id, branch_id)
);

create table stock_movement (
  id             bigint generated always as identity primary key,
  product_id     bigint not null references product(id),
  branch_id      bigint not null references branch(id),
  type           varchar(20) not null,
  quantity_delta bigint not null check (quantity_delta <> 0),
  unit_cost      numeric(12,2) check (unit_cost >= 0),
  reason         varchar(255),
  occurred_on    date not null,
  source_type    varchar(20),
  source_id      bigint,
  reverses_id    bigint unique references stock_movement(id),   -- a movement can be reversed once, ever
  created_by     bigint not null references app_user(id),
  created_at     timestamptz not null,
  constraint ck_sign check (
       (type in ('OPENING','PURCHASE','DONATION','DELIVERY_REVERSAL') and quantity_delta > 0)
    or (type in ('USAGE','DELIVERY_OUT')                              and quantity_delta < 0)
    or  type = 'ADJUSTMENT'),
  constraint ck_reason check (type not in ('ADJUSTMENT','USAGE') or reason is not null)
);
create index ix_movement_pb_date on stock_movement (product_id, branch_id, occurred_on desc);
create index ix_movement_source  on stock_movement (source_type, source_id);
```

### Types and rules

```java
public enum MovementType {
    OPENING(Sign.IN), PURCHASE(Sign.IN), DONATION(Sign.IN), DELIVERY_REVERSAL(Sign.IN),
    USAGE(Sign.OUT), DELIVERY_OUT(Sign.OUT),
    ADJUSTMENT(Sign.EITHER);

    enum Sign { IN, OUT, EITHER }
    private final Sign sign;
    MovementType(Sign sign) { this.sign = sign; }

    boolean accepts(long delta) {
        return switch (sign) { case IN -> delta > 0; case OUT -> delta < 0; case EITHER -> delta != 0; };
    }
    boolean needsReason() { return this == ADJUSTMENT || this == USAGE; }
}
```

The DB `CHECK`s mirror these rules on purpose: the Java gives clear error messages, the DB guarantees nothing else can write bad data.

### Entity

```java
@Entity @Table(name = "stock_movement")
class StockMovement {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private long productId, branchId;
    @Enumerated(EnumType.STRING) private MovementType type;
    private long quantityDelta;
    private BigDecimal unitCost;
    private String reason;
    private LocalDate occurredOn;
    private String sourceType; private Long sourceId;
    @Column(unique = true) private Long reversesId;
    private long createdBy; private Instant createdAt;

    protected StockMovement() {}

    static StockMovement create(StockCommand c, Actor actor, Instant now) {
        if (!c.type().accepts(c.quantityDelta()))
            throw new RuleViolation(c.type() + " does not allow delta " + c.quantityDelta());
        if (c.type().needsReason() && (c.reason() == null || c.reason().isBlank()))
            throw new RuleViolation(c.type() + " requires a reason");
        var m = new StockMovement();
        /* assign fields from c, actor.userId(), now */
        return m;
    }
}
```

### Public module API (all other modules use only this)

```java
public interface StockApi {
    MovementResult record(StockCommand cmd, Actor actor);          // opening, purchase, donation, usage, adjustment
    List<MovementResult> deduct(List<StockLine> lines, long branchId,
                                SourceRef source, LocalDate on, Actor actor);   // delivery: all-or-nothing
    List<MovementResult> reverseAllFor(SourceRef source, String reason, LocalDate on, Actor actor);
    long balance(long productId, long branchId);
    Page<MovementView> history(long productId, long branchId, Pageable page);
}

public record StockCommand(long productId, long branchId, MovementType type, long quantityDelta,
                           BigDecimal unitCost, String reason, LocalDate occurredOn, SourceRef source) {}
public record StockLine(long productId, long quantity) {}
public record SourceRef(String type, long id) {}
```

### The invariant, enforced atomically

Not "read balance, check, write" (lost updates under concurrency), but one conditional SQL statement:

```java
interface StockBalanceRepository extends JpaRepository<StockBalance, StockBalanceId> {

    @Modifying
    @Query(nativeQuery = true, value = """
        insert into stock_balance (product_id, branch_id, quantity)
        values (:p, :b, 0) on conflict do nothing""")
    void ensureRow(long p, long b);

    /** Returns 0 when the result would go negative → caller reports insufficient stock. */
    @Modifying
    @Query(nativeQuery = true, value = """
        update stock_balance set quantity = quantity + :delta
         where product_id = :p and branch_id = :b and quantity + :delta >= 0""")
    int applyDelta(long p, long b, long delta);
}
```

```java
@Service
class StockLedger implements StockApi {
    @Override @Transactional
    public MovementResult record(StockCommand c, Actor actor) {
        catalog.requireActiveProduct(c.productId());
        catalog.requireActiveBranch(c.branchId());
        var movement = StockMovement.create(c, actor, clock.instant());   // validates sign/reason first
        balances.ensureRow(c.productId(), c.branchId());
        if (balances.applyDelta(c.productId(), c.branchId(), c.quantityDelta()) == 0)
            throw new InsufficientStockException(c.productId(), c.branchId());
        return MovementResult.of(movements.save(movement));
    }

    @Override @Transactional
    public List<MovementResult> deduct(List<StockLine> lines, long branchId, SourceRef src, LocalDate on, Actor actor) {
        // Lock order = ascending productId, so two concurrent deliveries can never deadlock.
        return lines.stream().sorted(comparingLong(StockLine::productId))
            .map(l -> record(new StockCommand(l.productId(), branchId, MovementType.DELIVERY_OUT,
                                              -l.quantity(), null, null, on, src), actor))
            .toList();   // any exception rolls back the whole delivery transaction
    }

    @Override @Transactional
    public List<MovementResult> reverseAllFor(SourceRef src, String reason, LocalDate on, Actor actor) {
        return movements.findBySource(src.type(), src.id()).stream()
            .filter(m -> m.type() == MovementType.DELIVERY_OUT)
            .map(m -> reverse(m, reason, on, actor))   // inserts DELIVERY_REVERSAL with reverses_id = m.id
            .toList();                                  // UNIQUE(reverses_id) makes double-cancel impossible
    }
}
```

### Flow: record a purchase

```
POST /api/stock/movements {type: PURCHASE, productId, branchId, quantity: 100, unitCost: 85.00, occurredOn}
  → StockController → @PreAuthorize(OWNER|ASSISTANT) → StockApi.record()
      ├ catalog: product & branch active?
      ├ StockMovement.create(): sign + reason rules
      ├ ensureRow + applyDelta (atomic, ≥ 0)
      ├ insert movement (append-only)
      └ [slice 3] FinanceApi.recordStockPurchase(amount = qty × unitCost, source = MOVEMENT#id)  — same tx
  ← 201 + MovementResult
```

Donation and opening stock **do not** touch finance (matches the business proposal).

### Endpoints (slice 2)

| Method | Path | Notes |
|---|---|---|
| `GET/POST/PATCH` | `/api/branches`, `/api/products` | OWNER writes |
| `POST` | `/api/stock/movements` | Typed by `type`; OPENING/ADJUSTMENT OWNER-only |
| `GET` | `/api/stock/balances?branchId=` | Current quantities |
| `GET` | `/api/stock/movements?productId=&branchId=&from=&to=` | Paged history |

No `PUT`/`DELETE` on movements. A mistake is fixed with an `ADJUSTMENT` carrying a reason.

### Tests (Testcontainers Postgres, no mocks for the DB)

1. **Cannot go negative:** balance 10, deduct 11 → `InsufficientStockException`, balance still 10, no movement row.
2. **Concurrency:** balance 10, two threads each deduct 6 → exactly one succeeds; balance = 4.
3. **Reconciliation:** after a random sequence of operations, `balance == SUM(quantity_delta)` for every (product, branch).
4. **Sign rules:** `PURCHASE` with a negative delta rejected by Java *and* by the DB check.
5. **Atomic multi-line deduct:** line 2 short → line 1 rolled back.
6. **Double reversal:** reversing the same movement twice → second fails on `UNIQUE(reverses_id)`.
7. **Boundary test:** `ApplicationModules.verify()`.

## 6. Sketch — Finance ledger (slice 3, so slice 2 doesn't box it in)

```
financial_entry: id, type, amount (>0), direction (IN|OUT|NONE), method (CASH|GCASH|BANK|...),
                 occurred_on, description, source_type, source_id, reverses_id UNIQUE, created_by, created_at

type            cash?   meaning
SALE            none    revenue recognised for a delivery
PAYMENT_IN      IN      customer money received
SALE_REVERSAL   none    cancels the sale in reports; the SALE row is kept
REFUND          OUT     money actually given back (separate, only when it happens)
STOCK_PURCHASE  OUT     cash paid for inventory
EXPENSE         OUT     operating cost
```

This honours the proposal: cancelled sales leave reports, the retained payment stays visible as `PAYMENT_IN` without a `REFUND`, and purchase cash is distinct from cost-of-goods.

## 7. Sketch — Delivery (slice 4)

```
ACTIVE ──► DONE            (sets completedAt, no other effects)
ACTIVE ──► CANCELLED       (StockApi.reverseAllFor + SALE_REVERSAL, atomic; idempotent)
DONE / CANCELLED: terminal   (DONE→CANCELLED is Q7)
```

`DeliveryService.create()` is one `@Transactional` method: validate → `StockApi.deduct` → `FinanceApi.recordSale + recordPayment` → save delivery. Human-readable code `DLV-2026-0001` from a Postgres sequence. Fixing a mistake after saving = cancel and re-enter (no in-place editing of priced lines).

## 8. Open business decisions that change the design

See the questions in the conversation. Each has a default that I will use if unanswered.
