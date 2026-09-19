# Database-backed patient registration and encounter entry

Status: implemented for the opt-in local synthetic clinician workflow. See [implementation, verification and remaining gates](patient-registration-implementation.md). Sections describing the baseline below record the pre-implementation state.

Prepared: 2026-09-19.

## 1. Outcome and scope

Connect the existing, pictured My Patients directory and six-section patient form to PostgreSQL. Preserve their layout, patient preview, search, filters and responsive behavior. The smaller connected directory introduced during the encounter-navigation fix is an interim integration screen, not the intended replacement design.

The first delivery must let an authorized doctor create a patient through the UI, reload the saved record, and create an encounter for that exact patient. Saving a patient and starting care remain separate server-confirmed actions.

```text
My Patients → New patient → Enter details → Review possible duplicates
    → Save patient → Saved patient chart → New encounter
        → Select location and date/time of service → Start encounter

Optional continuation on the registration form:
    Save destination: “Patient chart” or “Encounter setup”
    → Save patient → Selected destination for the returned patient ID
```

Use one form-wide save button. A destination selector can change its label to “Save and continue to encounter”; this opens setup after registration succeeds and does not silently create an encounter. Default to the patient chart for ordinary registration; a registration opened from an explicit new-care action may default to encounter setup.

The first increment supports the currently authorized clinician. Staff registration requires its own operational permissions and is a subsequent increment; staff must not gain chart or encounter rights as a shortcut.

## 2. Current implementation and gaps

Inspected sources, rather than the appearance of controls, establish the baseline:

| Existing source | What exists | Work required |
| --- | --- | --- |
| [MyPatients.tsx](../../web/src/features/patients/MyPatients.tsx), [PatientDetails.tsx](../../web/src/features/patients/PatientDetails.tsx) | Search/filter/page UI and patient preview using fixtures | Replace fixture reads with authorized paginated API data; keep the composition |
| [PatientForm.tsx](../../web/src/features/patients/PatientForm.tsx), [PatientFormSections.tsx](../../web/src/features/patients/PatientFormSections.tsx) | Six-section form, validation, unsaved-change guard and demo saves | Reuse presentation with API-backed create, detail and edit operations |
| [preview-repository.ts](../../web/src/features/patients/preview-repository.ts) | In-memory records, simulated failures and duplicate checks | Keep behind an explicit preview adapter; remove from normal patient routes |
| [ClinicalPatientController.java](../../api/src/main/java/ph/epr/api/patientregistry/web/ClinicalPatientController.java) | Local opt-in patient list and create endpoints | Full registration contract, detail/edit/search and duplicate-review operations |
| [JdbcClinicalPatientRegistry.java](../../api/src/main/java/ph/epr/api/patientregistry/infrastructure/JdbcClinicalPatientRegistry.java) | Persisted name, birth date, identity state; MRN; clinician assignment; journal/audit | Structured demographics, optional sections, versioned edits and stronger duplicate review |
| [V3 tenant foundations](../../api/src/main/resources/db/tenant-migration/V3__create_synthetic_clinical_foundations.sql) | Tenant patient table and care assignments; synthetic-only constraints | Additive schema evolution without changing existing patient UUIDs or foreign keys |
| [EncounterStart.tsx](../../web/src/features/encounters/EncounterStart.tsx), [ConnectedClinical.tsx](../../web/src/features/encounters/ConnectedClinical.tsx) | Patient-specific encounter setup, location, date/time and persisted creation | Use the patient returned by registration and replace list-based patient lookup with detail retrieval |

The current list returns at most 100 records, with no server search or pagination. Patient access is limited to enabled clinician care assignments. Registration checks exact normalized name plus birth date against assigned patients and accepts a `duplicateReviewConfirmed` boolean. That boolean is not evidence that the operator reviewed the current candidates.

Current API authorization and routing are deliberately restricted to local synthetic clinical work and tenant schema 5. Database-backed registration already exists in minimal form; complete patient registration UI integration does not exist. The implementation must address these restrictions explicitly rather than removing flags or database checks to make a button work.

