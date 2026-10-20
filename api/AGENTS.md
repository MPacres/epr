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
- Resolve authorized `practiceId` values through the control-plane registry to server-owned tenant database routes. Clients never select database names, URLs, schemas, endpoints, or credentials; missing, disabled, migrating, or mismatched routes fail closed.
- Each Practice has a separate logical PostgreSQL database with distinct runtime and migration roles. Retain `practice_id` and tenant-consistent foreign keys for defense-in-depth and audit/export integrity. Runtime credentials must not own tables or connect to another tenant database.
- Bound aggregate tenant datasource connections, lazily create and evict pools, and test route/pool reuse for tenant leakage. Use `FORCE ROW LEVEL SECURITY` on shared tenant-scoped control-plane or operational tables.
- Run Flyway as a canaried, observable tenant-fleet operation with per-tenant schema status and quarantined failures. Do not design critical workflows around distributed transactions across control-plane and tenant databases.
- Keep HTTP sessions limited to authentication and CSRF concerns. Map browser and future bearer-token authentication into a transport-neutral authenticated actor before invoking application services.
- All practice-scoped endpoints live under `/api/v1/practices/{practiceId}/...` and independently authorize the path practice.
- Preserve client-generated UUIDs, optimistic versions, idempotency keys, occurrence/client/server timestamps, immutable signed history, and explicit transitions in new persisted workflows.

## Tests

- Keep `ModularityTests` passing; it is the CI guard for cycles, access to module internals, and undeclared module dependencies.
- Add module-sliced tests for new application behavior and PostgreSQL Testcontainers tests for persistence, RLS, transaction, retry, concurrency, and pooled-connection isolation behavior as applicable.
- A passing context or build test is not clinical validation or authorization proof.

## Connected synthetic clinical slice

The local clinical capability is opt-in (`epr.clinical.enabled`, `epr.clinical.demo-seed`). See `../docs/architecture/encounter-templates-obgyn-implementation.md` for the base rollout (current tenant schema 15 after later extensions), exact-byte local artifacts, active-care reconciliation and contract generation. The synthetic confirmation adapter is not clinical signing authority. Runtime routing and command/audit/outbox helpers expose explicit tenant transaction contexts; their presence does not authorize cross-module table access. The base worklist supports explicit reconciliation; the intervention extension adds a retryable worker and accepted operational handoffs, with authoritative obligations retained before delivery. Keep real-patient, extraction, production offboarding/coverage, storage/signing and pilot gates explicit.

Patient registration extends the local slice with explicit patient capabilities (control-plane V13–V14) and tenant schema 7 (V6–V7). The patient registry owns demographics, identifiers, duplicate reviews, correction evidence, recent opens and visit projections. Encounter-owned reconciliation publishes summaries through the registry API; never add cross-module table reads. See `../docs/architecture/patient-registration-implementation.md`.

## Practice team administration

The closed `practiceadministration` module depends only on identity/tenancy and platform operations. It coordinates identity-owned team commands with an authorized site-metadata directory; administrative contexts never enter clinical commands. Control-plane V15 supplies aggregate versions, generic team onboarding, append-only evidence/receipts and lookup throttling. Team endpoints require `local` plus `epr.team.enabled=true`. Clinical access reduction remains blocked pending fenced offboarding; non-clinical suspension/reactivation is supported. See `../docs/architecture/practice-team-access-implementation.md`.

Control-plane V19 adds readiness-safe longitudinal-list access management. Practice Team commands carry exact per-site/source action grants, validate the constrained permission lattice and schema/Practice/source prerequisites, and commit grants with roles, sites, aggregate version, append-only evidence and idempotent receipt. Synthetic Practice activation creates all three source capabilities but no user grants. Provider Support owns readiness/schema/capability actions and never individual doctor grants. See `../docs/architecture/clinical-list-access-management-implementation-plan.md`.

Local synthetic clinical capability for a newly provisioned Practice is a separate `SUPERADMIN` command after schema-15 rollout and an explicit clinician directory grant. Control-plane V17 permits its append-only administration event. Doctor/team roles alone do not activate clinical routing, and this command does not publish templates or authorize real-patient use.

## Synthetic encounter interventions

The intervention extension uses tenant schema 11 and control-plane V16; the longitudinal clinical-list extension adds control-plane V18 and tenant V12–V14, and prescribing catalog/refill provenance adds tenant V15, so current clinical routing requires schema 15. `EncounterInterventionLinks` owns encounter associations/manifests; prescribing, orders/results and care coordination retain typed source ownership. Acquire the encounter boundary before source locks. Capture content, stage private bytes outside the transaction, then finalize against versions and hashes. `epr.interventions.enabled` gates new preparation/issuance, while current explicit clinical grants authorize history and existing-work disposition. The tenant-aware local worker reconciles owned obligations and cleans old unattached files; disabling new commands must not disable continuity. See [implementation and evidence](../docs/architecture/encounter-interventions-implementation.md). Production signing/storage, offboarding/fallback coverage and clinical approval remain gated.

## Longitudinal clinical lists

`clinicalchart` owns allergy, reported medication-use and problem identities, immutable revisions, explicit absence assertions, proposals, review manifests and immutable reconciliation decisions. Control-plane V18 grants source/action authority separately from demographic and prescribing access. Tenant V12–V14 persist the source heads, revisions, generations, exact review evidence and patient-consistent provenance constraints. `prescribing` and `encountertemplates` consume only the public `ClinicalLists` boundary: new prescription preparation/issuance requires a current canonical safety manifest, and prepared note signatures are invalidated if their bound review becomes stale. Legacy flat medication-safety reviews remain readable but cannot be created. See [the implementation record](../docs/architecture/clinical-lists-reconciliation-implementation-plan.md). Fixture terminology, synthetic authorization and local HTML signing are not production clinical safety controls.
