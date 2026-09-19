# Saved patient registration

Implemented 2026-09-19 for the opt-in, local synthetic clinician workflow. This implements the [registration plan](patient-registration-database-implementation-plan.md) without enabling real-patient use or staff registration.

## Delivered behavior

- `/patients` uses the existing Dashboard-based directory composition with authorized Practice choices, server search, identity/sex/visit filters, sorting, pagination, desktop preview and tablet/mobile detail sheets. The directory endpoint has a separate page response; legacy `/patients` array consumers are not silently given a new shape.
- `/patients/new?scope={practiceId}/{siteId}` uses the existing six-section presentation with a separate saved-record repository. All enabled demographic, contact, address, PhilHealth, representative/emergency-contact and identifier fields persist. Unknown birth dates remain null; estimated dates retain their label. Existing unsplit names remain intact rather than being guessed into name parts.
- One save action registers the patient and returns its UUID, MRN and version. The destination is either the saved chart or encounter setup. Neither registration nor chart review creates care. Direct chart/setup routes retrieve the patient by ID, independently of directory pagination.
- `/clinical/{practiceId}/{siteId}/patients/{patientId}/edit` uses versioned corrections, a required reason, protected before/after evidence and explicit conflict comparison. Existing identifiers are returned only as masks; unchanged/replace/remove operations prevent saving a mask as an identifier.
- Duplicate reviews are server-held, expire after ten minutes, and bind identity fields, candidate IDs/versions, actor, site and Practice. Registration and editing serialize against a Practice registration lock, then recheck candidates inside the command transaction. Selecting an existing authorized candidate or deliberately saving a separate record records the decision. The new public create contract has no boolean bypass.
- Patient creation, care assignment, fields, correction evidence, audit and idempotency receipt commit together. Unconfirmed browser submissions retain their original UUID, key and payload and lock editing until retry resolves the outcome. Confirmed rejection permits correction and a new command. Encounter creation retains its separate command and retry mechanism.
- Recent charts are actor/Practice scoped, reauthorized, limited to 50 entries and a 30-day visible window. Chart opens are explicit and idempotent; previews and preloads do not count. Expired entries are pruned on chart-open activity; dormant tenants still require scheduled retention operations before production.
- Global patient search uses saved records from separately authorized Practices and preserves the centered search dialog, keyboard navigation, Escape and focus restoration. Historical fixture forms, charts, notes and encounters are confined to `/patients/preview/...`.

## Authorization and storage

Control-plane migrations V13–V14 add four explicit patient capabilities to existing clinician/site grants and initialize only the opt-in synthetic fixture. Subsequent seeder restarts do not restore revoked patient capabilities. Every endpoint rechecks current clinician membership, Practice service, site, capability and care assignment as applicable, including command replay. Technical administrators gain no patient permissions. Registration creates an assignment only for the authenticated clinician.

Tenant migrations V6–V7 add relational registration columns, protected identifier storage, append-only demographic revisions, duplicate-review evidence, recent opens and registry-owned visit projections. Existing UUIDs, MRNs, clinical foreign keys and signed artifacts remain unchanged. MRNs retain the existing sequence-backed allocator. This registration slice introduced **schema 7**. The [intervention extension](encounter-interventions-implementation.md) advances current routing, scope discovery, bootstrap and upgrade checks to **schema 11**; earlier schemas fail closed until upgraded through the existing quarantined rollout workflow.

The local identifier/evidence adapter uses AES-GCM with Practice/patient-bound authenticated data and a separate `EPR_PATIENT_ENCRYPTION_KEY`. Its checked-in default is exclusively for synthetic local development. This is not production key management, rotation or restore evidence. Identifier verification remains `NOT_VERIFIED`; recording PhilHealth information makes no eligibility claim. Uploads remain visibly unavailable.

## Visit summaries and boundaries

