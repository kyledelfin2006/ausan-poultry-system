# Architecture documentation

How the Ausan Poultry System backend is built, why it is built that way, and how the build keeps it that way. Start with the overview;
the rest can be read in any order.

| Document | Read it to learn | Audience |
|---|---|---|
| [overview.md](overview.md) | The shape of the system: modules, dependency direction, layers, cross-cutting rules (transactions, time, errors, security), runtime view | Everyone new to the code |
| [data-model.md](data-model.md) | Every table, how they relate (ERD), which business rules the database enforces itself, migration rules | Anyone touching SQL or entities |
| [sequences.md](sequences.md) | Step-by-step flows: sign-in and request authentication, purchase, create / cancel / refund a delivery | Anyone changing a use case |
| [ledger-and-costing.md](ledger-and-costing.md) | The two append-only ledgers, weighted average cost with worked numbers, what each business event writes, how reports are derived | Anyone touching money or stock |
| [consistency-and-concurrency.md](consistency-and-concurrency.md) | Which mechanism protects which invariant when requests overlap, and which test proves it | Anyone touching transactions or locking |
| [glossary.md](glossary.md) | The vocabulary of the business and of the code | Everyone |
| [decisions/](decisions/README.md) | Architecture decision records (ADRs): the significant choices, the alternatives, the consequences | Anyone about to change a foundation |

Related documents that live elsewhere:

- [../api.md](../api.md): the contract the frontend codes against (it moves to `docs/api/` with the API-contract work; update this link then).
- [../reports.md](../reports.md): the exact definitions behind every report figure.
- [../operations.md](../operations.md): deployment, upgrade, backup, restore.
- [../quality/](../quality/README.md): test strategy, quality gates, findings.
- [../business-and-system-proposal.md](../business-and-system-proposal.md): the business problem and scope.
- [../design-foundation-and-stock.md](../design-foundation-and-stock.md): the original design notes for slices 1-2 (historical; where it differs from the
  documents here, these documents describe the current code).

## How these documents stay true

- Diagrams are [Mermaid](https://mermaid.js.org/) text, so they diff in pull requests and render on GitHub and in IntelliJ.
- Statements about enforcement name the test or constraint that enforces them. If you change the rule, change the test and
  this text in the same pull request.
- A change that alters a module's dependencies, a table, a ledger rule or a locking mechanism updates the matching document here
  (the pull request template asks for it). A choice a future reader will question gets an ADR instead of a paragraph.
