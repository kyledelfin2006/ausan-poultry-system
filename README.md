# Ausan Farm System

**Ausan Poultry Farm** plans to use Ausan Farm System to manage stock, paid deliveries, and finances across its two branches. This repository currently contains a Spring Boot starter project and project documentation. The business features described below are **proposed**, not implemented.

## Project documents

- [Business problem and proposed system](docs/business-and-system-proposal.md) — current workflow, first-release scope, business rules, and planned work.
- [Spring Boot starter notes](HELP.md) — references generated with the starter project.

## Planned technology

The backend is planned first with Spring Boot, Maven, PostgreSQL, and Docker. A React, Vite, and TypeScript frontend follows. The current `pom.xml` configures Spring Boot and Java 25; it does not yet configure PostgreSQL or provide a Docker setup.

The intended first release is a lean ERP starting point for inventory, deliveries, and finance. Live GPS, route planning, payroll, and broader business management are outside its scope.