## 3. Patient workflow and UI contract

### Directory

Restore the pictured My Patients layout at `/patients`, backed by saved records. Preserve the table, selected-patient pane on desktop, tablet/mobile detail presentation, Practice selector, search, filters, sorting and pagination.

- Populate Practice/site choices from current authorized scope data, never `doctorClinics` fixtures.
- A Practice owns the patient; clinic location is a care setting within that Practice. Do not create a new patient identity for each site.
- Display only authorized counts and results. Searches and totals must be computed over the same permission scope.
- New patient opens registration with the selected Practice fixed and visible. Any site context used to authorize registration must also be authorized, but must not bind the chart permanently to that site.
- Show a saved patient's name, MRN and date of birth or explicit unknown state. Show estimated birth dates as estimated.
- Read encounter history and queue summaries from their owning services or maintained projections. Never reuse demo last-visit dates, visit types, queue tokens, counts or active sessions for saved patients.
- Distinguish “No encounters recorded” from “Visit history unavailable.” Unknown data is not a negative filter match.
- Do not display a functioning Recent patients tab until its data source is connected. Persist actor-specific, tenant-scoped recent chart opens with bounded retention, independent of append-only audit history; reauthorize each result.

### Registration and editing

Reuse these sections: Demographics; Contact & Preferences; Address; PhilHealth; Representatives & Emergency Contacts; Identifiers. Keep Demographics and PhilHealth open on create, Address and PhilHealth open on edit.

Every enabled field must survive save and reload. A minimal POST that silently drops the other five sections does not satisfy this plan.

- Require at least one supplied name or a deliberately entered provisional name. Do not invent missing name parts.
- Permit exact, estimated or unknown birth dates. Reject invalid/future dates using the Practice's Philippine calendar date. Unknown dates store null, not a placeholder date.
- Phone, email, address, PhilHealth and other external identifiers remain optional.
- Keep the patient's own PhilHealth information separate from a dependent's principal-member information. Recording an identifier does not verify it or establish eligibility.
- Mask existing sensitive identifiers. Use explicit unchanged/replace/remove semantics so masked text cannot overwrite stored values.
- Keep uploads unavailable until the private document pipeline is connected; registration does not depend on uploads.
- After a successful create, use the returned UUID/MRN/version. Invalidate scoped directory queries and navigate to the selected destination.
- On validation, duplicate, authorization, conflict or network failure, retain entered values and show actionable, distinct feedback. Never fall back to demo success.
- Editing requires an expected record version and a correction reason when required by the changed identity fields. Show a comparison on conflict rather than overwriting.

### Encounter handoff

After registration, the URL and API request must carry the returned Practice and patient IDs. Refreshing or bookmarking a saved chart or encounter-setup URL must work without a previously loaded directory page.

Keep the patient visible and fixed. Offer only authorized locations within the same Practice and a date/time of service in Asia/Manila. Queued care keeps its session location. Selecting a location does not silently activate another PracticeSession.

Use the existing encounter service, immutable template selection and idempotency mechanism. Registration creates no encounter, appointment or queue entry. If encounter creation fails after registration, the patient remains saved; retry only the encounter command. Cancelling encounter setup does not delete the patient.

## 4. Database and module changes

Extend the existing patient identity rather than introducing a second competing patient table/UUID system. Patient-registry-owned tables remain in the Practice database with `practice_id`, tenant-consistent foreign keys, versions, source/author and timestamps.

| Record | Proposed persisted content |
| --- | --- |
| Core patient | Existing UUID and MRN, structured name parts, display name, birth date/accuracy, sex or not recorded, identity state, creator and timestamps/version |
| Contact/preferences | Phone, email, preferred language and contact preference; preference is not consent |
| Address | Supplied street, barangay, city/municipality, province/region and postal code; do not infer missing geography |
| Identifiers/PhilHealth | Type, issuer, protected value, role, contributor category, source, verification state and separate principal-member details |
| Representatives/contacts | Distinct representative and emergency-contact entries, relationship, phone and reported authority; no automatic chart access |
| Demographic revisions | Append-only before/after correction evidence with actor, reason and server receipt time; access-controlled, not ordinary application logs |
| Duplicate review | Server-held review context: request fingerprint, eligible candidate IDs/versions, reviewer, scope, expiry and explicit decision |
| Recent chart opens | Practice, actor, patient and last authorized open time; bounded independently of audit retention |

