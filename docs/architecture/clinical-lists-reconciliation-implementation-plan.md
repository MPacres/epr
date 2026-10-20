# Longitudinal allergies, medications, and problems implementation plan

Status: backend implemented for the opt-in local synthetic slice on 2026-10-08. The existing frontend work is maintained separately; this implementation did not redesign it.

Prepared: 2026-10-07.

## Backend implementation record

The backend now provides the planned longitudinal source and reconciliation boundary:

- control-plane V18 adds action-specific allergy, medication-use and problem capabilities; tenant V12 adds source heads, immutable revisions, generations, assertions, proposals and exact review manifests; tenant V13 makes reconciliation decisions immutable; tenant V14 adds patient-consistent encounter, problem and prescription provenance constraints;
- current tenant routing and local synthetic enablement require schema 15 after the prescribing catalog/refill extension and continue to fail closed for disabled, migrating, mismatched or unauthorized Practice routes;
- `clinicalchart` exposes practice/patient/site-authorized summaries, paginated active/history reads, revision and transition commands, explicit absence reviews, terminology fixtures, proposals, exact reconciliation and safety-manifest reads;
- commands use client UUIDs, idempotency receipts, optimistic versions, immutable history and append-only clinical audit events; accepted reconciliation changes and their exact decisions share one tenant transaction;
- new prescription preparation and issuance consume a current canonical allergy/medication manifest and bind its ID/hash into immutable issued bytes; creation of legacy flat safety reviews is disabled while historical reads remain available; and
- encounter signature preparation binds the exact clinical review ID/hash, becomes invalid when the review is stale or changes before signing, and preserves already signed bytes after later list corrections.

The checked-in [clinical OpenAPI contract](../../api/contracts/clinical-openapi.json) and generated [TypeScript API types](../../web/src/features/encounters/services/api.generated.ts) were regenerated from the fully enabled local application. The implementation remains synthetic-only: terminology search is a local fixture, broader medication interaction knowledge is unavailable, non-clinician staff proposal routing remains gated by the existing clinical role and care-assignment boundary, and production identity, signing, storage, offboarding, restore/load and clinician-approval gates remain in force.

Verification completed on 2026-10-08:

- from `api/`, `./mvnw verify` exited 0, including PostgreSQL/Testcontainers integration and `ModularityTests`; Surefire logged its existing forced-fork shutdown message after the successful suite while local Hikari/Testcontainers threads were terminating;
- from `api/`, `./mvnw -q -Dtest=ClinicalListsTests test` passed four focused longitudinal-list tests, including exact non-empty proposal-decision replay and immutable history;
- the fully enabled local API started on port 8081 at control-plane V18 / tenant schema 14, then `EPR_CONTRACT_BASE_URL=http://localhost:8081 node scripts/export-clinical-openapi.mjs` exported 126 paths and `node scripts/generate-clinical-contracts.mjs` regenerated the client; and
- `npm --prefix web run lint`, `npm --prefix web run test` (95 tests), and `npm --prefix web run build` passed against the combined generated contract. No rendered UI was changed by this backend slice, so no new three-layout browser claim is made here.

## 1. Outcome and scope

Add three first-class longitudinal clinical sources to the connected synthetic patient chart, one complete vertical slice at a time:

1. allergies, intolerances, and adverse reactions;
2. reported current and historical medication use; and
3. the ongoing problem list, kept distinct from encounter diagnoses.

After the three sources exist, add encounter reconciliation and replace the prescription composer's manually re-entered safety list with an exact, current review of those sources.

```text
Authorized patient chart
  -> Overview
      -> Allergies & adverse reactions
      -> Current medications
      -> Problems & diagnoses
  -> dedicated list/detail/edit views

Active encounter
  -> staff may collect proposed changes
  -> clinician compares them with the longitudinal lists
  -> clinician applies or rejects each discrepancy
  -> clinician signs an exact reconciliation manifest
  -> prescribing and note signing reference that reviewed manifest
```

Opening or maintaining these lists does not create an encounter, appointment, queue entry, prescription, or diagnosis for the current visit. All work remains opt-in, local, and synthetic until the production gates in the repository guidance are met.

This plan does not add diagnosis or interaction recommendations, real-patient prescribing, pharmacy history exchange, a production terminology service, or cross-Practice chart sharing.

