# Testing and quality gates

What must be true before code merges, how to check it locally, and how tests are written here.

## 1. Gates

| Gate | Checks | Fails when |
|---|---|---|
| **Tests** | Unit, integration and concurrency tests (JUnit 5) | any test fails |
| **Coverage floor** | JaCoCo over the whole app (`jacoco:check` at `verify`) | line < 85 % or branch < 75 % |
| **Architecture** | Module boundaries, layering, coding rules (ArchUnit) | a rule is broken |
| **Javadoc lint** | `-Xdoclint:all,-missing`, private members included | a malformed tag, broken `{@link}`, bad HTML |
| **Build environment** | maven-enforcer: Java 25+, Maven 3.9+, no duplicate dependency versions | the toolchain is wrong |
| **Migrations** | Flyway applies on an empty PostgreSQL and Hibernate `validate` agrees | schema and entities disagree |
| **OpenAPI snapshot** | the running contract equals `docs/api/openapi.json` | a controller change altered the contract |
| **Dependency scan** | Trivy on production Maven dependencies | any HIGH or CRITICAL vulnerability |

The coverage floors sit a little below current values. Raise them when coverage rises; never lower them to pass a build: write the missing test.

## 2. Commands

```powershell
./mvnw verify                                   # everything: tests, coverage check (needs Docker)
./mvnw javadoc:javadoc                          # Javadoc lint
./mvnw -Dtest=DeliveryServiceUnitTests test     # one class (pure units need no Docker)
./mvnw test -DexcludedGroups=integration        # unit and architecture only
./mvnw test -Dgroups=integration                # PostgreSQL tests (Docker required)
```

The coverage report is `target/site/jacoco/index.html`; CI uploads it as the `jacoco-report` artifact. Integration tests start PostgreSQL 17 through Testcontainers ([decision 6](architecture/decisions.md#6-real-postgresql-in-tests)).

CI (`.github/workflows/ci.yml`) runs on every pull request and on pushes to `main`: **unit-test** (untagged tests, Javadoc lint), **integration-test** (tagged PostgreSQL tests, merged coverage gate), **security-scan** (Trivy). A newer push cancels the older run. Dependabot proposes weekly updates for Maven, GitHub Actions and Docker.

## 3. Choose the smallest test that proves the promise

| Prove | Write | Docker |
|---|---|---|
| Arithmetic, rounding, a state rule | pure unit test (JUnit 5, AssertJ) | no |
| A service calls collaborators correctly | Mockito unit test | no |
| A constraint, lock, atomic SQL, rollback | integration test on PostgreSQL | yes |
| HTTP status codes, JSON, security | HTTP integration test | yes |
| Two simultaneous requests cannot break an invariant | race test with `ConcurrentRunner` | yes |
| A structural rule holds everywhere | ArchUnit rule | no |

A mock repeats the author's assumption; only a real database can disprove it. Tag every Spring Boot/PostgreSQL class `@Tag("integration")`; leave pure units and ArchUnit untagged.

## 4. Conventions

1. **Javadoc on every test class and method**, one sentence stating the *rule* proven. Method names are sentences about behaviour (`cancelledDeliveryCannotBeCompleted`).
2. **Arrange, act, assert**, with expected values calculated by hand or an independent oracle, never by the production algorithm.
3. **One reason to fail per test.**
4. **Time is injected** (`TestSupport.clockAt(...)`); never `Thread.sleep`. The business zone is Manila, so pick instants near midnight when the date matters.
5. **Mockito strictness stays on**; an unused stub means the setup is wrong.
6. **Tests are independent.** Integration tests create their own data with unique names (`UUID`) instead of clearing tables, because the container is shared.
7. **Assert what a user can observe**: the response, ledger rows, the balance.

## 5. Race tests

`ConcurrentRunner.race(List<Callable<T>>)` starts all tasks behind one gate, waits up to 60 s, and returns an `Outcome(value, error)` per task. Assert on the **final database state** and the *set* of outcomes ("exactly one succeeded"), never on which thread won. Repeat a close race a few times; one lucky pass proves nothing.

## 6. Trust a new test only if it can fail

Before relying on a test, break the code it protects in a scratch copy: remove a lock, flip a comparison, change a rounding mode, use the wrong time zone, make a class public. If nothing fails, the test is decoration.

## 7. Architecture rules

Add a rule to `CodingRulesTests` when a mistake is structural and easy to repeat; keep it module-agnostic so new modules are covered, and check it once by breaking it on purpose. Module dependencies are the table in `ModuleBoundaryTests`: register every new module there.

## 8. Known defects

A defect that belongs to someone else is captured as a failing test marked `@Disabled("<what is wrong>")`; the fix removes the annotation. Open ones today: `CatalogApiTests` (a whitespace-only product name is accepted on update, and names differing only by trailing spaces are stored as different products).
