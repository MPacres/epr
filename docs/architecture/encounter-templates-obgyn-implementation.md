# Connected encounter templates: implementation and validation

Implemented against [the OB-GYN plan](encounter-templates-obgyn-implementation-plan.md). This is an opt-in, persisted **synthetic development workflow**, not a clinical release or a complete EPR. The existing historical demonstration remains separate; its display IDs and signed-note metadata are not imported into the connected records.

## Implemented behavior

- Current clinician membership, explicit site/reproductive/approver grants, care assignment, enabled Practice and schema status authorize every tenant request. Support access does not confer chart access. At most four lazily opened tenant pools, at most two connections each, use runtime credentials and verify the database's Practice identity and site before work. Idle pools are evicted; busy pools are retained.
- Minimal persisted patient registration and reviewed duplicate acknowledgment; UUID identity and server-assigned synthetic record numbers. Registration is exposed through the API; the connected directory currently lists up to 100 authorized patients.
- Strict versioned definition/answer contracts, bounded module composition and flattening, stable field identities, three-valued declarative rules, missing states, provenance, repeated-item UUIDs, and draft/sign validation. Edited definitions invalidate approval. Publication pins an immutable definition/checksum; site retirement prevents new use while retaining historical access. Composition is code-driven; a persisted reusable-module authoring UI and visual builder are not implemented.
- Six explicitly provisional releases: general outpatient; prenatal initial/follow-up; gynecology initial/follow-up; postpartum follow-up. These exercise the engine with synthetic narrative and structured fields. Their approval records explicitly describe engineering approval, not clinical content approval.
- Persisted pregnancy episodes, immutable dating evidence, reasoned/versioned dating selection, and patient obstetric-history corrections. Encounter creation captures its source snapshot. A later dating correction does not update the captured encounter or archived note.
- Non-queued and queued encounter creation; release, patient, site, clinician and episode are pinned. Serialized saves append immutable response revisions. Retries retain the original command key/body/time. Version conflicts require an explicit server/local choice. Rejected validation input can be corrected; ambiguous transport failures retain their original command. Open form memory is not an offline store.
- Explicit session activation, queue readiness, transactional queue/encounter handoff and independent queue completion. Preview does not activate a session. The Queue badge counts only ready entries in the selected `IN_PROGRESS` session at the authorized scope.
- Cross-Practice active-care reservations use a control-plane fence and never expire automatically. A tenant rollback retains the reservation. Retry resumes the original start; cancellation commits a tenant tombstone before releasing a verified failed-start fence. Committed encounters must be resumed. Finish commits in the tenant before reservation release; retry/reconciliation repairs the intervening crash window. Paused care retains its reservation.
- Revision-bound sign preparation, exact-byte private artifacts, deliberate local synthetic confirmation, immutable signatures and linked amendments. Signed views read the archived bytes; missing or altered artifacts fail explicitly. Editing a prepared candidate invalidates it. Signing, care finish, queue completion and follow-up resolution are separate.
- The authoritative encounter owns unsigned documentation. Explicit follow-up intents commit with an outbox event and initially report `PENDING`. The worklist's demand-driven reconciliation applies current authorized obligations, deduplicates tasks, and acknowledges delivery in one tenant transaction. Replays do not reopen a resolved follow-up. Signing resolves only documentation work. The current UI supports same-clinician ownership; delegation/reassignment and a continuously running fleet dispatcher remain unimplemented.

The connected UI is available at `/#/clinical`; scoped queue and worklist routes are reachable through the existing shell. It uses the Dashboard's Roboto/Lucide, colors, surfaces and responsive navigation. Unsupported broader clinical workflows remain explicit.

## Contracts and ownership

[Clinical OpenAPI](../../api/contracts/clinical-openapi.json) is exported from the running local API. [Template JSON Schema v1](../../api/contracts/template-definition-v1.schema.json) documents the current non-executable fixture contract; the Java compiler additionally enforces semantic bounds, dependencies and identity compatibility. Java and TypeScript run [the same rule vectors](../../api/src/main/resources/encounter-templates/rule-v1-vectors.json).

Module dependencies are enforced by `ModularityTests`. `identitytenancy` owns authorization, reservations and session selection; `platformoperations` owns routing, credentials, command receipts, audit/outbox and schema operations; patient/pregnancy/encounter/queue/document/coordination modules own their respective tables. Cross-module work calls public interfaces in one explicit tenant transaction. Browser sessions contain no selected Practice, tenant route or clinical draft.

Runtime commands use `/api/v1/practices/{practiceId}/...`, a separately validated `siteId`, and `Idempotency-Key` for consequential commands. There is no client-supplied database selector. Artifacts have the same patient, encounter, site, clinician and reproductive-access checks as their source records.

## Local operation

Use the PostgreSQL/local-auth setup in [the API README](../../api/README.md), Java 25 and the existing Maven wrapper. To run alongside an existing API on port 8080:

```sh
./api/mvnw -f api/pom.xml spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.arguments='--epr.clinical.enabled=true --epr.clinical.demo-seed=true --server.port=8081'
EPR_API_PROXY_TARGET=http://127.0.0.1:8081 npm --prefix web run dev -- --host localhost --port 5174
```

For the normal `epr.test` URL, run the enabled API on port 8080 (stop the existing API on that port first); the default Vite proxy already targets it:

```sh
./api/mvnw -f api/pom.xml spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.arguments='--epr.clinical.enabled=true --epr.clinical.demo-seed=true --server.port=8080'
```

