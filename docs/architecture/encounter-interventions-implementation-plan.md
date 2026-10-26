# Encounter interventions implementation plan

Status: proposed implementation and integration contract; not implemented functionality or clinical approval.

Prepared: 2026-09-19. Companion to the [OB-GYN encounter-template plan](encounter-templates-obgyn-implementation-plan.md), which is being implemented separately.

Decision: provide **one Add intervention entry point and one intervention list in the encounter workspace**, available independently of the selected documentation template. Prescriptions, orders, referrals and tasks remain typed records owned by their clinical modules.

Quick navigation: [Integration handoff](#3-integration-contract-with-the-encounter-workstream) · [Clinician workflow](#4-clinician-workflow) · [Clinical records](#5-intervention-types-and-minimum-records) · [Lifecycle rules](#6-lifecycle-and-authorization-rules) · [Backend design](#7-backend-architecture-and-persistence) · [API contracts](#8-proposed-api-contracts) · [Delivery](#11-delivery-sequence) · [Tests](#12-verification-and-acceptance) · [Build prompt](#14-copy-ready-implementation-prompt).

## 1. Scope and settled product decisions

The open encounter has a standard workspace, one combined documentation template, shared interventions, clinical references, and independent care/signing controls. An intervention is a clinical action requested or recorded through the workspace. It is not another encounter template and is not merely a paragraph inside the note.

The first complete intervention release covers:

- Medication prescriptions, with draft preparation and separately authorized issuance.
- Laboratory and imaging requests, grouped for convenient composition/printing while retaining individual request identities.
- Doctor/service referrals, with recipient, clinical question and continuing ownership.
- Follow-up tasks, using the encounter implementation's existing intent/task pathway.
- Procedure **requests** where the approved service catalog supports them. Recording that a procedure was performed is a separate capability with its own evidence.

The interface can grow to include performed procedures, immunizations and other approved actions. Do not show a working-looking menu option until its service and permissions exist. Billing, dispensing, laboratory operations, admissions, theatre management, and automatic treatment recommendations are outside this slice.

### The template boundary

| Documentation template | Shared intervention capability |
| --- | --- |
| History, examination, assessment, narrative plan and counselling | Structured prescription, request, referral or task |
| Specialty/visit-type fields and layout | Type-specific composer, validation and lifecycle |
| References to selected source records where useful | Authoritative clinical action and its immutable authorized version |
| Optional navigation hint to shared actions | Authorization and permission checks independent of template hints |
| Pinned form release and response revisions | Independent intervention versions and note-time summary snapshot |

A template release must not contain medication order instances, lab requests or referral payloads. It must not issue anything on load, save or note signature. Approved specialty favorites may be offered in the intervention picker, but the clinician chooses and reviews them for this patient. No automatic prenatal tests, medicines, doses or referrals are introduced by this plan.

Narrative such as “discussed possible referral” does not create a referral. A follow-up date in a note does not create an owned task unless the clinician explicitly uses the task action. The UI should make the distinction understandable without technical terminology.

## 2. Current working-tree context and sources

The repository is changing concurrently. The following is a source inspection snapshot, not a claim that these changes have passed tests or are ready for production. Re-inspect the latest tree before implementation. The older baseline paragraph in `AGENTS.md` and the first plan's review section may lag the background work; do not overwrite those files solely to reconcile this snapshot.

| Observed source | Integration relevance |
| --- | --- |
| [EncounterWorkspace.tsx](../../web/src/features/encounters/EncounterWorkspace.tsx) | New connected workspace renders template sections, tracks a versioned draft writer, care transitions and a signing panel. Place interventions beside/below the documentation form, not inside its answers. |
| [encounter-api.ts](../../web/src/features/encounters/services/encounter-api.ts) | Existing typed request helper, same-origin CSRF flow, `Scope`, encounter identity and scoped query keys. Reuse through a stable client boundary; do not hand-edit generated types. |
| [EncounterController.java](../../api/src/main/java/ph/epr/api/encountertemplates/web/EncounterController.java) | Local-profile connected encounter endpoints include draft, care transitions and follow-up intents. Do not create a competing encounter API. |
| [EncounterContinuity.java](../../api/src/main/java/ph/epr/api/encountertemplates/EncounterContinuity.java) | Public source-obligation contract for unsigned documentation and follow-up. Extend/reuse with the encounter owner rather than importing its JDBC implementation. |
| [JdbcClinicalWorklist.java](../../api/src/main/java/ph/epr/api/carecoordination/infrastructure/JdbcClinicalWorklist.java) | Existing in-progress task projection/reconciliation. This is the starting point for follow-up continuity; it is not yet a complete referral or general task lifecycle. |
| [TenantTransactions.java](../../api/src/main/java/ph/epr/api/platformoperations/TenantTransactions.java), [TenantCommandJournal.java](../../api/src/main/java/ph/epr/api/platformoperations/TenantCommandJournal.java) | Tenant unit of work and idempotency/audit facilities exist in the in-progress source. Verify their behavior before reuse. |
| [ClinicalAuthorization.java](../../api/src/main/java/ph/epr/api/identitytenancy/ClinicalAuthorization.java) | Current contract includes scoped clinical access and reproductive access. It does not establish all the proposed prescriber, order-authorizer, referral-release and field permissions. Add explicit capabilities. |
| [JdbcEncounterSignatures.java](../../api/src/main/java/ph/epr/api/encountertemplates/infrastructure/JdbcEncounterSignatures.java) | Background work prepares and signs synthetic note artifacts. Intervention summary binding is an explicit integration change, not presumed present. Local synthetic confirmation is not production prescribing authority. |
| [V5 continuity migration](../../api/src/main/resources/db/tenant-migration/V5__add_owned_continuity_projection.sql) | Follow-up intents and owned tasks already have an in-progress schema. Do not duplicate them or reserve subsequent migration numbers from this snapshot. |
| [Prescribing boundary](../../api/src/main/java/ph/epr/api/prescribing/package-info.java), [orders/results boundary](../../api/src/main/java/ph/epr/api/ordersresults/package-info.java) | These are module declarations in the inspected tree, not complete intervention services. |

The new workspace explicitly reports unavailable allergies/critical alerts in the inspected source. Medication issuance needs a real authorized source/review workflow and clinically approved handling of unknown/stale data; hiding that message or adding a prescription form does not satisfy that prerequisite.

Authorities: [root guidance](../../AGENTS.md), [web guidance](../../web/AGENTS.md), [API guidance](../../api/AGENTS.md), [session/authentication decision](session-and-client-authentication.md), [tenancy decision](database-per-practice-tenancy.md), [product plan](../../generated-assets/doctor-centric-epr-plan.md) §§13.5–13.11 and 23, and [technical plan](../../generated-assets/epr-technical-architecture-plan.md) §§2–5. The ignored generated-assets plans may be absent in another checkout; the essential decisions are restated here.

For terminology orientation, HL7 distinguishes medication requests from reported medication use, service requests from fulfillment, and tasks from the clinical requests they help execute. This supports the separation chosen here. These references do not mandate a FHIR server, a particular interoperability version, or US-specific coding for this project. [HL7 MedicationRequest](https://hl7.org/fhir/R4/medicationrequest.html), [HL7 ServiceRequest](https://hl7.org/fhir/R4/servicerequest.html), [HL7 Task](https://hl7.org/fhir/R4/task.html).

## 3. Integration contract with the encounter workstream

### 3.1 Work ownership

| Encounter/template workstream owns | Intervention workstream owns | Coordinate explicitly |
| --- | --- | --- |
| Encounter creation, patient/Practice context, episode link, active care, note draft writer | Picker, list, composers and their draft state | One workspace mount point and navigation guard |
| Template definitions, compiler, renderer and published releases | Prescription, order and referral domain records/APIs | Authorized encounter-context public interface |
| Note revision/signature/amendment implementation | Intervention revisions, issuance/release, own documents and lifecycle | Note-time intervention manifest and concurrent-signing guard |
| Existing follow-up intent source | Follow-up UI and operational task improvements in care coordination | Single intent-to-task pathway and deduplication |
| Existing tenant route/auth foundations | Action-specific permission checks and necessary clinical prerequisites | Minimal coordinated additions to foundations |

Implement independently in feature/module files. Integrate by small patches once contracts are available. Do not replace `EncounterWorkspace.tsx`, change the template answer schema, rewrite signing, fork the tenant router, or renumber the other workstream's migrations to make interventions fit.

No application source changes are made by this planning task. During implementation, use an isolated branch/worktree when appropriate and agree shared-file edits before merging. Preserve unrelated working-tree changes. Treat all source paths here as integration pointers whose current contents must be checked again.

### 3.2 Minimal frontend boundary

Proposed contract, not an existing exported type:

```ts
type EncounterInterventionContext = {
  practiceId: string
  siteId: string
  patientId: string
  encounterId: string
  responsibleClinicianId: string
  careState: 'IN_PROGRESS' | 'PAUSED' | 'FINISHED'
  noteState: 'DRAFT' | 'READY_TO_SIGN' | 'SIGNED'
  contextGeneration: string
}

type EncounterInterventionsProps = {
  context: EncounterInterventionContext
  onPendingWorkChange: (state: {
    dirty: boolean
    mutationPending: boolean
    unresolvedOutcome: boolean
  }) => void
  onSummaryVersionChange: (version: number) => void
}
```

Derive all identifiers from the loaded authorized encounter, not editable form inputs. `contextGeneration` rejects late responses after navigation; it grants no server authority. The panel fetches server-calculated capabilities. Do not infer permission merely because the note is editable or care is in progress.

Mount a single `<EncounterInterventions>` outside the template form. Keep intervention `useForm` instances and field names separate from note answers. The parent coordinates **one** navigation blocker for note and intervention dirty state, including dismissal of an unsaved composer. Avoid multiple competing router blockers or nested HTML forms.

Signing preparation requires acknowledged note changes and no unsaved intervention composer or unresolved intervention mutation. The server still independently verifies versions and authorization. Opening/closing an intervention does not reset or save the note.

### 3.3 Minimal backend boundary

Ask the encounter workstream to expose a narrow application API for:

1. Authorizing and loading the encounter/patient/site context for a named intervention action.
2. Locking the encounter's intervention-summary boundary within the same tenant transaction.
3. Appending an immutable, source-versioned intervention summary and advancing its dedicated version.
4. Capturing that summary manifest during note-sign preparation and checking it during finalization.

The proposed public interface name is `EncounterInterventionLinks`. Exact DTO names can follow the emerging source conventions. Its internal implementation owns only associations/snapshots and note-sign integration. Prescribing, order and referral logic remains in the owning modules.

Use a separate `interventionSetVersion`; routine intervention changes must not masquerade as edits to the note's structured response revision. A material summary change invalidates an already prepared signature, with explicit refresh feedback. Ordinary background task-delivery metadata need not invalidate clinical content unless it changes what the candidate represents.

This contract is the first integration deliverable. Backend composers can be developed against a test double for it, but production-connected actions stay disabled until the real transaction/authorization integration passes. A test double must never report a production prescription as issued.

## 4. Clinician workflow

### 4.1 One entry point

The encounter contains a persistent **Interventions** section with **Add intervention**. The button opens a searchable picker with these categories:

| Visible option | Opens |
| --- | --- |
| Medication / prescription | Prescription composer |
| Laboratory order | Laboratory request composer |
| Imaging order | Imaging request composer |
| Doctor referral | Referral composer |
| Follow-up task | Owned task composer |
| Procedure request, when enabled | Service request composer configured for procedures |

Do not make the clinician select a different documentation template to perform any of these actions. A specialty can influence favorite ordering, never silently narrow permissions or exclude clinically appropriate enabled actions.

### 4.2 Add, review and act

1. Select the intervention type or a permitted favorite.
2. Show patient name plus identifier, encounter context and relevant alerts within the composer. Prefill context only; do not invent clinical content.
3. Enter/select the required details. Use dedicated fields appropriate to the type. Preserve entered data on validation, network and authorization failure according to scope policy.
4. For laboratory and imaging requests, **Add to note** records the planned request in one action. It remains internally unissued until separate authorization; the list and note candidate must say so plainly. Other intervention types retain **Save draft** preparation where applicable. **Save and add another** can return to the picker while preserving the completed record's identity.
5. Review and perform the specific action: **Issue prescription**, **Authorize order**, **Release referral**, or **Create follow-up task**. These labels must describe the actual outcome. A task may be created in one deliberate step because it is not prescription issuance.
6. Update the list only from server-confirmed outcomes. A timed-out mutation shows “Confirmation pending” and retries its original payload/key; it is not an invitation to create another record.

Keep common tasks fast. Medication composition may collect several items into one prescription document; laboratory selection may collect several tests into a requisition. Each item/request keeps an identity, version and lifecycle. Do not start with a mixed “Issue all interventions” action spanning different authorization and delivery requirements.

### 4.3 Unified list

Each row/card shows type, concise clinical summary, responsible clinician/owner, relevant due or requested time, draft/authorized/released state, and distinct delivery/fulfillment/review status where applicable. Available actions come from current capabilities and are revalidated on the server.

Suggested filters: All, Drafts, Prescriptions, Orders, Referrals, Follow-up. Show unresolved or urgent owned work clearly. Do not collapse “authorized,” “printed,” “sent,” “performed,” and “reviewed” into one green Completed badge.

A prescription containing several medicines may appear as one expandable document group with item identities. A requisition may group several independently trackable requests. A common list is a presentation view; it is not a generic intervention database that erases these distinctions.

An empty list means “No interventions added to this encounter” only after a successful authorized load. Distinguish loading, partial service failure, unavailable records, and no permission. Never leak restricted category counts or titles through filters or badges.

### 4.4 Layout and interaction

- Desktop 1440 × 1000: section below/alongside the main note, with a sufficiently wide composer sheet or dialog. Keep patient context visible.
- Portrait tablet 834 × 1194: reflow list and composer fields; avoid a thin desktop sidebar composer.
- Mobile 390 × 844: full-width/screen composer with its own readable context, accessible navigation and actions clear of the software keyboard and bottom navigation.
- Match current Dashboard tokens, Roboto, Lucide and existing shared controls. Preserve visible labels, units, focus, keyboard selection, error associations and announced outcomes.
- Escape/dismissal preserves or deliberately discards unsaved inputs with a clear choice. Close returns focus to Add intervention or the originating row. Do not stack multiple modal dialogs unnecessarily.
- No patient data in URLs beyond opaque authorized record IDs, browser persistence, diagnostic logs or unapproved telemetry.

## 5. Intervention types and minimum records

These are proposed product fields, not clinically approved datasets. Requiredness varies between draft preparation and authorization. Clinician-approved sample outputs and deployment-specific requirements must be recorded before clinical use.

### 5.1 Medication prescriptions — `prescribing`

`Prescription`: patient/Practice, originating encounter/site, actual author, responsible prescriber, clinical/authored/issued times, version/state, indication references or narrative, and immutable issued-document references.

`PrescriptionItem`: stable UUID, generic ingredient identity/text, optional brand, product/strength/formulation, dose and dose unit, route, frequency/timing, duration or applicable start/end, quantity/unit, repeat allowance where supported, patient instructions, and item-specific indication where appropriate. Preserve terminology/catalog identity and version as well as readable labels. Do not substitute product strength for administered dose.

Keep prescribing separate from reported current medication use, dispensing and administration. A saved prescription is not evidence that a patient took or obtained the medicine. Renewing creates a new authorization; reprinting uses the original immutable issue version/date. Changing an issued item requires cancellation/supersession and a new authorized revision, never silently editing the prior document.

Minimum prerequisites for issuance include current prescriber authority, required credential/Practice output data, patient context, an allergy/medication review source, and approved handling of unavailable/stale information. Detect known allergy matches and duplicate ingredients using reviewed data; disclose when interaction support is unavailable. Pregnancy-related, renal and weight-based recommendations require separate validated knowledge/rules; do not infer safety from an empty alert list.

Use approved medication sources and clinician review. A controlled free-text preparation path may be allowed by policy, but it cannot evade issuance validation or required generic-name information. Keep controlled-drug electronic issuance disabled until its current requirements and prescriber permissions are validated. The product plan's Philippine prescription fields are requirements to verify, not a legal-compliance claim made by this plan.

### 5.2 Laboratory, imaging and procedure requests — `ordersresults`

`ServiceRequest`: stable request UUID, kind, service name/code/catalog version, indication or clinical question, priority, intended destination/performer, requested time/window, patient/Practice/encounter, requester/authorizer, specimen/body site/laterality where applicable, instructions, and version.

`RequisitionGroup`: grouping/document identity for convenient selection and printing. Preserve member IDs. Do not replace a set of requests with one unstructured string; one cancelled or missing test must not mark all requests completed.

Store only clinically approved preparation instructions. An order for imaging/procedure is not evidence it was performed. Performed procedure documentation, if enabled later, belongs to a dedicated chart capability with consent, performer, site/laterality, actual time, findings, complications and aftercare.

Orders require a result/outcome owner at authorization. Support printed requisitions and manually received external reports without pretending a lab integration exists. Result receipt, patient/source matching, review and follow-up remain separate operations. Partial and corrected reports preserve originals and create the appropriate new review obligation. Unmatched reports remain quarantined for review; never link by name similarity alone.

### 5.3 Doctor/service referrals — `carecoordination`

`Referral`: UUID, patient/Practice/encounter, requesting clinician, destination provider/service/facility, referral reason and clinical question, clinically selected priority, relevant approved summary, intended timing, source-record/attachment versions, accountable owner, status/version and release evidence.

Distinguish an internal referral assignment from an external referral letter. External recipients need not be registered EPR users. Another Practice must never gain automatic chart access from the referral. Release only the approved minimum information and specific attachment versions through the documents/communication authorization boundary.

Preparing, releasing, actual delivery, recipient acknowledgement, appointment/outcome and response review are separate facts. “Print referral letter” must not assert that a specialist received or completed the referral. Track declined/unavailable destinations, unsuccessful contact, patient refusal and alternative plans with ownership and reason.

### 5.4 Follow-up tasks — `carecoordination` with the existing encounter intent

Inputs: reason/instruction, due date/window, individual responsible owner, priority where supported, source encounter/intervention, allowed assignment scope and explicit closure evidence. Booking an appointment, sending a reminder, reaching a patient and resolving the clinical obligation are distinct events.

Reuse `encounter_followup_intent → clinical_owned_task`. The picker must call this source pathway or a thin public facade around it. Do not additionally create a second generic task for the same intent. Preserve the intent UUID as a deduplication key and link the intervention list row to the resulting task.

The current source returns a `PENDING` receipt. Display “Follow-up requested · task delivery pending” until task creation/reconciliation is confirmed. Pending delivery is still an owned durable obligation. Do not show two rows for the source intent and its projection; enrich the same logical row.

Extend the simple current `OPEN/RESOLVED` task model only through reviewed transitions/migrations. Add reassignment with acceptance or a designated fallback, closure reasons/outcomes, due-window support and escalation as needed. Existing source/task identities must survive those improvements.

### 5.5 Catalogs and favorites

Keep medication, service and referral-directory catalogs separate from documentation templates. Each entry has stable identity, display label, source/version, eligibility and retirement metadata. Preserve original labels/codes on authorized records even when catalog wording changes.

Favorites and approved order sets copy suggestions into a **new draft**; they do not copy prior authorization, clinical review, patient identity or old dates. Make patient-specific changes explicit. Do not implement automatic bundles of OB-GYN interventions from template selection. Catalog administration requires scoped authority and audit; it does not grant clinical issuance rights.

## 6. Lifecycle and authorization rules

These are proposed internal states to finalize in domain contracts. Shared UI grouping must not replace source states.

| Type | Clinical lifecycle | Separate dimensions |
| --- | --- | --- |
| Prescription | `DRAFT → ISSUED`; draft may be abandoned; issued version may be cancelled/superseded with reason | Document archive/delivery; dispensing/use unknown unless separately evidenced |
| Service request | `DRAFT → AUTHORIZED`; explicit cancellation, supersession or entered-in-error where permitted | Delivery; fulfillment pending/partial/fulfilled/unable; report/review status |
| Referral | `DRAFT → RELEASED`; cancellation/supersession with reason | Delivery acknowledgement; acceptance/decline; appointment/outcome; response review/closure |
| Follow-up | Durable intent → task open → resolved with evidence | Delivery/reconciliation, owner/coverage, due/overdue, contact attempts |
| Performed procedure, later | Draft record → confirmed performed record → correction history | Do not share the request's lifecycle or infer performance from authorization |

Save draft requires permitted preparation rights; issuance/authorization/release requires the relevant clinical authority and current scope. Staff cannot impersonate the prescriber or authorizing clinician. Administrative/template permissions and provider-support membership confer no clinical action rights.

Never make one generic `PATCH status` endpoint that accepts arbitrary transitions for all types. Use domain commands with expected versions, reasons, signatures/review evidence and dependent-work checks. Preserve original history; post-authorization removal is not ordinary deletion.

### Encounter and note states

- Care in progress permits authorized new actions. Paused/finished care uses explicit policy; a finished queue does not automatically remove permission to resolve a result or complete previously prepared documentation.
- A draft note may contain issued interventions. Signing the note does not issue its remaining drafts.
- At note-sign review, show every selected intervention's exact status. Unissued drafts may be included only as clearly labeled plans/drafts with an acknowledged owner/disposition; no wording may imply they were prescribed or ordered. Local policy may require resolving specific drafts first, but the system must not force issuance merely to sign.
- Note-sign preparation blocks while the local intervention composer is dirty or a mutation outcome is unknown. Clinically signed content is built from acknowledged server revisions, not local form values.
- Signed-note artifacts remain unchanged when a request is later cancelled, fulfilled or reviewed. The live intervention list can show subsequent activity with its actual timestamps beside a separate note-time view.
- For a **new** clinical decision after signing, default to a new authorized follow-up encounter. Use a linked amendment for correcting the original documentation. Existing authorized requests may continue their fulfillment/review lifecycle without reopening the note. If an existing draft is authorized later, record a deliberate late action with current time/authority and explicit provenance; never backdate the issued status into the signed note.
- A wrong-patient intervention requires controlled entered-in-error handling and review of its downstream documents/recipients. Do not change its patient ID in place.

## 7. Backend architecture and persistence

### 7.1 Ownership and acyclic dependencies

Prescribing owns prescription tables, validation and issuance. Orders/results owns requests and results. Care coordination owns referrals/tasks. Documents/communication owns private artifacts, controlled disclosure and delivery infrastructure. Encounter/templates owns the encounter and note-time intervention associations/snapshots.

Proposed dependency direction:

```text
prescribing ────────┐
ordersresults ──────┼──> encountertemplates public context/link API
carecoordination ──┘

owning modules ──> identitytenancy / platformoperations / documentscommunication
encountertemplates ──> its existing lower-level dependencies
```

Do not add reverse encounter-module imports of prescribing/order/referral implementations merely to render a list or sign a note. Use the small encounter-owned link/summary contract below. Preserve Modulith dependency checks. No cross-module JDBC queries or repository/entity imports, even though the tenant transaction context exposes JDBC.

This plan does **not** add a generic `interventions` backend module or generic JSONB clinical record. A shared frontend feature and a typed association read model are enough for the proposed scope.

### 7.2 Encounter-owned association and summary projection

Proposed records:

- `EncounterInterventionLink`: Practice/patient/encounter, source kind, source UUID, creating actor/time and current summary revision. Unique by encounter + source kind + source UUID.
- `EncounterInterventionSummaryRevision`: immutable source version, typed status, safe structured display/print summary, owner, clinical/authored/authorized timestamps, sensitivity policy, immutable artifact references where applicable, and checksum. It contains the bounded content needed for list/note summaries, not a second editable prescription or referral.
- `EncounterInterventionSet`: dedicated monotonically increasing `interventionSetVersion` scoped to the encounter.
- `SignedNoteInterventionManifest`: the exact links/summary revisions/source versions/statuses and set version captured for one sign preparation/signed artifact.

The source module remains authoritative and creates these summaries through a **server-only public module API**. Clients cannot submit trusted summary text, source permissions or “issued” status to the association service. Use reviewed, versioned summary DTOs per kind; an arbitrary HTML blob is not a clinical summary contract.

Every material source mutation must update its association/summary and audit in the same tenant transaction. A failed summary update rolls back the source change. This makes note-sign capture consistent without synchronous calls back into each source module. Use events for subsequent worklists/notifications, not as the sole path for a signing-critical summary update.

Check source/encounter Practice, patient and site relationships before mutation. Retain original source IDs on supersession/correction. Associated records never move between patients or Practices. Database constraints reinforce matching references where possible; a polymorphic source reference additionally requires owning-service validation and consistency tests.

Restrict summary reads by the same record/field policy as their source. Do not infer that chart access permits every prescription/referral summary. Use a shared explicit authorization policy/capability boundary, and test source-versus-summary parity. Rich detail and mutations continue through source APIs. Do not expose restricted content/counts simply because it has been projected into the encounter module.

The signer must be authorized to review the complete content included in the candidate. If relevant restricted content cannot be reviewed, stop preparation with a privacy-safe explanation and an authorized resolution path; do not silently omit it or present a partial list as the complete intervention history. Summary DTOs must carry enough policy metadata for this check without exposing the restricted values to unauthorized clients.

### 7.3 Transaction and signing protocol

1. Resolve authenticated actor and current permissions, Practice route and clinical scope. Recheck source access on idempotent replay.
2. Lock the encounter intervention-set boundary **before** locking source intervention rows. Use this lock order consistently for source changes and signature capture to avoid deadlocks.
3. Revalidate encounter/source state and expected source version. Apply the domain command using its owning service.
4. Append source revision/audit and the corresponding immutable encounter summary. Advance `interventionSetVersion` for a material change and invalidate a prepared candidate as needed.
5. Write idempotency outcome and durable event/obligation in the same tenant transaction. Commit before reporting confirmed success.
6. At note preparation, capture note revision plus intervention-set version and exact summary manifest. Include both in candidate content/hash evidence. Do not asynchronously render from a later mutable list.
7. At finalization, reacquire the boundary lock and require the expected note/candidate/set versions. If an intervention was edited/issued/cancelled after preview, reject stale confirmation and request a fresh review. Never silently sign new content the clinician has not seen.

Late intervention changes after successful note signing advance the live set but preserve the signed manifest and archived bytes. Each amendment captures its own manifest. Already signed interventions retain their own evidence independent of note signing.

A slow artifact render/upload should not hold a database transaction/lock across the network. Capture a versioned candidate first, stage/verify artifacts outside that transaction, then finalize under version checks. Handle failures/retries and orphan cleanup as in the encounter plan. Database and object storage are not an atomic transaction.

### 7.4 Versions, retries and duplicate clinical intent

Use client-generatable UUIDs, optimistic source versions, immutable authorized revisions, and original idempotency keys with request hashes. Separate source version, note response revision and intervention-set version. After source mutation, refresh the list/manifest metadata without resetting note form values.

Idempotency prevents duplicate requests with the same key. It does not establish that two independently created identical tests or prescriptions are mistakes. Provide duplicate-intent warnings based on comparable active records and let an authorized clinician confirm a legitimate repeat with appropriate reason. Do not automatically merge care based on matching text.

For a multi-test draft operation, use one clearly defined same-tenant transaction and return stable item IDs. If later individual authorization/delivery operations partially succeed, report per-item outcomes; retry only unresolved original commands. Do not mark a group fully authorized because one item succeeded.

### 7.5 Follow-up, results and durable ownership

Keep unsigned-note and follow-up obligations independently owned. Reconcile the current intent/task pipeline using one source ID and monotonic source versions. A projection replay must not reopen a resolved task, overwrite reassignment, or create duplicates. Preserve operational task state separately from immutable source intent.

New requests/referrals persist an accountable owner and a durable obligation at authorization/release, before any optional notification. Their worklist projections may lag, but the authoritative source cannot become ownerless. Retain a fallback for clinician absence/revocation and require acceptance or an approved fallback when reassigning.

Evolve the currently demand-driven worklist reconciliation into a retryable, monitored tenant-aware worker where timely delivery is required. Workers carry explicit tenant context, claim jobs safely across replicas, deduplicate and back off after failure. Do not make an overdue result depend on the clinician opening the inbox to create its obligation. The existing local outbox code is a starting point to verify, not proof of a complete durable event system.

## 8. Proposed API contracts

All paths are under `/api/v1/practices/{practiceId}`. Keep the current authorized site-context convention while the other workstream settles it; the inspected client currently supplies `siteId` as a query parameter. Do not silently introduce incompatible routing. UUIDs and site selectors are context, never authorization.

### 8.1 Encounter-facing reads

| Method/path | Contract |
| --- | --- |
| `GET /patients/{patientId}/encounters/{encounterId}/intervention-capabilities` | Available kinds/actions and explicit unavailable reasons, based on current actor and source prerequisites |
| `GET /patients/{patientId}/encounters/{encounterId}/interventions` | Scoped summaries, `interventionSetVersion`, pagination and authorized category counts |
| `GET /patients/{patientId}/encounters/{encounterId}/intervention-manifests/{manifestId}` | Exact authorized note-time view; immutable signed/prepared manifest |

The common list is a read API. Mutations use type-specific services. Resolve source routes using an allowlisted kind-to-route mapping rather than accepting arbitrary URLs from stored summary data. Stale client capability data never authorizes a server command.

Illustrative list item, not a final generated wire contract:

```json
{
  "linkId": "a52b4f3e-9067-49cb-9dd9-df04f99292e9",
  "kind": "LAB_ORDER",
  "sourceId": "c7c239ab-c340-4f4e-9171-1d603a253df0",
  "sourceVersion": 3,
  "summaryRevision": 2,
  "summary": "Synthetic laboratory request",
  "clinicalState": "DRAFT",
  "deliveryState": "NOT_RELEASED",
  "fulfillmentState": "NOT_STARTED",
  "reviewState": "NOT_APPLICABLE",
  "allowedActions": ["EDIT_DRAFT", "AUTHORIZE", "ABANDON_DRAFT"]
}
```

### 8.2 Typed writes and detail

| Owner/path family | Operations |
| --- | --- |
| `/patients/{patientId}/prescriptions` | Create/list draft; get specific record; versioned draft updates; issue; cancel; supersede/renew; retrieve authorized original/reprint artifacts |
| `/patients/{patientId}/service-requests` | Create requests/groups; get/update draft; authorize; cancel/supersede; record delivery/fulfillment evidence; link reports; record review/disposition |
| `/patients/{patientId}/referrals` | Create/get/update draft; release; record delivery/acknowledgement/outcome; cancel; review response; close with evidence |
| `/patients/{patientId}/encounters/{encounterId}/follow-up-intents` | Reuse existing encounter-origin task request contract through its public service boundary |
| `/clinical-worklist` | Reuse/extend existing task list/reconciliation/resolution; add scoped reassignment and necessary outcome commands |

Use named commands such as `POST /{id}/issue`, `POST /{id}/authorization`, `POST /{id}/cancellation` and `POST /{id}/release`; agree exact names in generated OpenAPI before UI wiring. Do not implement a broad arbitrary status patch. Every create binds the origin encounter; updates cannot retarget it.

Typical command metadata: UUID idempotency header, expected source version, current relevant context version/candidate ID, actual occurrence/client time where needed, structured details, reason for exceptional transitions, and review evidence bound to the exact candidate. Server derives author/authorizer and receipt/authorization times.

Errors distinguish unauthenticated, forbidden/unavailable record, stale version, reused key with changed payload, invalid field/domain rule, missing clinical prerequisite, and unavailable tenant/service. Return a stable code, authorized field paths and correlation ID without patient content in logs. Preserve form data. A validation failure does not silently discard the old draft.

Generate TypeScript contracts through the current contract workflow after inspecting [generate-clinical-contracts.mjs](../../scripts/generate-clinical-contracts.mjs) and [clinical-openapi.json](../../api/contracts/clinical-openapi.json). The exact generator coverage may change during background implementation; do not claim these proposed endpoints already exist or manually patch generated DTOs to simulate them.

## 9. Frontend state, clinical privacy and failure handling

- Use TanStack Query for source/list/detail server state. Keys include Practice, site, patient, encounter where relevant, source kind and source ID. Reconcile invalidations after mutations and reconnect.
- Use a dedicated React Hook Form/Zod composer per type. Share context, field primitives, error rendering and command handling; avoid a single enormous form with optional properties for every kind.
- Keep a draft's UUID and each in-flight command payload/key stable through timeout retries. Additional edits create a subsequent versioned command after reconciliation. New keystrokes must not be marked saved by an older response.
- Begin with explicit intervention Save draft. Add serialized autosave only when its behavior is independently tested; do not reuse the note writer by feeding intervention payloads into template answers.
- Keep pending note and intervention edits across opening/closing reference panels. The combined route guard offers remain, save-and-leave where possible, or deliberate discard. Drafts confirmed by the server survive reload; local unsaved text is not durable offline storage.
- Disable dependent review/issue actions when catalog, authorization or required clinical inputs cannot be confirmed. Show why. An empty medication/allergy response must never imply verified absence when the service failed or the record is unreviewed.
- Handle revocation/session expiry immediately. Stop further mutations, prevent data leaking into a new user/scope, and cancel/remove inaccessible caches. Do not automatically download or persist patient content to preserve a revoked user's draft.
- Apply the same permissions to search, summaries, documents, exports, URLs, events and worklists. For a signed document with restricted content, authorize the whole artifact or create a governed redacted derivative; do not hide sensitive lines in browser CSS.
- Minimize referral disclosures. Shared-phone contacts and patient representatives have only verified permitted access; reproductive details are not automatically shared with guardians or recipients.

## 10. Documents, external delivery and clinical release gates

Prescriptions, requisitions and referral letters have their own immutable authorized document versions. Use source module review/authorization and the shared private artifact service. Preserve exact bytes, hashes, authorizer evidence, content versions and actual issue times. A browser-generated PDF or the synthetic note-signing adapter does not independently establish issuance authority.

Stage/render and verify content before reporting archive success. Keep document/storage state distinct from delivery status. A print action can record that a printable copy was produced; it does not prove it reached the patient or external recipient. A transport acknowledgement is not a clinical review or fulfillment acknowledgement.

Delivery integrations, when introduced, use durable jobs, stable external correlation IDs and safe retry semantics. If a transport cannot deduplicate, reconcile an unknown send outcome before resending; do not claim exactly-once delivery from a local idempotency key. Cancellation may require a new communication task and acknowledgement. Cancelling the local request cannot retract an already performed service or erase a delivered document.

Clinical enablement prerequisites:

| Capability | Required evidence |
| --- | --- |
| Prescription issue | Approved field/output requirements, current prescriber credentials, reviewed medication source, allergy/reconciliation behavior, approved signing/print workflow, applicable Philippine requirements |
| Lab/imaging/procedure authorization | Approved service catalog/wording, valid requester/destination handling, requisition samples, owner and missing-result process |
| Referral release | Clinically reviewed content, minimum disclosure/attachment rules, authorized recipient/delivery flow and unresolved-referral ownership |
| Follow-up task | Accepted ownership/reassignment/closure rules and tested pending-delivery recovery |
| Any patient-facing document | Private verified storage, immutable historical bytes, authorized release/reprint and readable approved output |

These gates need current official verification for the affected deployment and document type. This planning task does not resolve legal validity, controlled-drug rules, program certification or pharmacy acceptance. Synthetic engineering tests can proceed, but clinical enablement must not invent approval or fallback to unsafe silent issuance.

## 11. Delivery sequence

### 11.1 Work packages

| Package | Deliverable | Exit evidence |
| --- | --- | --- |
| I0 — Integration agreement | Current-tree review; context/link/manifest/version contract; ownership of shared files; capability/permission matrix | Encounter and intervention contract tests agree; no duplicate schema, task flow or dependency cycle |
| I1 — Shared UI and typed reads | Add intervention picker, list, composed navigation guard, capability states; explicit test adapter | Desktop/tablet/mobile interactions pass; no fixture represented as persisted clinical success |
| I2 — First connected orders slice | Service catalog, laboratory request draft, versioned edit/abandon, clinical authorization, list summary, owned result obligation and verified requisition | Save/reload and duplicate/concurrency tests; unauthorized actions denied; no claim a test was performed |
| I3 — Prescribing | Medication composer/items, prerequisites, issue/cancel/supersede/reprint and immutable document | Clinically reviewed synthetic cases; correct units/versions; missing safety context handled; real issuance gated appropriately |
| I4 — Referrals and follow-up | Referral draft/release/outcome; existing follow-up intent wired to UI; task recovery, closure and reassignment | No duplicate intent/task; external referral does not share chart; owner persists after session closure |
| I5 — Note integration and remaining order types | Exact manifest binding/invalidation, concurrent sign/mutation tests, imaging and approved procedure requests | Signed note/history unchanged by later actions; all enabled categories use shared picker/list |
| I6 — Operational hardening | Independent tenant/role tests, result/correction workflow, delivery failures, migration/restore, performance, clinical outputs | Feature acceptance matrix plus applicable broader pilot release gates |
| Later | Approved favorites/order sets, advanced integrations, performed procedures/immunizations, validated decision support | Separate clinical/domain requirements and historical compatibility tests |

Build the manifest contract in I0 and keep its integration tests active during I2–I4; do not postpone discovering a signing dependency cycle until I5. I5 completes its end-to-end user flow.

I2 is a useful first demonstration, not completion of prescriptions/referrals or an entire EPR. The requested core is complete after the enabled I0–I6 scope passes; a generic menu with disabled editors does not satisfy it.

### 11.2 Suggested file ownership

Proposed paths; inspect current conventions before creating them:

```text
web/src/features/interventions/
  EncounterInterventions.tsx
  InterventionPicker.tsx
  InterventionList.tsx
  components/                   # shared composer shell and status presentation
  services/                     # scoped read model and typed adapters
web/src/features/prescribing/   # medication composer and source services
web/src/features/orders/        # laboratory, imaging, procedure-request composer
web/src/features/referrals/     # referral composer and source services
web/src/features/follow-up/     # existing intent/task UI adapter

api/src/main/java/ph/epr/api/prescribing/
api/src/main/java/ph/epr/api/ordersresults/
api/src/main/java/ph/epr/api/carecoordination/
  domain/ application/ infrastructure/ web/ within each owning module

api/src/main/java/ph/epr/api/encountertemplates/
  EncounterInterventionLinks.java       # coordinated public API
  <owned association and manifest implementation>
api/src/main/resources/db/tenant-migration/
  <next available additive migrations, assigned at integration>
```

Do not fork common patient identity/tenant/authentication infrastructure. Avoid a new global store for clinical records or a new generic form engine; neither is needed for this interface. Reuse the current stack and dependencies unless a concrete requirement justifies another library.

### 11.3 Migration and rollout

Use additive tenant migrations, observed canary rollout and per-Practice capability flags. Never edit an already applied migration, including the other workstream's new migrations. Read the actual migration inventory immediately before adding a file.

Introduce independent capability flags for draft preparation, clinical authorization and each intervention kind. Existing signed notes without intervention manifests remain valid historical artifacts; display “No intervention snapshot captured by this version,” not a fabricated empty historical list. Do not rerender or backfill them from today's live records.

For existing follow-up intents, reuse their source IDs when populating associations. Make backfill idempotent, authorized and explicitly distinguish migration-recorded time from original occurrence. Do not recreate original prescriptions/referrals from narrative text or assign invented signatures.

Rollback disables new affected commands while preserving read/history, unresolved work and verified artifacts. Retiring catalog/favorite entries affects new selections only. A code rollback must not cancel clinical requests automatically.

## 12. Verification and acceptance

### 12.1 Required cases

| Concern | Test and expected result |
| --- | --- |
| Template independence | Same shared intervention panel works with general, prenatal and gynecology templates; no intervention payload appears in template answers |
| Context | Manipulated Practice/patient/site/encounter/source IDs are denied; pending UI responses cannot enter another chart |
| Permissions | Staff preparation does not issue; support/template admin cannot prescribe; revoked/expired membership and credentials block new actions and replay |
| Source-summary parity | Restricted intervention content, counts, print summaries and document links cannot bypass the source policy |
| Draft resilience | Failed save preserves inputs; reload restores only server-confirmed drafts; opening composer does not reset note; one navigation guard covers both |
| Retries | Same frozen payload/key returns same record/outcome; changed payload with same key conflicts; unknown issue outcome is reconciled |
| Concurrent edits | Expected versions prevent overwrite; issued records cannot be edited; explicit supersession retains originals |
| Group operations | Multi-test/item IDs stable; failure/partial results are explicit; one completed/cancelled test does not complete/cancel the entire group |
| Medication | Strength/dose/unit distinctions; duplicate ingredient/allergy scenarios using reviewed fixtures; missing or stale safety context; no dispensing inference |
| Orders/results | Printed is not sent/performed; partial/corrected result; unmatched report; delayed missing result; corrected report creates new review obligation |
| Referral | Release is not receipt/attendance; destination failure, refusal and cancellation retain owner/history; no automatic cross-Practice access |
| Follow-up | Existing intent plus projection appears once; replay cannot reopen resolved work; pending delivery recoverable; reassignment survives reconciliation |
| Ownership | Ending care/session and clinician offboarding do not orphan result/referral/task obligations; fallback and acknowledgement paths exercised |
| Note preparation | Concurrent intervention save/issue/cancel changes set version and invalidates stale candidate; no lock inversion/deadlock |
| Signed history | Later fulfillment, cancellation, catalog change or document renderer update does not change prior manifest/bytes; old notes are not backfilled falsely |
| Documents/delivery | Render/upload/database finalize failure; private authorization; original reprint; unknown external send outcome and failed cancellation notice |
| Responsive/accessibility | Keyboard picker/form, labels/units/errors, focus return, long summaries, many items, zoom and mobile keyboard at all required layouts |
| Isolation/operations | Two tenant databases, pooled routing, migrations, outbox retry/worker failure, restore of records/artifacts/audit without affecting another Practice |

### 12.2 Demonstration scenario

Use synthetic patients only. Do not embed unreviewed clinical recommendations in fixtures.

1. Open an existing prenatal encounter with unsaved narrative; add a laboratory draft through Add intervention without losing that narrative.
2. Save/reload the laboratory draft, then explicitly authorize it with a result owner and verified requisition. The note remains unsigned and the request remains unfulfilled.
3. Prepare a prescription, demonstrate missing-prerequisite behavior, then use approved synthetic sources to exercise issuance/reprint mechanics without claiming production authority.
4. Add a doctor referral and follow-up intent; show pending task delivery, reconcile once, and show one owned task.
5. Prepare note signing, change an intervention in another session, and verify stale confirmation is rejected with preserved work.
6. Sign a refreshed synthetic candidate. Cancel a request later and show the live list changes while original note bytes/status snapshot remain unchanged.
7. Close care/session, process a later report/referral outcome, and verify the obligation still has an authorized owner.
8. Repeat relevant flows at desktop, portrait tablet and mobile; attempt unauthorized access from another Practice and a staff/support identity.

### 12.3 Actual commands and evidence

For frontend implementation, from repository root:

```sh
npm --prefix web run lint
npm --prefix web run test
npm --prefix web run build
```

For API changes, run `./mvnw verify` from `api/` with Java 25 and Docker/Testcontainers available. Keep PostgreSQL-specific isolation, constraints and transaction tests; do not replace them with H2-only coverage. Recheck current scripts and the generated-contract workflow before use.

Rendered UI changes require actual interactions, state assertions, inspected screenshots and console/error checks at desktop **1440 × 1000**, portrait tablet **834 × 1194**, and mobile **390 × 844**. Follow current `web/AGENTS.md` and applicable clinical/React/testing skills. The inspected `web/package.json` has no `test:e2e` script; never claim it ran. Temporary browser checks or a deliberately introduced suite must be reported accurately.

For this document-only task, verify local links, example JSON, section anchors, commands and whitespace. Do not run/claim application tests on the concurrently changing implementation solely to validate this Markdown file.

## 13. Decisions and completion boundary

Defaults settled by this plan: one shared picker/list; typed domain ownership; no template-based action instances; explicit type-specific authorization; versioned note-time summaries; one follow-up pathway; no generic backend intervention module; no automatic clinical recommendations or mixed bulk issuance.

Before implementing connected actions, settle these concrete contracts with the relevant workstream/clinical owner:

| Decision | Proposed default or required evidence |
| --- | --- |
| Context/link API and lock order | Public encounter API; encounter-set boundary first; one tenant transaction |
| Signature integration | Note revision + intervention-set version + immutable summary manifest |
| Actions after care finish/signing | Continue existing lifecycle under policy; new decisions use explicit follow-up context; preserve signed snapshots |
| Medication/service sources | Reviewed, versioned local sources initially; no assumed online catalog integration |
| Clinical output/authority | Approved samples and actual prescriber/requester/referral permissions; synthetic signing is insufficient |
| Referral delivery | Verified private document/print workflow initially; external transmission only with explicit integration evidence |
| Procedures | Request capability initially; performed record only when its domain service is implemented |
| Follow-up ownership | Reuse source intent UUID and single task; extend reassignment/closure without duplicating the pipeline |

The complete feature must deliver all enabled core types with persisted workflows, independent history, note integration, ownership and failure handling. A working picker, a list of free-text “interventions,” or a build without server authorization tests is not completion. Broader EPR pilot gates remain necessary before real-patient use.

## 14. Copy-ready implementation prompt

The following prompt is intended for a separate implementation workstream. It explicitly preserves the encounter/template work already underway.

```text
Implement the shared encounter intervention feature using
docs/architecture/encounter-interventions-implementation-plan.md.

The encounter/template implementation is being developed separately. Do not replace
its workspace, schema, draft writer, tenant routing, signing flow or existing
follow-up pipeline. Re-inspect the current working tree and preserve unrelated
changes. Read AGENTS.md, web/AGENTS.md, api/AGENTS.md, this intervention plan, the
companion encounter-template plan and accepted authentication/tenancy decisions.
Apply the relevant clinical, React, design and browser-testing skills for UI work.

DELIVERY TARGET
Complete work packages I0–I6 for medication prescriptions, laboratory/imaging
requests, doctor referrals and follow-up tasks. Include procedure requests only
when the approved service capability exists. A picker or draft-only demo is an
intermediate milestone, not the complete feature. Use synthetic development data;
real clinical enablement depends on the specified clinical and operational gates.

PRODUCT EXPERIENCE
Mount one shared Interventions section in the open encounter, independent of the
documentation template. Provide one Add intervention button, searchable type picker,
type-specific composers, and one grouped/filterable list. Keep patient identifiers,
Practice/site and clinical alerts visible. Save draft does not issue/send. Use clear
Issue prescription, Authorize order, Release referral and Create follow-up task
actions with actual server-confirmed outcomes. Permit fast repeated entry and
within-type groups while retaining item identities. Do not implement mixed Issue all.

INTEGRATION FIRST
Agree/implement the small encounter context/link/manifest API and frontend mount
contract in section 3. Keep intervention forms outside template answers and outside
the note HTML form. Coordinate one navigation blocker for both kinds of edits.
Reuse the existing scoped client/CSRF flow and generate OpenAPI contracts properly.
Use independent intervention source versions and an interventionSetVersion; do not
overwrite or reset note drafts after an intervention mutation. Do not hand-edit
generated types or reserve migration numbers before inspecting the latest inventory.

MODULES
Prescribing owns prescriptions/items and issue/cancel/supersede/renew behavior.
Orders/results owns requests, requisition groups, results and review obligations.
Care coordination owns referrals and operational tasks. Reuse the existing
encounter_followup_intent -> clinical_owned_task pathway; never create a second
task for the same intent. Documents/communication owns verified private artifacts
and controlled release infrastructure. Encounter/templates owns only the source
links, bounded summary revisions, set version and signed-note manifest integration.
Maintain acyclic Modulith dependencies and public module APIs; no repository or
table access across module ownership. Do not create a generic intervention database
or a new generic backend module for this UI abstraction.

CLINICAL INTEGRITY
Each source record carries Practice/patient/encounter, actual author, responsible
owner, clinical/recording/authorization times and versions. Do not infer dispensing,
performance, receipt, result review or clinical closure from authorization/printing.
Preserve original issued documents and revisions; later edits require correction,
cancellation or supersession. No patient retargeting or deletion of performed care.
Keep prescription strength separate from dose, use approved generic/product data,
validate source/context and permissions, and surface missing allergy/medication
review honestly. No unapproved pregnancy rules, risk scores, doses, regimens,
screening schedules or automatically ordered prenatal bundles. A synthetic note
signature is not production prescription authority. Require clinical approval of
content/outputs and deployment-specific issuance requirements before enablement.

TRANSACTIONS, SIGNING AND OWNERSHIP
Use trusted tenant routing, current action-specific authorization, UUIDs, expected
versions and original idempotency keys/request hashes. Reauthorize on retries.
Update a source and its immutable encounter summary in one tenant transaction via
the public link API; acquire the encounter-set lock before source locks. Clinical
summary changes advance interventionSetVersion and invalidate a prepared note
candidate. Note signing captures/checks the exact note revision and intervention
manifest; it never authorizes pending prescriptions/orders/referrals. Preserve
signed bytes when live intervention states later change. Do not hold database locks
over artifact rendering/network storage. Handle staged artifact/finalization errors.
Persist owned obligations before optional notifications. Reconcile/deduplicate
tasks and events without reopening resolved work or overwriting reassignment.
Session closure or clinician departure cannot orphan pending clinical work.

FAILURE AND PRIVACY
Preserve authorized local inputs after failures/conflicts; distinguish loading,
empty, missing prerequisite, denied, stale, disconnected and unknown command outcome.
Retry frozen payload/key rather than duplicating orders. No silent last-write-wins,
browser clinical persistence, permission inference from editable notes, or unverified
success states. Protect source records, summaries/counts, artifacts, exports, search
and events equally. Referral release never automatically shares another Practice's
chart. Typed source services remain authoritative; clients cannot forge summaries.

DELIVERY ORDER
1. Establish integration/permission contracts and tests against the latest source.
2. Build shared panel/picker/list and a connected lab-request draft/authorization
   slice with result ownership and verified requisition output.
3. Add prescribing, then referral and existing follow-up integration, each with
   its own validation, authorization, immutable documents and lifecycle tests.
4. Complete note-sign manifest integration and imaging/approved procedure requests.
5. Complete failure, security, continuity, migration/restore and clinical-output gates.
Keep independently owned engineering work moving when a clinical decision is pending;
document the exact gate and do not invent approval or claim production readiness.

VERIFICATION AND REPORT
Test manipulated scope/permissions, source-summary access parity, staff/support
denial, retries, concurrent edits, grouped outcomes, medication context, late/corrected
results, referral failure, deduplicated tasks, reassignment, note-sign races and
unchanged signed bytes. Use PostgreSQL Testcontainers and keep ModularityTests passing.
Run actual frontend lint/test/build scripts and ./mvnw verify from api/. Verify actual
UI interactions/screenshots/console health at desktop 1440x1000, tablet 834x1194 and
mobile 390x844, including keyboard, long content, error and context-switch cases.
Do not claim nonexistent e2e commands ran. Report implemented flows, integration
changes, tests/evidence and remaining clinical/operational gates. Update relevant
documentation without overwriting the other workstream's unrelated work.
```

## 15. Planning verification and limitations

This document is based on inspected in-progress source and accepted product/architecture requirements. The background implementation may change interfaces, migrations or files after inspection; the integration contract must be reconciled before coding. The document does not certify the current source, clinical outputs, legal requirements or production deployment.

Only this new Markdown file is the deliverable for this planning task. Local links, section anchors, example JSON, code fences, existing command references and whitespace were checked successfully. Application tests and browser checks belong to implementation and were not run for this documentation-only change.
