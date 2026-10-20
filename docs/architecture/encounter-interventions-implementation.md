# Synthetic encounter interventions implementation

This implements the enabled I0–I6 engineering scope in the [intervention plan](encounter-interventions-implementation-plan.md). It is an opt-in **local synthetic** workflow, not clinical approval or permission for real-patient issuance. Procedures and external transmission remain unavailable.

## Delivered behavior

One panel outside the template form provides a searchable type picker, explicit draft saves, repeated entry, typed prescription/laboratory/imaging/referral/follow-up composers, source review, filters, and paginated summaries. The Dashboard shell, shared sheet, buttons and patient context remain in use. Note and intervention edits share the encounter navigation guard. Forms retain their contents on errors; uncertain multi-step commands retain the original request body, UUIDs and idempotency key. Acknowledged steps are not repeated. Unmounted patient workspaces stop subsequent steps and ignore late responses.

Prescribing, orders/results and care coordination own separate relational sources and immutable revisions. Commands independently check current grants, tenant/site/patient/origin encounter, source version and lifecycle. Preparation does not authorize a document; printing does not imply delivery, dispensing, performance, attendance or review. Originals survive cancellation and replacement. Deliberately copied replacement drafts require a current active encounter, renewed review and separate authorization. There is no mixed “issue all” action.

Versioned fictional ingredient/service catalogs support engineering cases. Laboratory test names may be entered as free text or selected from the database-backed service catalog; selected suggestions retain their code and version, while unmatched names remain uncoded. Imaging requests still require a catalog match for authorization. The prescription form groups strength, formulation, dose/unit, route, timing, duration and quantity, shows current allergy/medication status, and attaches the latest eligible chart reconciliation when available. The legacy flat safety questionnaire was removed from the form because its write endpoint is disabled. Issuance still requires a current canonical reconciliation and acknowledgement that interaction support is unavailable; no new reconciliation UI is supplied by this form. Unknown, stale, duplicate-ingredient, known-allergy and duplicate-current-medication cases fail closed. These rules do not constitute a clinical decision-support engine.

Laboratory/imaging groups retain stable individual request identities and independent authorization, cancellation, fulfillment and review. Composite requisitions require every member to have been authorized and freeze the exact member versions. Original composite bytes remain available after individual lifecycle changes. Incoming reports are immutable, initially owned by their receiving clinician, and require an explicit patient/request match. Corrections link the original report and create another review obligation. Referral release, delivery, acceptance/refusal/attendance and response disposition remain separate.

Existing follow-up intent UUIDs continue through the original `encounter_followup_intent → clinical_owned_task` pipeline. Source obligations persist before optional delivery. Transfers retain the existing owner until an independently authorized clinician accepts. Accepted source/task work grants only the required patient identity and source access, not the original encounter/chart. Reconciliation preserves accepted transfers and resolved tasks. Closing care does not close these obligations. Clinical offboarding remains fenced by the existing team-access boundary; arbitrary administrative revocation is not a substitute for accepted clinical handoff.

## Integration and document integrity

`EncounterInterventionLinks` is the server-only context, association and manifest API. All source mutations acquire the encounter boundary before source work and append the source revision, summary, set version, audit and command receipt in one tenant transaction. No encounter-module dependency on prescribing/orders/referral implementations was introduced.

`interventionSetVersion` is independent of the note response revision and each source version. Signing captures an immutable manifest with exact source/summary revisions and draft/authorized statuses. A material intervention change rejects stale note confirmation. Source-read restrictions also protect summary counts, manifests and artifacts; a signer cannot silently omit restricted content.

HTTP preparation captures content first, stages/verifies local bytes outside that transaction, then finalizes against captured versions and hashes. Artifacts use private local files, immutable publication, SHA-256 verification and persisted metadata. Reprints use original bytes. Attached preparations and archives are never removed by cleanup. The worker removes at most 100 unattached files older than seven days per pass, examining at most 1,000 entries; retries can recreate these detached bytes. Cleanup serializes against artifact metadata attachment. Storage failure cannot report archival success.

