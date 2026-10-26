# Connected encounter templates: implementation and validation

Implemented against [the OB-GYN plan](encounter-templates-obgyn-implementation-plan.md). This is an opt-in, persisted **synthetic development workflow**, not a clinical release or a complete EPR. The existing historical demonstration remains separate; its display IDs and signed-note metadata are not imported into the connected records.

## Implemented behavior

- Current clinician membership, explicit site/reproductive/approver grants, care assignment, enabled Practice and schema status authorize every tenant request. Support access does not confer chart access. At most four lazily opened tenant pools, at most two connections each, use runtime credentials and verify the database's Practice identity and site before work. Idle pools are evicted; busy pools are retained.
- Minimal persisted patient registration and reviewed duplicate acknowledgment; UUID identity and server-assigned synthetic record numbers. Registration is exposed through the API; the connected directory currently lists up to 100 authorized patients.
- Strict versioned definition/answer contracts, bounded module composition and flattening, stable field identities, three-valued declarative rules, missing states, provenance, repeated-item UUIDs, and draft/sign validation. Edited definitions invalidate approval. Publication pins an immutable definition/checksum; site retirement prevents new use while retaining historical access. Composition is code-driven; a persisted reusable-module authoring UI and visual builder are not implemented.
- Six explicitly provisional releases: general outpatient; prenatal initial/follow-up; gynecology initial/follow-up; postpartum follow-up. These exercise the engine with synthetic narrative and structured fields. Their approval records explicitly describe engineering approval, not clinical content approval.
- Persisted pregnancy episodes, immutable dating evidence, reasoned/versioned dating selection, and patient obstetric-history corrections. Encounter creation captures its source snapshot. A later dating correction does not update the captured encounter or archived note.
- Non-queued and queued encounter creation; release, patient, site, clinician and episode are pinned. Serialized saves append immutable response revisions. Retries retain the original command key/body/time. Version conflicts require an explicit server/local choice. Rejected validation input can be corrected; ambiguous transport failures retain their original command. Open form memory is not an offline store.
- Tenant V19 permits a clinician to soft delete their own in-progress or paused, non-queued `DRAFT` encounter from the patient chart. The versioned, idempotent command retains the encounter, response revisions, actor, reason and audit event; active history, visit projections, vital timeline and owned-note work omit it. Signed, finished, queued, prepared-for-signing or clinically linked encounters are rejected. The active-care fence is released only after the tenant commit. This local synthetic capability does not authorize real-patient deletion.
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

**My Patients** (`/patients`) opens the saved authorized directory and is the clinician sign-in destination. Select a patient, then **New encounter** in their chart: the patient and Practice remain in the route, with no repeated patient search or Practice-selection step. An active encounter offers **Resume encounter** directly from the chart. Setup asks for the visit template and care mode, with site and Asia/Manila service time visible in a compact summary and editable on demand. Queued encounters keep their queue session’s location. The clinical-host Dashboard now shows connected scope and work status instead of fixture counts. Historical demo charts and registration previews remain separately available at `/patients/preview`; their encounter creation action is disabled, and fixture patients are never imported or linked automatically. Accounts without explicit clinical grants see an access explanation and a sign-out action. No additional clinical authority is granted by these routes.

Open `http://localhost:5174/#/clinical`. The opt-in seeder creates `obgyn-demo@epr.test` with the development password `ClinicalDemo123!`; override it before first seed with `EPR_CLINICAL_DEMO_PASSWORD`. Clinical capability and seeding default to false and controllers require the local profile. The seeder does not re-enable a revoked grant. Use synthetic data only.