The encounter module reconciles its own records and calls the public patient-registry projection interface; the registry never queries encounter tables. The projection retains encounter ID/version and service timestamp, deduplicates sources, and is scoped to clinician, authorized location and reproductive-access state. Encounter creation, draft saves and care transitions invalidate its completeness marker. Directory refresh rebuilds it under the encounter clinician lock; stale/missing/failed reconciliation cannot match the “No recorded visits” filter. Reads expose refresh state, and failed refresh remains visibly unavailable.

Reconciliation is demand-driven and bounded at 10,000 source encounters per clinician/location. Overflow fails closed. Summaries and detailed history describe the selected authorized location, not every clinician's encounters across the Practice. Queue summaries remain explicitly unavailable in the patient preview. A fleet/background reconciliation mechanism, broader chart-sharing policy, and production load evidence remain gates.

Duplicate matching is deliberately limited to the clinician's authorized assigned patients: normalized name, supplied phone, or birth date with a family-name match, bounded at 20 candidates. Larger candidate sets require refinement. It neither exposes another clinician's patients nor implements the later registration-only identity-review workflow. Staff registration needs its separate permissions and responsible-clinician assignment workflow.

## Verification and operation

From the repository root:

```sh
./api/mvnw -f api/pom.xml verify
EPR_CONTRACT_BASE_URL=http://localhost:8082 node scripts/export-clinical-openapi.mjs
node scripts/generate-clinical-contracts.mjs
npm --prefix web run lint
npm --prefix web run test
npm --prefix web run build
```

The contract generator's explicit schema list includes the registration, detail, duplicate-review, directory and identifier-operation DTOs. Generated output is exported from the running API rather than handwritten.

For an isolated local development run alongside the existing ports:

```sh
java -jar api/target/backend-api-0.0.1-SNAPSHOT.jar --spring.profiles.active=local --epr.clinical.enabled=true --epr.clinical.demo-seed=true --server.port=8082
EPR_API_PROXY_TARGET=http://127.0.0.1:8082 npm --prefix web run dev -- --host localhost --port 5175
```

Use the synthetic account described in [connected encounter operation](encounter-templates-obgyn-implementation.md). Registration is available at `http://localhost:5175/#/patients`.

Automated verification passed: 74 backend tests, including nine registration tests plus owner-service visit-projection checks, and 88 frontend tests. PostgreSQL coverage includes all-section round trips, masked operations, immutable correction evidence, retries, concurrent same-key creation, stale/expired duplicate reviews, candidate binding, more than 100 searchable records, two Practices, two clinicians, permission revocation, isolation and schema quarantine/recovery. Existing signed-original and amendment tests still pass. Maven exits successfully; the existing Surefire shutdown warning remains visible after its test JVM shutdown timeout.

Browser verification uses temporary Playwright scripts outside the repository because the Browser plugin is unavailable. It exercises the real PostgreSQL API with synthetic records. Required sizes are desktop 1440×1000, portrait tablet 834×1194 and mobile 390×844. Evidence includes registration validation, save/reload, edit/reload, duplicate review, both destinations, directory selection, global search and keyboard navigation. Additional desktop cases cover a lost response after commit with exact retry, a two-editor version conflict, registration → encounter draft save → reload, and a 00:15 Manila service time persisted as 16:15 UTC on the preceding day. All three layouts passed. A separate browser fault-injection check also verified that a failed conflict reload preserves the draft and allows retry on each layout. The mobile fixed-save-bar clearance was corrected after a blocked duplicate-review checkbox was found. Final checks confirmed no horizontal overflow, visible MRNs, sticky headers and keyboard search; screenshots were inspected.

## Remaining release gates

The implementation retains local-profile controllers, explicit synthetic capability and schema constraints. Production routing/authentication, MFA, staff operational authorization, managed encryption/keys, export, audit operations, scheduled retention, independent tenant/file/key restore, downtime reconciliation, clinician-approved outputs and representative load must be completed before real-patient use. Production patient-number formatting remains a separate decision. No synthetic record is relabeled as real.