**My Patients** (`/patients`) opens the saved authorized directory. Select a patient, then **New encounter** in their chart: the patient and Practice remain in the route, with no repeated patient search or Practice-selection step. Encounter setup includes a location dropdown limited to authorized sites in that Practice and explicit date/time of service, defaulted and interpreted in Asia/Manila. Queued encounters keep their queue session’s location. The Dashboard’s **New encounter** action opens `/clinical?start=1` for cases where no patient has been selected yet. Historical demo charts and registration previews remain available at `/patients/preview`; their encounter creation action is disabled, and fixture patients are never imported or linked automatically. Accounts without explicit clinical grants see an access explanation and a sign-out action. No additional clinical authority is granted by these routes.

Open `http://localhost:5174/#/clinical`. The opt-in seeder creates `obgyn-demo@epr.test` with the development password `ClinicalDemo123!`; override it before first seed with `EPR_CLINICAL_DEMO_PASSWORD`. Clinical capability and seeding default to false and controllers require the local profile. The seeder does not re-enable a revoked grant. Use synthetic data only.

The local document adapter writes private, hash-verified HTML artifacts under `~/.local/share/epr/synthetic-artifacts`, or `epr.clinical.artifact-directory`. Files and directories are restricted to the local user; writes use a temporary file, fsync and atomic move. PostgreSQL and the filesystem are not one transaction. Orphan staged files may require reconciliation; never delete files referenced by a signed record. This adapter is not S3 archival, PDF signing, cryptographic signing or approved legal assurance.

Export/generate contracts from the enabled local API:

```sh
node scripts/export-clinical-openapi.mjs
node scripts/generate-clinical-contracts.mjs
node scripts/generate-template-schema.mjs
```

The exporter only accepts localhost/127.0.0.1, uses an in-memory login cookie, and writes no session credentials. `EPR_CONTRACT_BASE_URL`, `EPR_CONTRACT_USERNAME`, and `EPR_CLINICAL_DEMO_PASSWORD` override its development defaults. Do not run concurrent Maven builds against the same `api/target` directory.

## Additive schema rollout and rollback

Control-plane migrations V9–V12 add explicit grants, active-care/session references and rollout history. Tenant migrations V3–V5 add the clinical records. New tenant provisioning applies these additive migrations without enabling clinical access. Clinical routing now requires tenant schema `11`; see [the intervention extension](encounter-interventions-implementation.md) for V8–V11 and [the patient-registration extension](patient-registration-implementation.md) for V6–V7 and patient capabilities.

The local support-only `POST /api/v1/admin/practices/{practiceId}/schema-upgrade` command takes an operation UUID in `Idempotency-Key`, plus `expectedVersion` and `reason` in the JSON body. Run one canary Practice first, verify it, then proceed serially; this endpoint is not an automated fleet orchestrator. A control-plane claim marks the route migrating, Flyway runs with that tenant's migration credential, and completion records `READY` or `QUARANTINED`. Attempts are fenced; an expired migration claim permits recovery, never ordinary clinical routing. Verify tenant identity and Flyway history before retrying a quarantined Practice. Rollout events are append-only.

Rollback disables new clinical use or retires the affected release. Do not reverse migrations, drop clinical tables, overwrite releases, change signed bytes, or silently retarget drafts. Preserve artifact readers and restore the complete database/file/credential set when recovering a local environment. Production restore and fleet drills have not been performed.

## Validation performed

- `./api/mvnw -f api/pom.xml verify`: 65 backend tests passed across PostgreSQL Testcontainers, existing authentication/provisioning/isolation suites, module boundaries, template compiler and shared rules, two visits/one episode, immutable revisions, approval invalidation, tenant/site/patient and membership denial, same-key concurrent starts, two-Practice race, failed-start cancellation fencing, queue handoff/readiness, stale sign preparation, artifact alteration/unavailability, signed-original preservation, linked amendments, delivery deduplication, independent follow-up resolution, and migration quarantine.
- `npm --prefix web run lint`, `npm --prefix web run test`, `npm --prefix web run build`: current scripts: 81 frontend tests passed; draft-writer tests include exact timeout replay, edits during a request, explicit conflict handling and correction after rejected validation.
- Browser plugin not available. Temporary Playwright scripts used installed Chrome against the real local API, with synthetic authentication and records. Desktop 1440×1000, portrait tablet 834×1194 and mobile 390×844 were exercised and screenshots inspected. Checks covered saved reloads, exact-artifact review/confirmation, archived viewing, real queue handoff, follow-up acknowledgment, source dating persistence, release dialogs/Escape/focus restoration, draft conflict comparison and disconnected-save retry. Rejected numeric input was corrected and saved, and pregnancy/history/follow-up navigation guards preserved unsent text. Additional 320px and 200% zoom checks exercised reflow/focus. Shared-shell Dashboard checks covered all three sizes. Temporary evidence stays outside committed source.

## Remaining gates and scope limits

A/H clinical content approvals and G production signing approval remain open. The provisional pack does not claim a clinically complete minimum dataset, validated calculations, full multiple-gestation/fetus/outcome modeling or approved document samples. Extraction mapping is deliberately `none`: a quantity entered in a note is provisional and does not silently become a confirmed chart observation. Add clinically approved mappings and owning-service confirmation before enabling reusable extracted facts.

Other unimplemented plan elements include governed persisted reusable-module editing, clinician template preferences/defaults, restricted staff contribution/redaction, advanced session transfer/reopening policies, richer authoring administration, production private/versioned S3 and signer adapters, automatic fleet delivery/monitoring, ownership reassignment, and production scheduling/administration ergonomics. Patient directory pagination/search is now implemented by [the registration extension](patient-registration-implementation.md); other scope worklists remain bounded development lists.

Pilot package I and visual-builder package J are not complete. Clinical output review, current deployment governance, MFA/Cognito, secure export, independent tenant/file/key restore, downtime reconciliation, representative load, and the broader prescribing/orders/results/referrals workflow gates still apply. Passing these engineering checks does not authorize real-patient use.
