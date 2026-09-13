# API development instructions

These instructions apply under `api/` in addition to the repository-level `AGENTS.md`.

## Runtime and build

- Use Java 25 and the checked-in Maven wrapper.
- Keep Spring Boot and Spring Modulith on compatible stable patch releases; verify official compatibility before upgrading either.
- Run `./mvnw verify` after API changes. Do not replace PostgreSQL-specific behavior with H2-only tests.

## Modular monolith boundaries

- `ph.epr.api` is the application bootstrap package. Its `configuration` package contains transport and framework adapters only; it must not contain business rules.
- Every business capability is an explicitly annotated, closed Spring Modulith application module directly below `ph.epr.api`.
- A module owns its domain model, application services, persistence repositories, database tables, and migrations. Never import another module's repository, entity, or internal package and never mutate another module's tables directly.
- Keep module APIs small. Expose cross-module contracts from the module base package or an explicitly annotated `@NamedInterface`; add the exact dependency to `allowedDependencies` in the consuming module.
- Keep `allowedDependencies = {}` until a real use case requires a dependency. Dependencies must remain acyclic and should point toward stable application APIs.
- Use synchronous module API calls within a single transaction for critical clinical workflows. Use domain events plus the durable PostgreSQL event publication registry for retryable side effects.
- Do not create a generic shared-kernel module for convenience. Value types belong to the module that owns their meaning; duplicate small transport-neutral values when ownership would otherwise become ambiguous.

## Layers inside a module

- `domain`: framework-independent business rules and value types.
- `application`: use cases, transactions, authorization orchestration, and public module facades.
- `infrastructure`: persistence and external-service adapters.
- `web`: REST request/response mapping only.
- Dependencies flow from adapters toward application/domain code. Domain code must not depend on Spring MVC, JPA repositories, servlet sessions, cookies, or JWT libraries.

## Persistence, tenancy, and security

- PostgreSQL 17 and Flyway are authoritative. Hibernate schema generation remains disabled.
- Tenant-owned tables require `tenant_id`, tenant-consistent foreign keys, RLS read/write policies, and `FORCE ROW LEVEL SECURITY`. Set tenant context transaction-locally only after current membership authorization; missing context must fail closed.
- Runtime and migration database roles are separate outside local development. Runtime credentials must not own tables or bypass RLS.
- Keep HTTP sessions limited to authentication and CSRF concerns. Map browser and future bearer-token authentication into a transport-neutral authenticated actor before invoking application services.
- All practice-scoped endpoints live under `/api/v1/practices/{practiceId}/...` and independently authorize the path practice.
- Preserve client-generated UUIDs, optimistic versions, idempotency keys, occurrence/client/server timestamps, immutable signed history, and explicit transitions in new persisted workflows.

## Tests

- Keep `ModularityTests` passing; it is the CI guard for cycles, access to module internals, and undeclared module dependencies.
- Add module-sliced tests for new application behavior and PostgreSQL Testcontainers tests for persistence, RLS, transaction, retry, concurrency, and pooled-connection isolation behavior as applicable.
- A passing context or build test is not clinical validation or authorization proof.