The demo-account seeder now starts without a patient or pregnancy fixture. Set `EPR_CLINICAL_DEMO_PATIENT_SEED=true` only when a development fixture is needed: it adds synthetic Sofia Dela Cruz with one ongoing episode with approximate, **not reviewed** dating evidence, one completed prior episode with unknown outcome, and one **not reviewed** obstetric-history account. The fixture dates and narratives are invented for development; no dating basis is selected automatically. The local clinician, Practice, site and template pack are created in either mode. The default names are Client Demo Practice, Main Clinic and Dr. Isabel Reyes; `EPR_CLINICAL_DEMO_PRACTICE_NAME`, `EPR_CLINICAL_DEMO_SITE_NAME` and `EPR_CLINICAL_DEMO_CLINICIAN_NAME` can override them. These settings do not remove existing records; reset an isolated local development database only after preserving any needed data and archived artifacts. The application remains demo-only and rejects real-patient registration and issuance.

The local document adapter writes private, hash-verified HTML artifacts under `~/.local/share/epr/synthetic-artifacts`, or `epr.clinical.artifact-directory`. Files and directories are restricted to the local user; writes use a temporary file, fsync and atomic move. PostgreSQL and the filesystem are not one transaction. Orphan staged files may require reconciliation; never delete files referenced by a signed record. This adapter is not S3 archival, PDF signing, cryptographic signing or approved legal assurance.

Export/generate contracts from the enabled local API:

```sh
node scripts/export-clinical-openapi.mjs
node scripts/generate-clinical-contracts.mjs
node scripts/generate-template-schema.mjs
```

The exporter only accepts localhost/127.0.0.1, uses an in-memory login cookie, and writes no session credentials. `EPR_CONTRACT_BASE_URL`, `EPR_CONTRACT_USERNAME`, and `EPR_CLINICAL_DEMO_PASSWORD` override its development defaults. Do not run concurrent Maven builds against the same `api/target` directory.

## Seed templates for an existing synthetic Practice site

The demo-account seeder only populates its own synthetic Practice. It does not populate a newly created Practice. Encounter-type options are derived from the selected site's published releases; there is no separate encounter-type lookup table to seed.

`LocalExistingPracticeTemplateSeeder` installs the existing provisional pack at one explicitly configured Practice/site. It is restricted to the `local` profile and requires an enabled synthetic clinical capability, schema 19, and a current clinician membership with explicit reproductive and template-approver access at that site. It does not grant access, create patients, or enable clinical capability. A doctor or Practice-administrator role alone is insufficient.

Run this one-shot command from the repository root, replacing the three scope values with the intended existing scope. It starts on an ephemeral port, leaving the normal API running, and exits after a successful seed. Supply the same database/credential configuration as the normal local API. Do not run it concurrently with another Maven build using `api/target`.

```sh
./api/mvnw -f api/pom.xml spring-boot:run \
  -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments='--server.port=0 --epr.clinical.enabled=true --epr.interventions.worker-enabled=false --epr.clinical.template-pack-seed.enabled=true --epr.clinical.template-pack-seed.practice-id=<practice-uuid> --epr.clinical.template-pack-seed.site-id=<site-uuid> --epr.clinical.template-pack-seed.username=<authorized-clinician-username> --epr.clinical.template-pack-seed.exit-after-seed=true'
```

| Specialty | Encounter type | Template key | Current synthetic new-encounter version |
| --- | --- | --- | --- |
| General | `OUTPATIENT` | `general.outpatient` | `1.2.0` |
| OB-GYN | `PRENATAL_INITIAL` | `obgyn.prenatal.initial` | `1.2.0` |
| OB-GYN | `PRENATAL_FOLLOWUP` | `obgyn.prenatal.followup` | `1.2.0` |
| OB-GYN | `GYNECOLOGY_INITIAL` | `obgyn.gynecology.initial` | `1.2.0` |
| OB-GYN | `GYNECOLOGY_FOLLOWUP` | `obgyn.gynecology.followup` | `1.2.0` |
| OB-GYN | `POSTPARTUM_FOLLOWUP` | `obgyn.postpartum.followup` | `1.2.0` |

