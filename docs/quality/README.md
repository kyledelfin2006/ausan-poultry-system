# Quality gates

What must be true before code is merged, how to check it on your own machine, and what each gate protects.

## 1. The gates

| Gate | What it checks | Where | Fails the build when |
|---|---|---|---|
| **Tests** | Unit, integration and concurrency tests (JUnit 5) | `src/test` | any test fails |
| **Coverage floor** | JaCoCo over the whole application | `pom.xml`, `jacoco:check` at `verify` | line coverage < 85 % or branch coverage < 75 % |
| **Architecture rules** | Module boundaries, layering and coding rules (ArchUnit) | `ModuleBoundaryTests`, `CodingRulesTests` | a rule is broken |
| **Javadoc lint** | Javadoc is well-formed (`-Xdoclint:all,-missing`, private members included) | `./mvnw javadoc:javadoc`, CI job `javadoc` | a malformed tag, broken `{@link}`, bad HTML |
| **Build environment** | Java 25+, Maven 3.9+, no duplicate dependency versions | maven-enforcer | the toolchain is wrong |
| **Migrations** | Flyway applies on an empty PostgreSQL and Hibernate `validate` agrees with it | every integration test | schema and entities disagree |

The coverage floors are deliberately a little below the current values so that normal work does not trip them, and high enough that
dropping a module's tests does. Raise them when coverage rises; never lower them to make a build pass: write the missing test.

## 2. Commands

```powershell
./mvnw verify                 # everything: tests, coverage check, report (needs Docker running)
./mvnw javadoc:javadoc        # Javadoc lint
./mvnw -Dtest=DeliveryServiceUnitTests test   # one class (pure unit tests need no Docker)
```

The coverage report is written to `target/site/jacoco/index.html`. CI uploads it as the `jacoco-report` artifact and prints a
per-module summary on the run page.

Docker must be running: integration tests start PostgreSQL 17 through Testcontainers
([ADR-0006](../architecture/decisions/0006-real-postgresql-in-tests.md)).

## 3. Continuous integration

`.github/workflows/ci.yml` runs on every pull request and on pushes to `main`:

- **verify**: `./mvnw -B -ntp verify`, then the coverage summary, the JaCoCo report artifact and, on failure, the Surefire reports.
- **javadoc**: the Javadoc lint as a separate, quick job.
- A newer push to the same pull request cancels the older run.

`.github/dependabot.yml` proposes weekly updates for Maven, GitHub Actions and Docker. `.github/pull_request_template.md` is the
checklist shown when a pull request is opened.

## 4. Test inventory

| Area | Test classes | Style |
|---|---|---|
| Stock | `StockMovementTests`, `StockLedgerUnitTests` | pure unit |
| Stock | `StockLedgerTests`, `WeightedAverageCostTests` | PostgreSQL |
| Finance | `FinanceLedgerUnitTests` | pure unit |
| Finance | `FinanceAndCostTests` | PostgreSQL |
| Delivery | `DeliveryStateMachineTests`, `DeliveryServiceUnitTests` | pure unit |
| Delivery | `DeliveryTests`, `DeliveryApiTests`, `DeliveryIdempotencyTests`, `DeliveryEdgeCaseTests` | PostgreSQL, HTTP |
| Delivery | `DeliveryConcurrencyTests` | PostgreSQL, racing transactions |
| Catalog | `CatalogApiTests` | PostgreSQL, HTTP |
| Identity | `ApiSecurityTests`, `UserAdministrationTests`, `LoginThrottleTests` | HTTP, security chain |
| Report | `ReportTests` | PostgreSQL, HTTP |
| Architecture | `ModuleBoundaryTests`, `CodingRulesTests` | ArchUnit over the compiled classes |
| Support | `TestSupport`, `ConcurrentRunner` | helpers, see [testing-guide.md](testing-guide.md) |

Two tests in `CatalogApiTests` are `@Disabled` on purpose: they describe correct behaviour that the code does not yet have
(Q-001, Q-002 in [findings.md](findings.md)). They show as *skipped* in the report, never as passed.

## 5. Further reading

- [testing-guide.md](testing-guide.md): how to write a test here.
- [mutation-checks.md](mutation-checks.md): evidence that the tests can fail.
- [findings.md](findings.md): defects and risks found while testing, with their owners.
- [../architecture/consistency-and-concurrency.md](../architecture/consistency-and-concurrency.md): the guarantees the concurrency tests prove.