Keep MRN server-generated and unique within the Practice; it is not the patient's UUID. Use a sequence/allocator with uniqueness constraints, never `count + 1`. Existing synthetic MRNs and UUIDs remain intact. Choose any production MRN formatting separately from identity semantics.

Add the next available tenant Flyway migration after checking the working tree; do not edit V3 or assume a migration number is free. Backfill new columns conservatively: retain an existing unsplit display name rather than guessing first/last names or unrecorded demographics.

Coordinate schema compatibility across the clinical scope query, runtime router, upgrade service, provisioning/bootstrap and tests. These currently contain schema-5 checks. Existing schema-5 tenants must remain on supported read paths or fail closed for new writes until upgraded; no partially migrated registration traffic. Test canary upgrade, quarantine and recovery.

The patient registry owns patient persistence. Encounters and scheduling own their histories and transitions. Do not add registry queries directly against another module's private tables. For last-visit sorting/filtering, add a registry-owned visit-summary projection updated through a public registry interface from encounter lifecycle code, preserving the existing dependency direction. Store source encounter/version, deduplicate updates and define reconciliation before enabling these filters. Read detailed visits/queues through owner APIs. Failure or lag must be visible, and projection updates must never become signing or care-completion authority.

## 5. API and client contracts

All paths below are relative to `/api/v1/practices/{practiceId}` and are proposed unless noted. Reuse same-origin cookies, CSRF, authenticated-actor mapping and trusted tenant routing. Keep site authorization context explicit and validated; clients never provide database routes.

| Method/path | Contract |
| --- | --- |
| `GET /patient-directory` | Bounded search, supported filters/sort, page/pageSize, items, authorized total and refresh/projection state |
| `GET /patients/{patientId}` | Authorized detail including all editable fields, masked identifier metadata, capability flags and record version |
| `POST /patients` | Extend existing create: client UUID, structured registration data, optional duplicate-review reference, original idempotency key; return confirmed identity and version |
| `PATCH /patients/{patientId}` | Versioned updates, explicit sensitive-field operations, correction reason and idempotency key |
| `POST /patients/duplicate-checks` | Bounded, authorized duplicate candidates with matching reasons and a review reference bound to the submitted identity fields |
| `GET /patients/recent` | Authorized actor-specific recent chart opens; bounded results |
| `POST /patients/{patientId}/chart-opens` | Record a deliberate authorized chart open idempotently; not hover/preload activity |
| Existing encounter endpoints | Unchanged patient-specific encounter creation; independently reauthorize the saved patient and chosen site |

Use a separate directory response initially so existing consumers of `GET /patients` do not suddenly receive a different array/page shape. Move chart/encounter patient lookup to `GET /patients/{patientId}`; a patient absent from the first directory page must still be addressable with authorization. Retire legacy list consumers deliberately after contract migration.

Define separate detail and minimal directory DTOs. Do not expose full contact/identifier payloads in list responses or general search. Use structured error codes for invalid fields, duplicate review required/stale, version conflicts, denied access and unconfirmed requests.

Extract a patient API/repository boundary under `web/src/features/patients/`; use TanStack Query for saved records and React Hook Form/Zod for the open form. Split reusable form presentation from demo transport rather than mixing live and preview success paths. Keep patient identifiers and scope in query keys, cancel obsolete requests on scope changes, and clear inaccessible data on logout/revocation. Do not persist clinical query caches or forms to browser storage.

Regenerate OpenAPI/TypeScript contracts through the checked-in scripts; extend the generator's explicit schema-name list for new DTOs. Do not hand-edit generated output. Keep runtime response validation aligned with the generated contracts.

## 6. Authorization, duplicate review and retries

### Authorization

