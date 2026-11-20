# Testing guide

How tests are written in this project, so a new one looks like the old ones and proves something.

## 1. Choose the smallest test that proves the promise

| You want to prove | Write | Needs Docker |
|---|---|---|
| An arithmetic, rounding or state rule | pure unit test (JUnit 5 + AssertJ) | no |
| That a service calls its collaborators in the right order, with the right values | Mockito unit test | no |
| A constraint, a lock, an atomic SQL statement, a rollback | integration test on PostgreSQL | yes |
| The HTTP contract: status codes, JSON, security | HTTP integration test | yes |
| That two requests at once cannot break an invariant | concurrency test with `ConcurrentRunner` | yes |
| That a structural rule holds everywhere | ArchUnit rule | no |

A mock repeats the author's assumption; only a real database can disprove it. Do not mock what you are trying to prove.

## 2. Conventions

1. **Javadoc on every test class and method**, one sentence stating the *rule* being proven, not the mechanics. The method name is a
   sentence about behaviour (`cancelledDeliveryCannotBeCompleted`, not `test3`).
2. **Arrange, act, assert**, with expected values calculated by hand (or by an independent oracle) and written as constants. Never
   compute the expected value with the production algorithm.
3. **One reason to fail per test.** Parameterise inputs that share a rule; split different rules.
4. **Time is injected.** Use `TestSupport.clockAt("2026-01-01T00:00:00Z")` for units; never `Thread.sleep` to wait for a result.
   Remember the business time zone is Manila: pick instants near midnight when the date matters.
5. **Mockito strictness stays on.** An unused stub means the setup is wrong. Keep stubs local to the test (the unit tests use small
   helper methods such as `packageA()`).
6. **Tests do not depend on each other or on order.** Integration tests create their own products, customers and keys with unique
   names (`UUID`) instead of clearing tables, because the container is shared.
7. **Assert what a user can observe**: the response, the rows in the ledgers, the balance. Verify an interaction only when the
   interaction is the requirement.

## 3. Helpers (`kyle.poultry.support`)

### `TestSupport`

| Member | Use |
|---|---|
| `MANILA` | the business zone, for building expected dates |
| `clockAt(String instant)` | a fixed `Clock` in Manila time |
| `withId(entity, id)` | sets the generated id of an entity in a pure unit test, by reflection |

### `ConcurrentRunner`

`ConcurrentRunner.race(List<Callable<T>>)` starts all tasks behind one gate so they begin together, waits for them with a
bounded timeout (60 s), and returns an `Outcome<T>(value, error)` per task, in the order given.

Rules for a race test:

- Assert on the **final state of the database** and on the *set* of outcomes ("exactly one succeeded"), never on which thread won.
- Repeat a close race a few times (`twoDeliveriesCompetingForTheLastPackageProduceExactlyOne` runs 5 rounds); one lucky pass proves nothing.
- A worker exception is returned as the `Outcome`'s error, not swallowed; assert on its type.
- A test of a *removed* safeguard must fail: do a mutation check ([mutation-checks.md](mutation-checks.md)).

## 4. Disabled tests

When a test exposes a defect that belongs to someone else, add the failing test with `@Disabled("Q-00N: <what is wrong>")`, and add the finding to
[findings.md](findings.md) naming the owner. The disabled test is the executable bug report: the fix removes the annotation.

## 5. Mutation check before trusting a new test

Before relying on a test, break the code it protects (in a scratch copy, never in the repository) and see that the test fails.
Typical breaks: remove a lock, flip a comparison, change a rounding mode, use the wrong time zone, make a class public. If nothing fails,
the test is decoration. Record a notable one in [mutation-checks.md](mutation-checks.md).

## 6. Architecture rules

Add a rule to `CodingRulesTests` when a mistake is structural and has been (or could easily be) repeated. A rule must be
module-agnostic where possible (so a new module is covered automatically), and must be checked once by breaking it on purpose.
Module dependencies themselves are the table in `ModuleBoundaryTests`: a new module must be registered there.
