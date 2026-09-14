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
| `identitytenancy` | Users, the Practice tenant registry, memberships, provisioning state, coverage, and access policy |
| `patientregistry` | Patient identity, identifiers, duplicate review, and identity links |
| `schedulingqueue` | Appointments, practice sessions, queue entries, and transitions |
| `clinicalchart` | Problems, allergies, observations, and medication reconciliation |
| `encountertemplates` | Encounter lifecycle, templates, revisions, signing, and amendments |
| `prescribing` | Prescription creation, issuance, renewal, and cancellation |
| `ordersresults` | Requisitions, report matching, review, corrections, and ownership |
| `carecoordination` | Clinical inbox, referrals, follow-up, and escalation |
| `documentscommunication` | Attachments, certificates, exports, releases, and communication history |
| `platformoperations` | Audit, durable jobs/events, tenant database provisioning, and operational controls |

All modules are explicitly annotated and closed. They initially declare no allowed cross-module dependencies. When a feature needs collaboration, expose a narrow module API or named interface and declare that exact dependency; Spring Modulith verification fails the build for cycles, internal-package access, or undeclared dependencies.

## Build

```bash
./mvnw verify
```

The Maven Enforcer rule requires Java 25 and Maven 3.9.x. The wrapper downloads the pinned Maven distribution on first use.

## Run locally

Start PostgreSQL and the local domain proxy, then run the API:

```bash
docker compose up --build --detach --wait postgres proxy
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

Run the Vite development server in another terminal with `npm --prefix ../web run dev`. With `epr.test` and `support.epr.test` mapped to `127.0.0.1` in `/etc/hosts`, Nginx exposes:

- `http://epr.test` for physicians and Practice staff.
- `http://support.epr.test` for provider support.
- Same-origin `/api/...` requests from both hosts to the Spring API on port 8080.

The proxy uses the pinned official Nginx image and rejects unknown hostnames. The default port is 80; set `EPR_PROXY_PORT` if port 80 is unavailable, then include that port in both browser URLs. Docker Desktop publishes the proxy on the host interfaces so the port-80 mapping works; use this stack only with synthetic local data and stop it when it is not needed. Local HTTP requires `EPR_SECURE_COOKIES=false`. Production must use HTTPS and secure cookies.

The local profile and Compose defaults use matching development-only credentials. Copy `.env.example` to `.env` to customize the container; export the same changed values before starting Spring Boot. The database is exposed only on loopback and its files persist in the `postgres-data` volume. The `epr` database is the local control plane. Creating a Practice through the provider-administration API creates a separate logical database plus distinct runtime and migration roles on this PostgreSQL instance.

Stop the local containers without deleting database data:

```bash
docker compose down
```

The initialization script creates three distinct control-plane roles:

- `epr_owner` initializes the local database and is not used by the application.
- `epr_migrator` owns Flyway-created tables and schema changes.
- `epr_app` is the non-owning application runtime role and cannot bypass row-level security.

Initialization scripts run only when the data volume is first created. Changing role names or passwords later does not rewrite an existing database volume. Deployed environments must provide secret-managed credentials rather than these local defaults, and must keep the table-owning migration role separate from the runtime role.

The default security policy exposes only health/info and the CSRF/login entry points anonymously and requires authentication for every other route. The `local` profile enables a development-only username/password adapter and seeds one global `SUPERADMIN` workforce identity. The default credentials are:

```text
username: superadmin
password: ChangeMe123!
```

Override them with `EPR_LOCAL_SUPERADMIN_USERNAME`, `EPR_LOCAL_SUPERADMIN_PASSWORD`, and `EPR_LOCAL_SUPERADMIN_DISPLAY_NAME`. The seeder hashes the configured password with BCrypt and updates the local account on each start. This adapter is not the production authentication design: Cognito authorization code flow with PKCE and MFA, tenant authorization/grants, clinical endpoints, and domain persistence remain to be implemented.

The browser flow uses `/api/v1/auth/csrf`, `/api/v1/auth/login`, `/api/v1/auth/session`, and `/api/v1/auth/logout`. The `EPR_SESSION` cookie is `HttpOnly`; login/logout require the returned CSRF header and token. “Keep me signed in” extends the local session to 30 days and emits a persistent session cookie.

## Create a Practice tenant locally

Tenant creation is restricted to a currently enabled `SUPERADMIN` and requires a UUID idempotency key. The initial administrator must already be an enabled `CLINICIAN` or non-clinical `PRACTICE_STAFF` platform account; provider authority is never converted into Practice membership or chart access. The resulting `PRACTICE_ADMINISTRATOR` membership manages the workspace but does not itself grant clinical-record access. After login and CSRF setup, send:

```http
POST /api/v1/admin/practices
Idempotency-Key: 313b5410-3fab-4b2c-8eaa-2c5ae6b619d9
Content-Type: application/json
X-XSRF-TOKEN: <current token>

{
  "practiceId": "3b9c3672-142d-44e0-8404-e37faf43f870",
  "practiceCode": "makati-family-clinic",
  "displayName": "Makati Family Clinic",
  "initialAdministratorUserId": "<enabled platform user UUID>"
}
```

`201 Created` means role creation, database creation, the tenant Flyway migration, tenant-identity verification, registry activation, and initial membership creation all completed. Repeating the same request and idempotency key is safe. A request already in progress returns `202 Accepted`; inspect it with `GET /api/v1/admin/practices/{practiceId}`. Failures remain `QUARANTINED`, non-routable, and retryable with the original key. The response intentionally omits database names, JDBC routes, usernames, and secret references.

Local provisioning uses the Compose database-owner credential for PostgreSQL administration and an AES-GCM-encrypted development credential store in the control plane. Set `EPR_TENANT_CREDENTIAL_ENCRYPTION_KEY` to a Base64-encoded 32-byte value before using shared development infrastructure. This adapter is not the production secret design: production enablement remains blocked until the AWS Secrets Manager adapter, restricted RDS provisioning role, fleet migration controller, and bounded runtime datasource pool are implemented and verified.

## Database migrations

Flyway connects as `epr_migrator` and owns control-plane schema changes. The main datasource connects as `epr_app`; Hibernate runs with `ddl-auto: validate`. Control-plane migrations create framework tables, global platform users, the tenant registry, Practice memberships with separate multi-role assignments, append-only provisioning events, and the encrypted local credential adapter. Tenant migrations are kept separately under `db/tenant-migration` and currently create the verified tenant identity plus the initial administrator reference. Runtime tenant datasource lifecycle, AWS Secrets Manager, RDS/IAM administration, and controlled fleet migrations remain to be implemented before production use.