Add explicit patient-directory read, patient-detail read, register and demographic-edit capabilities. Evaluate current Practice membership, enabled service, site assignment and field restrictions on every operation, including idempotency replay. Provider-support roles receive no patient access from technical administration.

The first clinician increment can retain the existing explicit clinician/site grant and care-assignment model. Creating a patient must establish an authorized care relationship for that clinician in the same tenant transaction, not trust a client-supplied clinician ID. Do not generalize this to make a secretary the clinical owner. Staff registration later needs separate operational authorization, an approved responsible-clinician assignment workflow and continued denial of encounter/signature actions.

The current tenant executor calls clinician-only authorization; supporting staff requires a deliberate operation-specific authorization entry point, not changing a frontend role check or passing the existing `approval` boolean differently.

### Duplicate review

Normalize names/contact values for matching while preserving original entered values. Use bounded matching rules with explicit reasons; demographic similarity is never a unique identity constraint or permission to merge.

The server rechecks candidates inside the registration transaction. Bind review confirmation to the submitted identity, eligible candidate versions, actor and Practice, with expiry. If fields or candidates change, require renewed review. Record the explicit choice to open an existing authorized patient or create a separate record. Remove the bare boolean bypass from the new public contract.

Do not limit duplicate protection blindly to the current directory page or expose inaccessible charts to improve matching. Define minimum registration-only identity access separately from clinical access. Where a suspected match falls outside the operator's permitted identity scope, use an authorized registration-review path without disclosing prohibited candidate details. Keep the first clinician scope explicit if that operational review workflow is not yet delivered.

Concurrent registration must serialize/recheck the relevant candidate domain so both requests cannot blindly commit before seeing each other. Preserve the current Practice registration lock initially if needed for correctness; measure before replacing it with narrower locks. A matching candidate may still become a separate record after deliberate review.

### Transaction and retry behavior

One tenant transaction commits the patient, optional fields, authorized assignment, demographic revision/audit and idempotency receipt. No partial registration success.

Generate the patient UUID and idempotency key once for a submission. A timeout or lost response retries the identical payload/key. While its outcome is unknown, prevent changing the in-flight command; reconcile through retry before accepting a revised submission. Once rejection is confirmed, let the operator correct the form and submit a new command/key. Replaying a successful command after access revocation must not return cached patient details.

Registration and encounter creation use separate keys and receipts. Navigating to encounter setup is a client continuation after confirmed registration, not a distributed transaction.

## 7. Delivery sequence and completion criteria

| Increment | Implementation | Required proof |
| --- | --- | --- |
| 1. Patient contracts and persistence | Additive schema, all six form sections, detail/create contracts, authorization and duplicate review | API create/read round-trip preserves every enabled field, retries create one record, migration and isolation tests pass |
| 2. Actual registration screen | Reuse current form, remove demo transport from normal route, show server errors, connect both save destinations | Create patient from UI, reload details, continue to encounter setup with same UUID/MRN and no second search |
| 3. Existing directory connected | Restore pictured layout at `/patients`; server search/pagination/preview; connect last-visit and recent-patient data before enabling their controls | New patient appears in correct scope; search can find records beyond 100; directory and direct patient routes work after reload |
| 4. Edit and continuity | Versioned editing, correction history, masked identifiers and conflict UI; all live navigation uses saved identities | Edit/reload works; two-editor conflict preserves both contexts; saved encounter remains linked; signed history unchanged |
| 5. Integration and release verification | Three-layout checks, two-Practice tests, failure/retry/access cases and production gate inventory | Evidence covers registration → encounter save → reload; all claimed features are connected and limitations explicit |

Increment 2 is the first usable vertical slice the user needs; do not wait for a redesign, template builder, prescribing implementation or advanced matching engine to deliver it. Increment 3 restores the complete pictured directory experience. Staff registration is separately sequenced after the doctor flow and its permission tests.