These are application categories consistent with the accepted initial catalog, not a national coding standard. The selection was checked on 20 October 2026 against [Southern Philippines Medical Center's prenatal, gynecology and postpartum services](https://spmc.doh.gov.ph/obstetrics-and-gynecology/), [WHO antenatal care](https://www.who.int/activities/promoting-healthy-pregnancy/promoting-healthy-pregnancy), [WHO postnatal care guidance](https://www.who.int/publications/i/item/9789240045989), and [ACOG's well-woman visit guidance](https://www.acog.org/clinical/clinical-guidance/committee-opinion/articles/2018/10/well-woman-visit). The initial/follow-up split and release versions are local product choices. The sources support visit families; they do not approve this pack's fields, release version, or clinical completeness. Family planning, preconception, menopause, infertility and procedure-specific packs remain future clinically governed work. The v1.0.0 originals and narrative v1.1.0 releases remain pinned and readable; v1.2.0 adds clinician-selected finding templates for new synthetic encounters. See [the implementation](encounter-ros-physical-exam-implementation.md).

Seeding validates and publishes through the existing registry, preserving synthetic approval evidence. The complete pack commits in one authorized tenant transaction with a Practice-scoped lock. Repeat and concurrent runs reuse immutable releases without new drafts or duplicate evidence. A second authorized site gets its own availability rows for the same Practice releases. The v1.0.0 and v1.1.0 availability is retired after v1.2.0 publication; explicit retirement of v1.2.0 is preserved. A checksum mismatch fails the seed without rewriting history or partially installing the pack; publish a new governed version instead. Reload encounter setup after completion. With OB-GYN selected, five encounter types are available; general outpatient appears under General.

Seeder verification: `./api/mvnw -f api/pom.xml -Dtest=LocalSyntheticTemplatePackTests test` exercises PostgreSQL publication, repeat/concurrent seeds, site availability, preserved retirement, authorization denial on replay, and atomic rollback on conflicting published content.

## Seed pregnancy fixtures for an existing synthetic patient

`LocalExistingPatientPregnancySeeder` uses the same [synthetic pregnancy fixture](../../api/src/main/resources/clinical-fixtures/synthetic-pregnancy.json) for one explicitly selected patient. It runs only with the `local` profile, checks current clinician/site/reproductive access and patient care assignment, and requires the patient registry's `synthetic_only` marker. It creates source records through the pregnancy module in one tenant transaction. Repeat runs preserve the records and any later clinician history corrections. It does not create a patient, grant access, select or review dating, or alter an existing non-fixture episode.

From the repository root, replace the scope values with those of the intended synthetic patient and authorized clinician. Supply the normal local API's database/credential configuration. The command exits after a successful seed and must not run concurrently with another Maven build using `api/target`.

```sh
./api/mvnw -f api/pom.xml spring-boot:run \
  -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments='--server.port=0 --epr.clinical.enabled=true --epr.interventions.worker-enabled=false --epr.clinical.pregnancy-fixture-seed.enabled=true --epr.clinical.pregnancy-fixture-seed.practice-id=<practice-uuid> --epr.clinical.pregnancy-fixture-seed.site-id=<site-uuid> --epr.clinical.pregnancy-fixture-seed.patient-id=<patient-uuid> --epr.clinical.pregnancy-fixture-seed.username=<authorized-clinician-username> --epr.clinical.pregnancy-fixture-seed.exit-after-seed=true'
```

`./api/mvnw -f api/pom.xml -Dtest=LocalSyntheticPregnancyFixturesTests test` verifies sourced fixture data, repeat safety and denied access against PostgreSQL.

## Additive schema rollout and rollback

Control-plane migrations V9–V12 add explicit grants, active-care/session references and rollout history. Tenant migrations V3–V5 add the clinical records. New tenant provisioning applies these additive migrations without enabling clinical access. Clinical routing now requires tenant schema `15`; see [the intervention extension](encounter-interventions-implementation.md) for V8–V11, [the patient-registration extension](patient-registration-implementation.md) for V6–V7 and patient capabilities, and [the longitudinal clinical-list extension](clinical-lists-reconciliation-implementation-plan.md) for V12–V14.

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
