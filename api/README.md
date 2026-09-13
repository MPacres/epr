# EPR API

Java 25 / Spring Boot API for the doctor-centric Philippine outpatient EPR. The application is a strict Spring Modulith modular monolith; it is one deployable application with independently owned business modules.

## Versions

- Java 25
- Spring Boot 4.1.1
- Spring Modulith 2.1.1
- Maven 3.9.16 through the checked-in wrapper
- PostgreSQL 17.11 development image; PostgreSQL 17 target

## Business modules

The packages directly below `ph.epr.api` define the initial module boundaries:

| Module | Responsibility |
| --- | --- |
| `identitytenancy` | Users, practices, sites, memberships, coverage, and access policy |
| `patientregistry` | Patient identity, identifiers, duplicate review, and identity links |
| `schedulingqueue` | Appointments, practice sessions, queue entries, and transitions |
| `clinicalchart` | Problems, allergies, observations, and medication reconciliation |
| `encountertemplates` | Encounter lifecycle, templates, revisions, signing, and amendments |
| `prescribing` | Prescription creation, issuance, renewal, and cancellation |
| `ordersresults` | Requisitions, report matching, review, corrections, and ownership |
| `carecoordination` | Clinical inbox, referrals, follow-up, and escalation |
| `documentscommunication` | Attachments, certificates, exports, releases, and communication history |
| `platformoperations` | Audit, durable jobs/events, provisioning, and operational controls |

All modules are explicitly annotated and closed. They initially declare no allowed cross-module dependencies. When a feature needs collaboration, expose a narrow module API or named interface and declare that exact dependency; Spring Modulith verification fails the build for cycles, internal-package access, or undeclared dependencies.

## Build

```bash
./mvnw verify
```

The Maven Enforcer rule requires Java 25 and Maven 3.9.x. The wrapper downloads the pinned Maven distribution on first use.

## Run locally

Start the development PostgreSQL container:

```bash
docker compose up --build --detach --wait postgres
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

The local profile and Compose defaults use matching development-only credentials. Copy `.env.example` to `.env` to customize the container; export the same changed values before starting Spring Boot. The database is exposed only on loopback and its files persist in the `postgres-data` volume.

Stop the database without deleting its data:

```bash
docker compose down
```

The initialization script creates three distinct roles:

- `epr_owner` initializes the local database and is not used by the application.
- `epr_migrator` owns Flyway-created tables and schema changes.
- `epr_app` is the non-owning application runtime role and cannot bypass row-level security.

Initialization scripts run only when the data volume is first created. Changing role names or passwords later does not rewrite an existing database volume. Deployed environments must provide secret-managed credentials rather than these local defaults, and must keep the table-owning migration role separate from the runtime role.

The default security policy exposes only `/actuator/health` and `/actuator/info` anonymously and requires authentication for every other route. Cognito login, tenant authorization, clinical endpoints, and domain persistence are intentionally not represented as implemented by this foundation scaffold.

## Database migrations

Flyway connects as `epr_migrator` and owns schema changes. The main datasource connects as `epr_app`; Hibernate runs with `ddl-auto: validate`. The first migration creates only the framework tables for PostgreSQL-backed browser sessions and durable Modulith event publications. Business tables and their RLS policies belong in feature migrations owned by the corresponding module.