Historical signatures without a manifest keep their original bytes and display “No intervention snapshot captured by this version.” No historical note is rerendered or assigned a fabricated empty snapshot. Missing follow-up associations are backfilled idempotently using the original intent UUID and original receipt time.

## Enablement, migrations and rollback

Migration inventory was checked before each additive allocation. The intervention implementation adds control-plane **V16** and tenant **V8–V11**, after the existing V15/V7 work. The later [longitudinal clinical-list extension](clinical-lists-reconciliation-implementation-plan.md) adds control-plane V18 and tenant V12–V14, so runtime clinical routing now requires **tenant schema 14**. New Practice provisioning applies the migrations without granting clinical access. The synthetic local seeder upgrades only its configured demo Practice.

From the repository root, with the existing local PostgreSQL environment running:

```sh
./api/mvnw -f api/pom.xml spring-boot:run \
  -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments='--epr.clinical.enabled=true --epr.clinical.demo-seed=true --epr.interventions.enabled=true --epr.team.enabled=true --server.port=8087'
EPR_API_PROXY_TARGET=http://127.0.0.1:8087 npm --prefix web run dev -- --port 5177
```

Use the existing documented synthetic clinician account. Never use this environment for real patient data. `EPR_INTERVENTIONS_ENABLED` controls new preparation/issuance; the per-Practice `practice_intervention_capability` flags independently control each kind. Explicit actor/site `practice_intervention_grant` rows are still required. Neither provider support nor Practice administration grants clinical authority. Seed retries do not reinstate removed permissions.

Turning off new interventions retains authorized read/history and `CONTINUE_EXISTING` dispositions, original documents and durable obligations. Turning off a kind’s preparation/authorization capability similarly stops new decisions while preserving existing clinical work. Membership, account, site and source-read revocation still deny reads and replay. Do not disable the broader clinical application or revoke the sole clinical owner as a rollout substitute.

