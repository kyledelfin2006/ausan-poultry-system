# ADR-0007: Each module has an `api` package and an `internal` package

- Status: Accepted (amends point 2 of [ADR-0001](0001-modular-monolith.md))
- Date: 2026-11-28

## Context

[ADR-0001](0001-modular-monolith.md) kept every module in one flat package and protected its internals with Java's
package-private access: entities, repositories and service implementations were not `public`, so the compiler refused any
outside use. That works, but it forces controllers, services, entities, repositories, configuration, views and exceptions
into one folder. `identity` had 22 files side by side, and a reader could not tell the module's public face from its
machinery without opening each file. Nested records inside the `*Api` interfaces (`StockApi` was 286 lines, mostly records)
hid the module's contract inside its method list.

Splitting a module into sub-packages is only possible if the classes in them are `public`, so the compiler can no longer be the
guard. The guard has to be a test.

## Decision

Every module except `shared` has exactly two top-level packages, and nothing sits loose at the module root:

```
<module>/
├── api/               what other modules may use
│   ├── <Module>Api    the service interface (only where other modules call the module)
│   ├── model/         views, commands and enums that cross the boundary, one type per file
│   ├── exception/     exceptions callers must handle
│   └── security/      annotations other modules put on their own code
└── internal/          everything else, split by layer
    ├── web/           controllers
    ├── service/       the module's rules and transaction boundary; the `*Api` implementation
    ├── model/         values the layers pass around (views returned by services, requests, status enums)
    ├── persistence/   entities and repositories
    └── security, config, export, bootstrap   only where the module has that concern
```

`delivery` and `report` are called by nobody, so they have no `api/` package. `shared` is the common kernel, public by design,
and is grouped by concern (`error`, `time`, `csv`, `web`, `reference`, `openapi`).

Classes inside `internal` are `public` where the layers need to reach each other. Instead of the compiler, ArchUnit enforces the
boundary on every build ([ModuleBoundaryTests](../../../src/test/java/kyle/poultry/architecture/ModuleBoundaryTests.java),
[CodingRulesTests](../../../src/test/java/kyle/poultry/architecture/CodingRulesTests.java)):

1. Code outside a module depends only on that module's `api` package.
2. `api` never depends on its own `internal`, and holds no controller, service, entity or repository.
3. A module package contains only `api` and `internal`.
4. Entities and repositories live in `internal.persistence`, controllers in `internal.web`, and an `*Api` implementation in
   `internal.service`; an `*Api` interface lives in `api`.
5. Inside `internal` the layers point one way: `web` -> `service` -> `persistence`, and `model` depends on none of them.

The module dependency table and the no-cycle rule of ADR-0001 are unchanged.

## Consequences

- A reader sees a module's contract by opening `api/`, and the layer of any class from its package.
- A module's public types are plain top-level files, so a change to the contract shows up as a file in a diff.
- The compiler no longer stops a cross-module reach into a repository; only the build does. A change that bypasses the build
  (for example one that disables the architecture tests) removes the protection. Keep these tests in the required checks.
- More `public` surface inside a module: the members the layers share are `public`. Nothing outside the module may use them, which the
  tests enforce, but the compiler will not warn.
- The move changed no behaviour: the same tests passed before and after, and the generated OpenAPI snapshot is unchanged.

## Alternatives considered

- **Keep flat packages and package-private access (status quo).** Strongest compiler protection, but the layout does not scale to
  22 files in one module and hides the contract.
- **Layer packages for the whole application** (`controller/`, `service/`, `repository/`). Rejected for the reasons in ADR-0001: no
  module ownership of tables.
- **Spring Modulith.** Offers the same `internal` convention with its own verification, but adds a dependency for rules that
  ArchUnit already checks here.
- **A multi-module Maven build.** Gives compiler-enforced boundaries, at the cost of build complexity that this project does not
  need yet (see ADR-0001).
