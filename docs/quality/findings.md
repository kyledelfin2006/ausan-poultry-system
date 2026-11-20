# Findings

Defects, risks and observations found while building the quality gates. Each has an id, an owner (who should act), a severity and, where
a defect is reproducible, an executable test that is `@Disabled` until the fix lands (remove the annotation in the fixing change).

Severity: **defect** = behaviour contradicts the documented or obviously intended rule; **risk** = works today, can hurt later;
**observation** = a design fact worth knowing, no action required.

Owners follow the module split of the parallel work: *delivery/stock/finance/report*, *identity/audit*, *shared*, and
*catalog (unowned)*, which needs somebody to adopt it.

## Open defects

| Id | Owner | Summary | Reproducing test |
|---|---|---|---|
| Q-001 | catalog (unowned) | `PATCH` of a product can change its name to blank or spaces only; the request returns 200 instead of 400. Creating a product with a blank name is refused, so the update path is missing the same validation. | `CatalogApiTests.aPatchCannotBlankOutAProductName` (`@Disabled`) |
| Q-002 | catalog (unowned) | Names that differ only by trailing spaces are different to the database, so `"Layer feed "` is created next to `"Layer feed"` (201) instead of being refused as a duplicate (409). The name should be trimmed before it is saved and compared. | `CatalogApiTests.namesThatDifferOnlyByTrailingSpacesAreTheSameProduct` (`@Disabled`) |

Suggested fix for both: trim in the request mapping (or the entity setter) and validate non-blank in one place used by create and update.

## Risks

| Id | Owner | Summary | Why it matters | Suggested action |
|---|---|---|---|---|
| Q-003 | delivery (Claude 2) | There is no upper bound on the quantity of a package line. A huge value (`2^62 + 1`, `Long.MAX_VALUE`) fails cleanly, but through a database overflow guard, so the client sees the generic `409 "The change conflicts with existing data"` and not a validation message. Nothing is written (`DeliveryEdgeCaseTests` proves it). | Poor error message; the safety depends on a database column size rather than on a rule. | Validate a sensible maximum (for example 10,000 packages) at the request and answer 400. |
| Q-004 | identity/audit (Claude 3) | `ModuleBoundaryTests` keeps a table of allowed module dependencies. A new `audit` module is not in it. | The table is where a new module's dependencies are checked; an unregistered module may fail or pass unchecked depending on the rule. `CodingRulesTests` is module-agnostic and already covers the new module's classes. | Add `audit` and its allowed dependencies to `ModuleBoundaryTests` in the same change that introduces the module. |
| Q-005 | all modules (unowned) | Javadoc lint runs with `-Xdoclint:all,-missing`. Under strict `-Xdoclint:all` the existing code produces at least 100 warnings (javadoc stops counting at 100): about 80 "no comment" on private fields, 13 "no @param", 4 default constructors without a comment, 3 "no @return". | The project's documentation standard asks for Javadoc everywhere; the gate currently enforces *correctness* of what is written, not *completeness*. | Document the backlog module by module, then remove `-missing` from the `<doclint>` setting in `pom.xml` so the gate also enforces completeness. |
| Q-006 | identity (Claude 3) | `LoginThrottle` keeps its counters in memory (5 failures per 15 minutes per username and address, at most 10,000 keys). | A restart resets it and several instances would each count separately. Fine for one instance. | Move to a shared store before running more than one instance. Already listed under known limits in [../architecture/overview.md](../architecture/overview.md). |

## Observations

| Id | Area | Observation |
|---|---|---|
| Q-007 | delivery | The idempotency comparison treats an *omitted* price override as matching any price. A retry that adds an override to an otherwise identical request is therefore answered with the original delivery, not refused. Probably what a client wants (the original is what happened), but it is a design choice: confirm it with the owner. |
| Q-008 | delivery | Delivery code numbers are not gapless: the sequence value of a failed creation is lost (see [consistency-and-concurrency.md](../architecture/consistency-and-concurrency.md)). If paper records require gapless numbers, a different numbering scheme is needed. |
| Q-009 | stock | The stored average cost can differ in the fifth decimal depending on the order in which concurrent purchases are applied (rounding at every step). Quantities are always exact and the cost of goods is rounded to centavos per movement. `DeliveryConcurrencyTests` bounds the drift at 0.0005. Not a defect. |
| Q-010 | catalog | Before this work the catalog module had no tests (about 45 % line and 8 % branch coverage). `CatalogApiTests` now covers its endpoints, which is how Q-001 and Q-002 were found. Further catalog rules (package contents immutable, deactivation) would add confidence. |
| Q-011 | build | Mockito's inline mock maker attaches an agent at runtime; JDK 25 warns about this unless `-XX:+EnableDynamicAgentLoading` is set. It is set in the Surefire `argLine` together with the JaCoCo agent. Remove it only when Mockito is configured with an explicit agent. |

## How to add a finding

1. Add a row here with the next id, the owner, and a one-line summary a non-developer can understand.
2. If it is a defect that can be reproduced, add a test that fails today, annotate it `@Disabled("Q-00N: ...")`, and name it in the table.
3. Tell the owner (the module's session or maintainer). The fixing change removes `@Disabled` and moves the row to
   "Resolved" below with the pull request.

## Resolved

Nothing yet.