Keep the old fixtures reachable only through an explicitly labeled preview route. Move preview registration to its own route so `/patients/new` cannot accidentally create an in-memory record. No automatic migration or name-based linking of demo patients. Update Dashboard/global-search links and shared shell context so saved and preview identities cannot be mixed; absent connected queue/inbox data must show an unavailable state instead of fixture counts.

## 8. Verification and definition of done

Use synthetic records for all implementation and browser tests.

- Create with minimum demographics and unknown birth date; no optional identifier or contact is required.
- Round-trip every enabled field, including dependent principal details, estimated dates, representative fields and sensitive identifiers; masked placeholders never become saved values.
- Server validation rejects malformed/future birth dates and tampered identifiers/scope; UI preserves entered data.
- Double-click, timeout-after-commit and concurrent retry produce one patient UUID/MRN; changed payload with the same key conflicts.
- Duplicate candidates require review; stale reviews and concurrent registrations cannot bypass the check; no automatic merge occurs.
- Two Practices and two clinicians prove authorized list/count/search/detail/write/replay behavior and no pooled-connection leakage.
- Revoked access and provider administration are rejected by the backend; staff cannot create encounters merely because they can register patients.
- More than 100 patients prove pagination/search and direct detail retrieval; unknown visit history is distinguished from no visits.
- A created patient remains after reload and can open encounter setup with its original context. Same-Practice site changes retain identity; another Practice's site is rejected.
- Encounter failure leaves the patient saved. Retrying start does not register another patient or create duplicate care. Cancel returns to the saved chart.
- Date of service survives persistence, including midnight Manila/UTC boundaries. Chart review and registration do not create clinical care.
- Concurrent demographic edits conflict predictably. Corrections do not rewrite signed encounter bytes or prior demographic evidence.
- Desktop 1440×1000, portrait tablet 834×1194 and mobile 390×844 verify registration, duplicate review, save/errors, directory selection, encounter handoff, keyboard focus, sticky context and absence of clipping.

Implementation verification commands from repository root:

```sh
./api/mvnw -f api/pom.xml verify
EPR_CONTRACT_BASE_URL=http://localhost:8080 node scripts/export-clinical-openapi.mjs
node scripts/generate-clinical-contracts.mjs
npm --prefix web run lint
npm --prefix web run test
npm --prefix web run build
```

Contract export requires the enabled local synthetic API at the selected port; regenerate contracts before frontend validation. Use PostgreSQL Testcontainers for transactions, tenant isolation and migrations. Add meaningful registry tests rather than relying only on the encounter suite. Use Browser tooling if available, otherwise temporary Playwright scripts outside committed source; no `test:e2e` script currently exists.

### Persisted development vs real-patient release

The delivered registration screen must perform real PostgreSQL writes, not simulated saves. That milestone can be verified using synthetic patients while current local restrictions remain active.

Permission for real-patient use is a separate release gate. Before enabling it, inventory and replace the synthetic-only schema/capability restrictions and local-only controller/auth adapters through an explicit production rollout; preserve existing synthetic provenance. Complete production clinical routing, MFA/session controls, membership revocation, encryption/key handling, protected identifiers, audit, export, backup/restore and downtime checks from the accepted plans. Do not simply remove `syntheticOnly` or relabel synthetic patients as real. Confirm deployment-specific program requirements before enabling affected program behavior; this registration plan makes no eligibility or certification claim.

## 9. Planning authorities

- [Repository guidance](../../AGENTS.md), [frontend guidance](../../web/AGENTS.md) and [API guidance](../../api/AGENTS.md).
- [Session/authentication decision](session-and-client-authentication.md) and [database-per-Practice decision](database-per-practice-tenancy.md).
- [Connected encounter implementation](encounter-templates-obgyn-implementation.md).
- Local ignored references: `generated-assets/doctor-centric-epr-plan.md` §§3–7, 13.11, 22–23; `generated-assets/epr-technical-architecture-plan.md` initial online behavior and release gates; `generated-assets/epr-patient-forms-v2-notes.md`.

Some architecture status paragraphs predate the connected local slice. Use those documents for accepted decisions and re-inspect current source for implementation status. This plan does not change that source or claim that proposed capabilities already exist.