Upgrade other synthetic Practices through the existing versioned support schema-upgrade operation, one observed canary first. Verify tenant identity and Flyway checksums, then proceed serially. Failed/mismatched routes remain quarantined; recover using the existing schema rollout workflow. Do not edit an applied migration or point a runtime role at another Practice database. See [encounter rollout](encounter-templates-obgyn-implementation.md#additive-schema-rollout-and-rollback).

## Delivery and recovery

The local worker runs every 30 seconds by default. `EPR_INTERVENTIONS_WORKER_ENABLED=false` stops automatic delivery while preserving the sources and the explicit reconciliation command. Authorized tenant scopes are resolved afresh; a PostgreSQL advisory claim prevents duplicate delivery in a scope. `clinical_delivery_checkpoint` records attempts, next attempt, last success and a non-clinical error code. Failure backoff starts at 30 seconds and is capped at one hour. Monitor failed/overdue checkpoints through an authorized tenant operational connection; no patient content belongs in monitoring labels.

Investigate route/grant/storage failures, restore the dependency and allow retry. Explicit reconciliation is idempotent. Do not delete source obligations or reconstruct them from narrative text. A revoked/unavailable scope is not bypassed by the worker; clinical access changes require the existing offboarding/handoff controls. Production fallback coverage, alert escalation and fleet worker operations require deployment validation.

## Verification evidence

Commands actually run from the repository root:

```sh
npm --prefix web run lint
npm --prefix web run test
npm --prefix web run build
./api/mvnw -f api/pom.xml verify
EPR_CONTRACT_BASE_URL=http://localhost:8087 node scripts/export-clinical-openapi.mjs
node scripts/generate-clinical-contracts.mjs
```

The OpenAPI exporter includes the persisted APIs, and the generator now exports every reachable schema rather than an older fixed schema list. Generated TypeScript is not hand edited. Final results on 2026-09-19: frontend lint passed without warnings; **22 Vitest files / 95 tests passed**; TypeScript/Vite build passed; **17 backend suites / 101 tests passed**, with zero failures/errors/skips and `verify` exit status 0. Surefire logged its 30-second fork-shutdown watchdog message after successful test completion; this does not establish clean production shutdown behavior. The exported contract contains **106 clinical/administration paths**.

| Rendered evidence | Desktop | Portrait tablet | Mobile |
| --- | --- | --- | --- |
| Typed draft composer | [1440×1000](evidence/interventions/composer-desktop.png) | [834×1194](evidence/interventions/composer-tablet.png) | [390×844](evidence/interventions/composer-mobile.png) |
| Explicit report matching | [Desktop](evidence/interventions/report-desktop.png) | [Tablet](evidence/interventions/report-tablet.png) | [Mobile](evidence/interventions/report-mobile.png) |
| Independent live statuses after care closure | [Desktop](evidence/interventions/final-desktop.png) | [Tablet](evidence/interventions/final-tablet.png) | [Mobile](evidence/interventions/final-mobile.png) |
| Owned-work directory | [Desktop](evidence/interventions/worklist-desktop.png) | [Tablet](evidence/interventions/worklist-tablet.png) | [Mobile](evidence/interventions/worklist-mobile.png) |
| Owned follow-up disposition | [Desktop](evidence/interventions/work-desktop.png) | [Tablet](evidence/interventions/work-tablet.png) | [Mobile](evidence/interventions/work-mobile.png) |

[Demonstration assertions](evidence/interventions/demonstration.json) record the synthetic signature, three deduplicated follow-up tasks and unchanged signed bytes. [Continuity and load assertions](evidence/interventions/continuity-and-load.json) record follow-up resolution and gynecology imaging authorization at all three sizes, with no console/page errors. [HTTP authorization checks](evidence/interventions/authorization.json) returned 403 for both source and summary access by the local support identity, and 403 for a clinician request retargeted to a different persisted Practice. Staff preparation remains gated by the existing clinician-only synthetic scope; administrative membership supplies no intervention authority. General-template laboratory draft entry was also verified. A bounded same-Practice burst of **50 concurrent authorized summary reads** returned 50/50 HTTP 200 responses: median 211 ms, p95 385 ms, maximum 401 ms. This local measurement is not a representative multi-user clinical workload or pilot capacity certification.

PostgreSQL tests cover typed persistence and retries; source/summary access parity and revocation; optimistic conflicts; immutable issued artifacts and signed history; concurrent signing/intervention edits; independent grouped requests and stale composite finalization; medication evidence; explicit report matching and corrections; referral refusal/cancellation; accepted handoff without chart access; single-instance follow-up reconciliation; worker failure/backoff/recovery; rollback dispositions; orphan cleanup; migrations/isolation; and database backup/restore with artifact hash comparison. Restore validation uses a separate database and confirms the original Practice is unchanged. This is an engineering restore check, not a production RPO/RTO drill.

Temporary Playwright scripts use installed Chrome because a Browser-plugin API was not available. They are not a committed `test:e2e` suite. Checks use exactly **1440×1000**, **834×1194**, and **390×844**, with state assertions, keyboard/focus checks, errors, no horizontal overflow, console/page-error checks and inspected screenshots. The prenatal demonstration preserves unsaved narrative through intervention entry, retries an intentionally failed request with identical payload/key, authorizes a reloaded lab draft, blocks a prescription missing review, issues after explicit synthetic review, releases a referral, and creates one follow-up task per intent. Another session invalidates note confirmation; refreshed signing, later cancellation, care closure, later reports and referral outcomes leave the signed bytes unchanged.

Impeccable’s final detector reported no findings. Passing tests, screenshots and local HTML artifacts do not establish clinical approval.

## Deployment gates retained

Real-patient issuance; approved medication/service terminology and outputs; production prescriber/requester authority and signer evidence; clinical decision support; private production storage/scanning and external delivery integrations; MFA and production session controls; complete clinician offboarding/fallback coverage; production restore/downtime drills and representative clinical load; program-specific governance; and procedure capability approval remain gated. Production automated tenant-fleet migration and worker monitoring are not supplied by the local canary/SQL workflow. No offline clinical cache, offline issuance or cross-Practice chart sharing was added.
