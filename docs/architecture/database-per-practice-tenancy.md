# Database-per-Practice tenancy

Status: Accepted  
Date: 2026-09-13

## Decision

A Practice is the tenant. Persist each Practice's business and clinical records in its own logical PostgreSQL database from launch.

Use a separate control-plane PostgreSQL database for global workforce identity references, the tenant registry, memberships and managed-administration grants, provisioning state, tenant schema status, and Spring Session JDBC records. The control plane must not become a shared patient chart or clinical-document store.

Multiple logical tenant databases may initially share one Amazon RDS PostgreSQL instance. The tenant registry allows a Practice to move to a dedicated RDS instance or cluster without changing its public API identifiers.

## Data ownership

| Store | Authoritative data |
| --- | --- |
| Control plane | Practice registry and database route references, global identity references, memberships, provider grants, provisioning/migration state, browser sessions, and minimal privacy-reviewed operational projections |
| Practice tenant database | Sites and tenant configuration; patients; schedules and queues; charts; encounters; prescriptions; orders/results; referrals; documents metadata; tenant audit; tenant events/jobs and idempotency records |
| Private S3 | Tenant-prefixed documents and attachments, authorized through the same Practice context as their metadata |

Retain `practice_id` on tenant-owned records even though each tenant database contains one Practice. It provides defense-in-depth, makes exports and audit evidence self-describing, and supports tenant/patient-consistent foreign keys.

## Trusted routing

Public APIs continue to use `/api/v1/practices/{practiceId}/...`. For every request, the server must:

1. Authenticate the actor.
2. Resolve current membership or managed-administration authority from the control plane.
3. Resolve `practiceId` to a server-owned database and Secrets Manager reference.
4. Reject missing, disabled, migrating or mismatched routes.
5. Execute the command or query using only that tenant's runtime role.

Clients never provide a database name, JDBC URL, schema, RDS endpoint or credential selector. A tenant runtime role cannot connect to another tenant database and never owns tables. Shared tenant-scoped control-plane or operational tables require RLS with `FORCE ROW LEVEL SECURITY` as defense-in-depth.

## Provisioning and migrations

Tenant provisioning is a recoverable workflow that creates the database, runtime and migration roles, secret references, schema, initial tenant administrator and registry state. Partial provisioning remains non-routable until verification succeeds.

Flyway runs as an observable tenant-fleet operation: canary databases first, bounded concurrency, per-tenant schema status, retryable failures and explicit quarantine. Application releases remain compatible with the oldest supported in-flight schema during rollout. A failed tenant migration must not roll back or block already healthy tenants.

## Connections, transactions and aggregation

Do not keep an unbounded permanent connection pool for every tenant. Tenant datasources open lazily, use bounded pools, evict when idle and keep aggregate connections below the RDS limit.

A critical clinical command, its audit entry, idempotency record and durable event belong in one transaction inside the affected tenant database. Do not design a clinical invariant that requires a distributed transaction across tenant and control-plane databases.

Cross-practice worklists query each authorized tenant independently or use a minimal, privacy-reviewed control-plane projection. They do not perform cross-database chart joins or copy complete patient records into the control plane.

## Backup, restore and relocation

Back up, restore and verify each tenant database independently together with its S3 objects, audit relationships and encryption keys. Release gates must prove that restoring one Practice does not overwrite or interrupt another.

Relocation to dedicated infrastructure uses a rehearsed quiesce, copy, integrity verification and registry cutover. The old route remains unavailable for writes after cutover.

## Consequences

- Database credentials and routing add an isolation boundary beyond application authorization.
- Tenant-specific export, restore and infrastructure relocation are simpler.
- Provisioning, secrets, fleet migrations, connection budgets and operational monitoring are materially more complex than a shared-table model.
- Tenants sharing an RDS instance still share capacity, maintenance and failure domains until moved to dedicated infrastructure.
- Database isolation does not grant access: application-level practice, site, role, care-relationship and field authorization remains mandatory.

## Current implementation status

The local Spring implementation now has a control-plane Practice registry, membership with separate multi-role assignments, and append-only provisioning history; idempotent leased provisioning; deterministic server-owned database and role names; distinct non-owning runtime and migration roles; separate tenant Flyway migrations; tenant-identity verification; quarantined failures; and an encrypted local-only credential adapter. An initial administrator may be a clinician or non-clinical Practice user, while provider-workforce authority cannot be converted into tenant membership. Superadmin REST endpoints create and inspect Practice tenants without exposing database routes or secrets. PostgreSQL Testcontainers cover retry behavior, unsafe-role rejection, runtime verification, and denial of cross-tenant database connections.

This remains a development provisioning slice, not the complete production topology. AWS Secrets Manager integration, a restricted RDS administrative adapter, bounded lazy runtime datasource pools, tenant-route authorization for clinical modules, controlled canary/fleet migrations, relocation, backup/restore automation, and production operations evidence remain unimplemented.