## 2. Design conclusions from EMR workflow research

The implementation adapts the following recurring patterns rather than copying one product:

- Epic exposes persistent allergy, medication, and problem activities while treating encounter review and outside-record reconciliation as separate workflows. [UI Health Care medication history](https://epicsupport.sites.uiowa.edu/epic-resources/medication-history), [outside information](https://epicsupport.sites.uiowa.edu/epic-resources/reconcile-outside-information), [problem list](https://epicsupport.sites.uiowa.edu/epic-resources/problem-list).
- Oracle Health makes allergies available from the patient panel, chart, and forms; distinguishes active, proposed, resolved, and canceled records; represents NKA/NKMA explicitly; and marks the list reviewed for a particular visit. Its problem workflow distinguishes `This Visit` from `Ongoing`. [Oracle allergies](https://docs.oracle.com/en/industries/health/oracle-health-ehr/ehrug/allergies.html), [review](https://docs.oracle.com/en/industries/health/oracle-health-ehr/ehrfg/mark-allergy-reviewed.html), [problems](https://docs.oracle.com/en/industries/health/oracle-health-ehr/ehrug/convert-problem-this-visit-or-ongoing.html).
- Elation keeps a longitudinal Clinical Profile beside the visit note, uses `Document Med Pt is On` separately from prescribing, and lets clinicians bring selected longitudinal information into a note. [Elation training](https://training.elationhealth.com/).
- DrChrono combines summary widgets with dedicated list pages, active/history views, duplicate checks, audit history, and encounter-local entry points. [Patient summary](https://support.drchrono.com/home/patient-chart-summary), [medications](https://support.drchrono.com/home/218734678-how-to-add-medications-to-the-medication-list), [problems](https://support.drchrono.com/home/patient-chart-problem-list).
- HL7 distinguishes reviewed absence from not asked, allergy from intolerance, reported medication use from a prescription, and an ongoing problem-list item from an encounter diagnosis. These distinctions guide the internal model but do not require a FHIR server. [AllergyIntolerance](https://hl7.org/fhir/R4/allergyintolerance.html), [MedicationStatement](https://hl7.org/fhir/R4/medicationstatement.html), [Condition](https://hl7.org/fhir/R4/condition.html).

The resulting product rule is: **one longitudinal source of truth, visible in the chart; an encounter-specific review of exact source versions; and separate downstream clinical actions.**

## 3. Baseline reviewed for this implementation

| Existing source | Baseline behavior | Implemented backend change |
| --- | --- | --- |
| [ConnectedClinical.tsx](../../web/src/features/encounters/ConnectedClinical.tsx) | Patient identity, unavailable-allergy warning, pregnancy sources, and encounter history | Server contracts now expose chart summaries and list/reconciliation commands; frontend composition remains in the existing frontend work |
| [PatientContextHeader.tsx](../../web/src/features/encounters/PatientContextHeader.tsx) | Always reports allergies/alerts unavailable | Summary contracts distinguish unavailable, not reviewed, reviewed empty, available and stale states |
| [PrescriptionComposer.tsx](../../web/src/features/prescribing/PrescriptionComposer.tsx) | Shows current chart allergy/medication status and uses the latest eligible reconciliation review when available; no longer asks clinicians to retype safety lists | Legacy POST creation was removed; prescription preparation requires canonical reviewed evidence |
| [MedicationSafety.java](../../api/src/main/java/ph/epr/api/clinicalchart/MedicationSafety.java) and [JdbcMedicationSafety.java](../../api/src/main/java/ph/epr/api/clinicalchart/infrastructure/JdbcMedicationSafety.java) | Stores a flat prescription safety review and ingredients | Historical evidence remains readable; new writes return `LEGACY_SAFETY_REVIEW_DISABLED` |
| [clinicalchart module](../../api/src/main/java/ph/epr/api/clinicalchart/package-info.java) | Owns pregnancy sources and medication-safety evidence | Now owns allergies, medication use, problems, revisions, proposals, list reviews and public read/review contracts |
| [prescribing module](../../api/src/main/java/ph/epr/api/prescribing/package-info.java) | Already depends on `clinicalchart` | Consumes the public canonical safety contract without chart-table access |
| [encountertemplates module](../../api/src/main/java/ph/epr/api/encountertemplates/package-info.java) | Already depends on `clinicalchart` | Binds exact reconciliation manifests through the public boundary without owning source lists |
| [tenant V11](../../api/src/main/resources/db/tenant-migration/V11__requisition_groups_and_safety_order.sql) | Adds sequencing to synthetic medication-safety reviews | Additive clinical-list storage and provenance constraints are allocated to tenant V12–V14 |

The current product plan already requires chart-local navigation and these source distinctions in `generated-assets/doctor-centric-epr-plan.md` sections 4.6 and 13.3–13.4. This committed plan restates the required behavior because `generated-assets/` is ignored and may be absent in other checkouts.

## 4. Non-negotiable domain rules

### 4.1 Shared rules

- Every source record belongs to one Practice and patient. Site and encounter references add provenance; they do not change tenant ownership.
- Store stable record identity separately from immutable revisions. A correction appends a revision and advances the current head with an expected version.
- Retain readable clinical text plus terminology system, code, and version when coded data is available. Never pretend free text participated in coded safety checking.
- Record actual author, information source/asserter, clinical occurrence time or precision, server receipt time, and correction reason.
- Deny patient retargeting. An entered-on-the-wrong-patient record uses controlled entered-in-error handling and retains its history.
- Ordinary UI actions never hard-delete clinical history.
- Distinguish loading, load failure, unavailable, not asked, unknown, not reviewed, stale, confirmed absence, and confirmed values.
- External or patient-submitted information remains proposed until an authorized clinician accepts, edits and accepts, or rejects it. A proposal is not part of the active local list.
- A list review captures exact record IDs and revision numbers. A later material change makes that review stale rather than rewriting it.
- Chart maintenance is permitted without creating an encounter. An encounter ID is optional provenance unless the command is specifically an encounter reconciliation.

### 4.2 Allergy and adverse-reaction rules

Store substance identity/display, category, allergy/intolerance/uncertain type, clinical status, verification status, criticality, onset precision, last known occurrence, source, and one or more reactions where supplied. Each reaction can retain manifestation, event severity, onset, and exposure route.

`No known allergies`, `No known medication allergies`, and `Not reviewed` are list-level assertions. They are not inferred from zero rows. Adding an active allergy after a confirmed-absence review makes that review stale.

Use explicit transitions such as activate, inactivate/resolve, refute, and enter in error. Severity of a past reaction and potential criticality are separate concepts.

### 4.3 Medication-use rules

Medication use records what a source reports the patient is taking or has taken. It is not a prescription, dispense, or administration record.

Store concept identity/display, optional brand, strength and formulation, actual dose and unit, route, timing/frequency, PRN state, effective period/precision, current status, source, prescriber when known, adherence/taking-differently state, notes, and optional indication/problem reference.

Use explicit statuses such as active, on hold, stopped, completed, and uncertain. A local statement that the patient stopped taking a medication does not cancel an external prescription. Issued prescriptions remain owned by `prescribing` and may be deliberately referenced as evidence for a medication-use record.

### 4.4 Problem-list rules

Store clinical concept/text, clinical status, verification/certainty, onset and abatement precision, severity where clinically supplied, priority/order, author/source, and relevant encounter links.

Keep these separate:

- an ongoing problem-list item;
- a diagnosis or symptom assessed in one encounter;
- a billing code; and
- a ruled-out or entered-in-error historical record.

From an encounter the clinician may link an existing problem, add a visit diagnosis only, or explicitly promote a visit diagnosis to the ongoing list. Never auto-promote every billing or visit diagnosis.

## 5. Target module, authorization, and schema boundaries

### Module ownership

`clinicalchart` owns:

- source identities and immutable revisions;
- active/history queries and clinical summaries;
- proposed external items and reconciliation decisions;
- list reviews and exact review manifests; and
- the public safety/reconciliation contract consumed by encounters and prescribing.

`prescribing` continues to own prescription drafts, issuance, cancellation, renewal, and documents. `encountertemplates` continues to own encounters, notes, signatures, and signed manifests. Those modules call public `clinicalchart` interfaces and never import its infrastructure or query its tables.

### Authorization

Do not reuse demographic-edit or prescription authorization as permission to edit the clinical chart. Extend the identity/tenancy boundary with action-specific clinical-chart capabilities:

```text
ALLERGY:       READ, PROPOSE, WRITE, REVIEW
MEDICATION_USE: READ, PROPOSE, WRITE, REVIEW
PROBLEM:       READ, PROPOSE, WRITE, REVIEW
```

The local clinician seed may receive all enabled synthetic actions. Future nurses or assistants may receive `READ` and `PROPOSE` without `REVIEW`; technical and Practice administration grants receive none. Every request still resolves the Practice, site, patient, membership, capability, and current service state on the server.

Add an append-only control-plane migration for the capability configuration and grants. Expose allowed actions in the clinical scope or a patient-specific capabilities response; frontend visibility never replaces server authorization.

### Tenant schema

Use additive migrations after inspecting the latest tree. At the time of this plan the next tenant version is V12, but implementation must not assume it is still free.

Prefer an identity/head plus immutable revision pattern:

```text
allergy_record -> allergy_revision -> allergy_reaction_revision
medication_use_record -> medication_use_revision
problem_record -> problem_revision
clinical_list_review -> clinical_list_review_item
clinical_record_proposal -> proposal_decision
```

Every table retains `practice_id`; patient-consistent composite foreign keys prevent cross-patient links. Review items reference a source kind, source ID, and exact revision/version. Use database constraints for bounded states and immutable-history triggers where appropriate.

Existing `medication_safety_review` rows remain historical synthetic evidence. Do not auto-convert their free-text ingredients into canonical allergies or medications. After cutover, new prescription issuance requires a new canonical reconciliation review; previously issued prescription evidence remains readable and immutable.

Coordinate every tenant version change with the registry, local enablement/upgrade service, runtime schema gate, provisioning/bootstrap tests, and fleet-upgrade documentation. Tenants not on the required version fail closed for the new capability.

## 6. API shape

All paths are relative to `/api/v1/practices/{practiceId}/patients/{patientId}` and include an independently authorized `siteId` query parameter in the current local transport.

### Shared summary and review

| Method/path | Purpose |
| --- | --- |
| `GET /clinical-summary` | Bounded active allergy, medication, and problem previews; per-list availability/review state; authorized counts and capabilities |
| `GET /clinical-list-reviews?encounterId=...` | Current and historical review manifests authorized for the context |
| `POST /clinical-list-reconciliations` | Atomically apply selected source changes/proposal decisions and create an exact review manifest |
| `GET /clinical-record-proposals` | Pending external/patient/staff proposals without merging them into the local lists |

### Allergies

| Method/path | Purpose |
| --- | --- |
| `GET /allergies` | Active list by default; explicit history/status filters and pagination |
| `POST /allergies` | Create a client-identified source record and first revision idempotently |
| `GET /allergies/{allergyId}` | Current detail and authorized revision history |
| `POST /allergies/{allergyId}/revisions` | Correct details with expected version and reason |
| `POST /allergies/{allergyId}/transitions` | Resolve, refute, reactivate, or enter in error with guards |
| `POST /allergy-list-reviews` | Record reviewed-known, reviewed-none-known, reviewed-no-known-medication-allergies, or unable-to-confirm against exact versions |

### Medication use

| Method/path | Purpose |
| --- | --- |
| `GET /medication-use` | Current list by default; explicit history/status filters and pagination |
| `POST /medication-use` | Document a medicine the patient reports taking; never issue a prescription |
| `GET /medication-use/{medicationUseId}` | Current detail, source references, and revision history |
| `POST /medication-use/{medicationUseId}/revisions` | Correct regimen, source, or adherence details with expected version |
| `POST /medication-use/{medicationUseId}/transitions` | Hold, stop, complete, reactivate, or enter in error |
| `POST /medication-list-reviews` | Capture an exact reviewed medication manifest |

### Problems and encounter diagnoses

| Method/path | Purpose |
| --- | --- |
| `GET /problems` | Ongoing list by default; explicit inactive/resolved/history filters |
| `POST /problems` | Create a longitudinal problem with first revision |
| `GET /problems/{problemId}` | Current detail, encounter links, and revision history |
| `POST /problems/{problemId}/revisions` | Correct clinical details with expected version and reason |
| `POST /problems/{problemId}/transitions` | Resolve, reactivate, refute, or enter in error |
| `POST /problems/{problemId}/encounter-links` | Link an existing problem to an authorized encounter without duplicating it |
| encounter-owned diagnosis commands | Add/update the current encounter diagnosis; optionally request explicit problem-list promotion through the public chart boundary |

Commands use client-generated UUIDs, original idempotency keys on retry, expected versions, bounded reasons, and structured errors. A timeout retains the original payload/key until its outcome is reconciled. List reads are paginated even if the initial UI shows only a small active set.

Export the final OpenAPI from a running enabled API and regenerate TypeScript through `scripts/export-clinical-openapi.mjs` and `scripts/generate-clinical-contracts.mjs`. Do not hand-edit generated DTOs.

## 7. Frontend structure

Add a connected feature boundary rather than growing `ConnectedClinical.tsx` or the prescription composer:

```text
web/src/features/clinical-chart/
  ClinicalChartNavigation.tsx
  ClinicalSummary.tsx
  clinical-chart-api.ts
  clinical-chart-contracts.ts
  allergies/
  medications/
  problems/
  reconciliation/
```

Reuse `PatientContextHeader`, shared buttons/sheets/dialogs, Dashboard tokens, TanStack Query, React Hook Form, Zod, the existing CSRF request behavior, and the shared unsaved-change guard. Query keys include Practice, site, patient, list kind, filters, and record ID.

Initial chart-local navigation should expose only connected destinations:

```text
Overview | Encounters | Medications | Patient Details
```

Allergies and Problems remain visible Overview sections with `View all` routes. Do not display nonfunctional Orders, Care Plan, Referrals, or Documents chart tabs merely to match the target information architecture.

### Responsive contract

- Desktop `1440 x 1000`: allergies remain prominent at the top; summary cards can use a two-column clinical grid; editors use a sufficiently wide sheet or focused page.
- Portrait tablet `834 x 1194`: one- or two-column reflow based on readable field width; chart-local navigation remains reachable without hiding safety state.
- Mobile `390 x 844`: stacked summaries and a full-width editor; patient identity and allergy safety state remain visible; primary actions stay clear of the bottom navigation and software keyboard.

Critical content is not available only through hover, tooltips, or collapsed-by-default panels. Color always has a text/icon/status equivalent. Loading never renders as an empty or negative list.

## 8. One-by-one delivery sequence

Do not begin the next clinical domain until the preceding increment meets its exit criteria. Small preparatory contract work for the next increment is acceptable, but unfinished controls must not appear as working features.

### Increment 0 — shared foundation and empty clinical summary

Implement:

- final domain/status vocabulary and synthetic terminology fixtures;
- clinical-chart capability/grant model and local seeding;
- tenant revision/list-review foundation tables needed by Increment 1;
- `clinicalchart` public application interfaces and web error contract;
- `/clinical-summary` returning explicit `UNAVAILABLE`, `NOT_REVIEWED`, `EMPTY_REVIEWED`, `AVAILABLE`, `STALE`, or `FAILED`-mappable states, never inferred absence;
- chart-local route shell and Overview/Encounters/Medications/Patient Details navigation, with only implemented links enabled; and
- feature capability checks so older/non-enabled tenants keep the current honest unavailable state.

Required proof:

- support/admin identities cannot read summary content;
- manipulated Practice/site/patient IDs fail closed;
- scope changes cancel/remove old patient data;
- unknown and confirmed absence cannot serialize to the same state;
- shell renders without horizontal overflow at all three required layouts; and
- `ModularityTests`, API verification, web lint/test/build, and rendered checks pass.

Exit condition: the connected chart has a safe, server-backed clinical-summary shell, but no fake Add actions.

### Increment 1 — allergies and adverse reactions

Backend:

- add allergy identity, immutable revisions, reaction revisions, list assertions, review manifests, indexes, constraints, and audit events;
- implement create, detail/history, revise, status transition, list review, and clinical-summary projection/application queries;
- provide a small versioned synthetic allergen catalog/search adapter; coded matches and free text are visibly distinct;
- make `No known allergies` and `No known medication allergies` explicit reviewed assertions; and
- make a material post-review change render the review stale.

Frontend:

- replace the banner's unconditional unavailable message with the authorized server state;
- add an always-visible Allergies & adverse reactions summary to Overview;
- add list/history and editor sheet/page with substance-first search, type, category, reactions, severity/criticality, onset precision, certainty, source, and notes;
- add deliberate `Mark reviewed`, `No known allergies`, transition, and entered-in-error flows; and
- preserve input and idempotency state through validation, timeout, conflict, and authorization failure.

Tests:

- reviewed-none versus unknown/unreviewed;
- allergy versus intolerance and uncertain type;
- duplicate candidate warning without silent merge;
- concurrent revision conflict;
- immutable prior revision and wrong-patient denial;
- refute/resolve/entered-error/reactivate guards;
- review becomes stale after change;
- keyboard/focus, screen-reader labels, error association, and all three layouts.

Exit condition: a clinician can add, review, correct, resolve, and audit an allergy from the patient chart without starting an encounter. The banner reliably reflects the result.

### Increment 2 — reported medication use

Backend:

- add medication-use identity/revisions, regimen fields, source references, adherence state, transitions, and medication-list review manifests;
- expose a synthetic medication-concept search through `clinicalchart`; do not query `prescribing` tables directly;
- optionally reference an existing prescription through a public identifier while preserving medication-use ownership; and
- keep prescription, reported use, dispensing, and administration states distinct.

Frontend:

- add the chart `Medications` destination with Current, Past/Stopped, Prescriptions, and Review history views;
- provide separate `Document medication patient is taking` and `Prescribe medication` actions;
- show ingredient/brand, strength/form, actual dose/unit, route, timing, PRN, status, source, adherence, indication, and last review;
- support hold, stop, complete, reactivate, taking-differently, and entered-in-error workflows with reasons; and
- add a compact Current medications card to Overview.

Tests:

- prescription creation does not prove current use;
- documenting current use creates no prescription;
- external prescription stop does not cancel the source prescription;
- strength and dose remain distinct;
- current, stopped, completed, on-hold, and uncertain states round-trip;
- conflict/retry/isolation/revocation behavior; and
- large-history pagination plus all three rendered layouts.

Exit condition: a clinician can maintain an accurate longitudinal medication-use list independently of prescribing.

### Increment 3 — ongoing problem list

Backend:

- add problem identity/revisions, certainty/status/onset/abatement, priority, encounter links, and audit history;
- expose bounded terminology search with readable text and source/version, without assuming US ICD-10-CM is the Philippine production terminology;
- add explicit link and promotion interfaces for the encounter owner; and
- reject silent duplicate creation while allowing a clinician to confirm clinically distinct records.

Frontend:

- add Problems & diagnoses to Overview with separate `Ongoing problems` and, only in an encounter, `This visit` groupings;
- add active/resolved/history views, create/edit/transition flows, duplicate review, ordering/priority, and encounter links;
- use `Add visit diagnosis`, `Link existing problem`, and `Also add to problem list` as separate actions; and
- show certainty and status in text, not color alone.

Tests:

- visit diagnosis does not automatically enter the problem list;
- linking does not duplicate the problem;
- suspected/confirmed/refuted and active/resolved combinations;
- terminology/text preservation across catalog changes;
- concurrent promotion/duplicate attempts; and
- revision immutability, tenant/patient isolation, keyboard use, and three layouts.

Exit condition: the chart has a longitudinal problem list and encounters can reference it without conflating it with visit diagnoses.

### Increment 4 — encounter reconciliation

Implement one encounter panel using the three existing sources:

1. show current list versions and last review state;
2. allow staff-authorized proposals without clinical verification;
3. compare proposed, external, and current items;
4. require an explicit keep/add/update/stop/resolve/reject decision for each discrepancy;
5. apply accepted changes and create the exact list-review manifest in one tenant transaction; and
6. display reviewer, time, outcome, and stale state in the encounter.

The command must lock/recheck relevant source heads, patient, encounter, capabilities, and expected versions. A reconciliation conflict returns a comparison rather than silently overwriting. Note autosave remains separate; unsaved reconciliation work joins the existing encounter navigation blocker.

Tests cover staff proposal versus clinician review, no-change review, partial review rejection, concurrent source changes, timeout retry, duplicate proposal delivery, revocation during review, and patient/context switching.

Exit condition: an authorized clinician can sign a reproducible encounter-specific review without copying list text into the note.

### Increment 5 — prescribing safety cutover

Replace the normal `PrescriptionComposer` free-text review flow with the `clinicalchart` public safety contract:

- show the latest exact allergy and medication reconciliation manifest;
- revalidate that it is current, authorized, for the same Practice/patient/site context, and within the configured synthetic freshness rule;
- compare coded prescription ingredients with coded active allergies and medication-use ingredients;
- fail closed for unknown, unavailable, stale, free-text-only/uncheckable matches, or superseded evidence according to approved policy;
- keep the explicit acknowledgment that broader interaction support is unavailable; and
- record the manifest ID/version on the prescription and immutable issued artifact.

Do not delete historical `medication_safety_review` rows or rewrite previously issued prescriptions. Disable creation of legacy flat reviews after the cutover; allow historical reads only.

Tests cover known allergy, drug class where supported, uncheckable free text, duplicate ingredient, stale review, concurrent list change during issuance, retry, old prescription readability, and no clinical source-table access from `prescribing`.

Exit condition: new prescriptions use canonical reviewed sources and no longer ask the clinician to retype the patient's safety lists.

### Increment 6 — signed-note binding and longitudinal polish

- Include exact reconciliation manifest references and human-readable summaries in note-sign preparation.
- Invalidate a prepared signature when the bound clinical review changes before final signing.
- Preserve signed bytes and source versions after later list corrections.
- Add searchable history/timeline events for allergy, medication, problem, and reconciliation changes.
- Finish empty/loading/failure/stale/proposed states, print/export views where authorized, and representative larger-list performance checks.
- Reconcile the documentation, current schema version, enablement commands, and remaining production gates.

Exit condition: signed encounters are reproducible, later corrections do not rewrite history, and the chart provides a coherent longitudinal view.

## 9. Verification matrix

Every functional increment runs the verification appropriate to its changed layers.

### API

From `api/`, with Java 25 and Docker/Testcontainers available:

```sh
./mvnw verify
```

Add PostgreSQL tests for migrations, composite tenant/patient foreign keys, immutable revisions, optimistic conflicts, idempotent retries, authorization/revocation, two-Practice isolation, schema routing, and transaction rollback. Keep `ModularityTests` passing.

### Contracts

With the enabled local API running on the selected port:

```sh
EPR_CONTRACT_BASE_URL=http://localhost:<port> node scripts/export-clinical-openapi.mjs
node scripts/generate-clinical-contracts.mjs
```

Inspect the generated diff and runtime Zod schemas. Do not claim contract generation passed when the enabled API was not running.

### Web

From the repository root:

```sh
npm --prefix web run lint
npm --prefix web run test
npm --prefix web run build
```

For every rendered change, inspect exactly:

- desktop `1440 x 1000`;
- portrait tablet `834 x 1194`; and
- mobile `390 x 844`.

Checks include correct patient identity, no stale patient data after navigation, keyboard-only completion, focus return from editors/dialogs, visible labels and errors, no horizontal overflow, software-keyboard-safe actions, loading/failure/empty distinctions, unsaved-change protection, and no console/page errors. Use the Browser plugin when available; otherwise record why temporary Playwright was used.

## 10. Release and safety gates retained

Completion of this synthetic slice does not establish production clinical readiness. Real-patient use still requires approved terminology and medication knowledge sources; clinician-approved field sets and outputs; production authorization and offboarding; MFA/session controls; privacy review; external data governance; backup/restore including revision/audit history; downtime reconciliation; representative load; and current Philippine regulatory/program review where applicable.

Do not advertise allergy or interaction checking for free-text-only entries. Do not claim prescribing safety from a fresh review alone. Pregnancy, renal-dose, pediatric, disease-interaction, and comprehensive drug-interaction support remain unavailable until separately validated data and rules are implemented.

## 11. Definition of complete

The backend portion of this definition is implemented and covered by the verification recorded above. End-to-end completion still depends on the separate frontend implementation and its required three-layout rendered checks.

The work is complete only when:

- all three longitudinal sources are persisted, authorized, revisioned, and visible in the connected chart;
- unknown, unreviewed, confirmed absence, known values, stale data, proposals, loading, and failure are distinct;
- encounter diagnoses and ongoing problems are separate;
- medication use and prescriptions are separate;
- clinician reconciliation captures exact source versions and staff collection cannot masquerade as review;
- prescribing consumes the canonical review rather than manual re-entry;
- signed notes retain immutable review references;
- retries, conflicts, patient/Practice isolation, revocation, and history preservation are tested; and
- all required API, frontend, contract, and three-layout checks have actually passed.
