# Dynamic encounter templates: OB-GYN implementation plan

Status: proposed implementation plan, not implemented functionality or clinical approval.

Prepared: 2026-09-19.

Priority: **OB-GYN first**, as requested; retain a general outpatient core and a reusable engine for other specialties.

Deliverable: current-state review, implementation design, delivery gates, and the copy-ready build prompt in section 15.

Quick navigation: [Current review](#2-review-of-the-current-implementation) · [OB-GYN pack](#4-ob-gyn-template-pack-and-clinical-data-ownership) · [Template engine](#5-template-definition-composition-and-publication) · [APIs and persistence](#7-persistence-module-boundaries-and-apis) · [Delivery sequence](#11-delivery-sequence-and-concrete-work-packages) · [Verification](#12-verification-and-acceptance-matrix) · [Build prompt](#15-copy-ready-implementation-prompt).

## 1. Recommendation and scope

Build **one encounter workspace driven by immutable, published template releases**. Compose each release from common outpatient sections, specialty content, encounter-type content, and Practice requirements. Keep clinician layout preferences separate from clinical meaning. Begin with engineer-authored, clinically reviewed template definitions; add a visual authoring interface later.

OB-GYN needs several encounter types, not one large OB-GYN form. Start with general gynecology initial/follow-up, prenatal initial/follow-up, and postpartum follow-up. Implement prenatal initial and follow-up as the first connected vertical slice because they exercise longitudinal episodes, reusable observations, conditional questions, repeatable history, and historical integrity. Build the remaining forms on that proven engine.

The first connected slice must create an authorized encounter, select and pin its template, capture structured and narrative information, save/reload a versioned draft, and reuse a pregnancy episode across visits. Signing, amendments, and clinical ownership are required subsequent gates before real-patient use. A draft-only milestone is an engineering milestone, not a complete EPR release.

This plan does not authorize medication regimens, diagnostic thresholds, screening schedules, automatic normal findings, or clinical decision support. Candidate clinical fields below require the pilot OB-GYN's review. Specialty expansion must add approved content and genuine domain capabilities where needed; it must not create a new copy of the encounter page for every specialty.

### Scope boundaries

| Include | Defer or require a separate approved capability |
| --- | --- |
| Practice-scoped template catalog and governed publication | Drag-and-drop visual builder until the engine and publication gates work |
| Queued and authorized non-queued encounters | Inpatient obstetrics, labor ward, theatre, admissions, billing and claims |
| Pregnancy episodes, dating provenance, relevant history, outpatient observations | Automatic redating, risk scoring, screening/dose rules without approved sources and tests |
| Note drafts, sign preparation, immutable signed artifacts, amendments | Offline signing, browser clinical caches, arbitrary template code |
| Links to existing clinical facts and owned follow-up | Automatic prescriptions/orders or a second specialty medication/result database |
| Synthetic development fixtures and previews | Real-patient launch before the broader product/security/operational gates |

## 2. Review of the current implementation

Review basis: the working tree on 2026-09-19, including existing uncommitted encounter/chart changes. This was a source and architecture review, not a rendered browser audit. The limitations below are implementation gaps relative to the requested feature; disabled demo controls are not production regressions.

| Priority | Evidence | Finding and implementation consequence |
| --- | --- | --- |
| Foundation | [Encounter.tsx](../../web/src/features/chart/Encounter.tsx), lines 21–25; [model.ts](../../web/src/features/chart/model.ts), lines 6–12 | Clinical lookup is a fixture lookup, limited to one synthetic chart. There is no persisted encounter service or patient authorization boundary behind it. Build tenant routing, clinical authorization and persisted patient references before enabling creation. |
| Foundation | [PatientChart.tsx](../../web/src/features/chart/PatientChart.tsx), lines 38 and 49; [Encounter.tsx](../../web/src/features/chart/Encounter.tsx), line 73 | Start consultation, non-queued creation, draft save and signing are disabled. Implement actual commands and server-confirmed outcomes; merely enabling buttons would not implement care. |
| Template engine | [model.ts](../../web/src/features/chart/model.ts), lines 6–7; [Encounter.tsx](../../web/src/features/chart/Encounter.tsx), lines 16–20 and 60–64 | `EncounterNote` is a fixed collection of strings and diagnosis strings. `template` is display text. Neither defines a schema, release identity, typed quantities, conditional rules, repeated groups, nor extraction mappings. Replace this as the connected domain contract. |
| Historical integrity | [ChartShared.tsx](../../web/src/features/chart/ChartShared.tsx), lines 37–39; [Encounter.tsx](../../web/src/features/chart/Encounter.tsx), line 59 | The release sheet explains pinning but has no stored release. Signed fixtures correctly refuse to reconstruct absent original documents. Preserve that behavior until exact archived artifacts exist. |
| Reuse | [Encounter.tsx](../../web/src/features/chart/Encounter.tsx), lines 51–53 and 68–70 | Heading, site, author, observations and problem references are tailored to the historical fixture. The connected workspace needs encounter-specific metadata and clearly dated chart references. Do not propagate the fixture's finished-care state or later measurements into a new visit. |
| Persistence | [Encounter.tsx](../../web/src/features/chart/Encounter.tsx), lines 30–41 and 58 | Draft state and its baseline are local memory with a navigation guard. There is no revision receipt, autosave, reconciliation or conflict recovery. Retain the guard, but base saved state on acknowledged revisions. |
| Clinical data | [Encounter.tsx](../../web/src/features/chart/Encounter.tsx), lines 43–47 and 62 | Visit diagnoses are free-text chips with case-insensitive deduplication. Introduce stable diagnosis IDs, certainty and optional approved terminology; preserve clinician narrative and permit symptoms/uncertainty. Do not force unverified coding. |
| Backend | [encountertemplates/package-info.java](../../api/src/main/java/ph/epr/api/encountertemplates/package-info.java); [tenant foundation migration](../../api/src/main/resources/db/tenant-migration/V1__create_tenant_foundation.sql) | The encounters module is a declared boundary, not an implementation. Tenant migrations currently establish identity and sites, not patients, pregnancy episodes, encounters or templates. Plan these as new capabilities. |

Preserve the current separation of chart review from encounter creation, care state from note state, ongoing problems from visit diagnoses, and unknown/unreviewed data from confirmed absence. Preserve the route remount boundary in [App.tsx](../../web/src/App.tsx), line 42, or replace it with an equally explicit practice/patient/encounter identity boundary; do not introduce draft leakage during refactoring.

### What “more dynamic” should mean

1. An approved template release changes the fields and grouping without adding a specialty-specific page component.
2. Visit type determines the form; specialty narrows the catalog.
3. Sections can reveal relevant follow-up questions without erasing entered answers.
4. Repeated entities have stable identities, such as prior pregnancies or observations associated with a fetus.
5. Existing chart and episode information is referenced with provenance and reviewed explicitly.
6. Useful facts remain relational and queryable across template versions.
7. Publishing a new version affects future selections; historical encounters remain stable.
8. Personalization changes navigation/density, never clinical requirements, permissions or signed output.

## 3. Design authority and source mapping

| Source | Requirements carried into this plan |
| --- | --- |
| [Root instructions](../../AGENTS.md), [frontend instructions](../../web/AGENTS.md), [API instructions](../../api/AGENTS.md) | Tenant isolation, independent lifecycle commands, server authority, modular boundaries, Dashboard baseline, three-layout verification |
| [Product plan](../../generated-assets/doctor-centric-epr-plan.md), §§3–7, 13–13.5, 13.8–13.11, 22–23 | Composition, exact releases, typed facts, OB-GYN episodes, clinical permissions, phased visual builder, continuity and acceptance gates |
| [Technical plan](../../generated-assets/epr-technical-architecture-plan.md), §§2–5 | Online-first saves, PostgreSQL transactions/events, provider approval, storage integrity and release gates |
| [Authentication decision](session-and-client-authentication.md) | Transport-neutral actor, current membership, JDBC browser sessions, separate clinical/support origins |
| [Tenancy decision](database-per-practice-tenancy.md) | Practice databases, trusted routing, bounded pools, fleet migrations and no cross-database clinical transaction |
| [Encounter concept notes](../../generated-assets/epr-patient-chart-encounter-v1-notes.md) and [concept prompt](../../generated-assets/epr-desktop-encounter-v1-prompt.txt) | Patient context, separate actions, read-only pinned release, reference panels and historical rendering |

The generated-assets sources exist locally but are Git-ignored. Essential decisions are restated here so another checkout can use this document. If those sources are absent, report the missing references and use the committed instructions and this plan; do not pretend to have read them.

The implemented Dashboard remains the visual authority. This proposal specifies workflow and architecture; it does not replace the approved visual system or claim screenshots have been verified.

## 4. OB-GYN template pack and clinical data ownership

### 4.1 Initial catalog

These are proposed stable keys, not existing records. Versions below start at `1.0.0` only after approval and publication.

| Template key | Purpose | Candidate content beyond common outpatient sections | Episode behavior |
| --- | --- | --- | --- |
| `general.outpatient` | General encounter fallback when permitted | Reason, relevant history, examination, observations, assessment, plan, disposition and follow-up | Optional applicable episode |
| `obgyn.gynecology.initial` | Initial gynecologic consultation | Menstrual and relevant reproductive history, presenting concern, prior procedures, relevant reviewed screening history, examination consent/chaperone context where applicable | Pregnancy is not presumed or required |
| `obgyn.gynecology.followup` | Follow-up of a gynecologic concern | Interval history, treatment experience as reported, prior results with review status, findings assessed today, revised plan | Reuse applicable episode/problem references |
| `obgyn.prenatal.initial` | First prenatal assessment documented by this Practice | Relevant obstetric history, selected pregnancy episode, dating evidence, available prior care records, observations, clinician-assessed concerns and care plan | Select an existing episode or explicitly create one; external prior care does not imply a new pregnancy |
| `obgyn.prenatal.followup` | Subsequent prenatal visit | Interval history, current observations, reviewed results, episode changes, counselling, plan and owned follow-up | Reuse the selected pregnancy episode; do not copy its history into a new episode |
| `obgyn.postpartum.followup` | Outpatient postpartum review | Sourced pregnancy/outcome summary, current concerns, recovery and examination as assessed, counselling and follow-up | Link the relevant completed/ongoing episode; support incomplete external outcome records |

All templates include a concise narrative path. A missing optional measurement must not force a fabricated value. “Required” must specify whether a value, an explicit assessment state, or an authorized exception with reason satisfies it.

Future packs may include family planning, menopause, infertility and outpatient procedures after their clinical workflows are approved. Pediatrics and ophthalmology should reuse the engine, with their own domain records and validated measurements. They are not part of the first OB-GYN delivery commitment.

### 4.2 Separate three data scopes

| Scope | Examples | Ownership and update behavior |
| --- | --- | --- |
| Patient longitudinal | Allergies, medication statements, problems, reviewed reproductive/obstetric history | Owning chart services preserve identity, source, authorship and correction history; a new visit does not reset these |
| Pregnancy episode | Episode identity, recorded status, dating assessments, chosen dating basis, relevant outcome references | Proposed OB-GYN capability inside `clinicalchart`; create/update explicitly, preserve versions, link multiple encounters |
| Encounter | Today's account, assessment, observed measurements, reviewed source versions, plan, disposition | `encountertemplates` owns responses and notes; reusable measurements belong to chart services with response provenance |

For this slice, place the clinical pregnancy aggregate in `clinicalchart`. Keep tasks/referrals in `carecoordination`. If a generic `EpisodeOfCare` is later introduced there, define a migration and ownership contract rather than duplicating the pregnancy record. This is a proposed ownership decision, not an existing API.

### 4.3 Pregnancy and obstetric history model

- `PregnancyEpisode`: UUID, practice/patient IDs, optimistic version, clinician-recorded status, responsible clinician, source, recorded/clinical times, and links to encounters. Clinical review must finalize the allowed episode transitions. Use explicit closure with outcome or known uncertainty; do not infer a completed pregnancy from elapsed time.
- `PregnancyDatingAssessment`: immutable assessment version, source type, source record/version, relevant source dates and precision, clinician-recorded estimated date where available, method, actual author, review status, and reason for replacing the selected basis. Retain competing evidence.
- The episode points to its selected dating assessment. Changing it requires an authorized explicit command, version check and reason. Existing signed notes retain the dating basis and displayed values used at signing.
- `ObstetricHistoryEntry`: stable item UUID, prior pregnancy facts as known, date precision, source, review status, and version. Preserve reported aggregate history when individual outcomes are unavailable. Do not auto-derive or reconcile gravida/para notation until definitions and handling of multiple gestation are clinically approved.
- If multiple gestation is supported, use stable fetus identifiers scoped to the episode and explicit per-measurement association. Renaming/reordering display labels must not swap histories. Do not infer a fixed fetus count or create patient identities for fetuses automatically.
- Keep maternal and child records separate. A postpartum note can reference sourced outcome information; creating/linking a newborn chart requires its own identity and authorization workflow.
- Do not select a template or expose reproductive information solely from demographic sex, age, marital status or a presumed pregnancy. Use authorized clinical context and clinician selection. Unknown dates, declined questions and uncertain pregnancy status remain representable.

Gestational-age display/calculation is a separately versioned capability. Before enabling it, define source precedence, date-only arithmetic, assessment date, uncertain inputs, corrections and clinician approval. Do not silently compute from the device's current date or replace a clinician-selected dating basis. The first engineering slice can store and display clinician-entered, sourced dating information without activating a calculator.

### 4.4 Clinical content approval packet

For each template, obtain: clinical owner; exact encounter purpose; minimum dataset; allowed missing/declined states; visit-stage requirements; sensitive fields; source terminology; sample completed and incomplete notes; approved output; exception policy; and test cases. Record approver, approved definition checksum, time and change summary.

WHO's antenatal and postnatal Digital Adaptation Kits provide structured workflow and data-dictionary references for clinical review. They are reference material, not automatic approval of this proposed pack or Philippine program compliance. The publication pages were checked; their full annex datasets were not audited in this review. [WHO antenatal DAK](https://www.who.int/publications/who-guidelines/9789240020306), [WHO postnatal DAK](https://www.who.int/publications/i/item/9789240090347).

## 5. Template definition, composition and publication

### 5.1 Records

| Record | Minimum contract |
| --- | --- |
| `EncounterTemplate` | UUID, Practice, stable key, title, specialty, encounter type, ownership |
| `TemplateModuleVersion` | Immutable version of reusable section/field definitions; explicit dependencies |
| `TemplateDraft` | Editable versioned composition, draft revision, validation results, change summary |
| `TemplateApproval` | Exact approved draft/flattened checksum, clinical approver, governance basis, time; edits invalidate approval |
| `EncounterTemplateRelease` | UUID, template ID, unique business version, flattened definition JSONB, schema version, checksum, pinned terminology/rule/renderer/extraction versions, publication actor/time |
| `TemplateAvailability` | Versioned, audited Practice/site/encounter-type eligibility, effective interval and retirement state referencing a release |
| `TemplatePreference` | Clinician favorites/default suggestion and allowed layout preferences; scoped to Practice, never grants access |

Separate immutable release content from mutable availability metadata. Retirement and restoring an earlier release for **new** encounters must not rewrite the old definition or historical selection evidence. Do not hard-delete a referenced release.

### 5.2 Publication pipeline

1. Resolve explicit module versions: common outpatient + OB-GYN + encounter type + Practice requirements.
2. Reject duplicate/reused field identities with conflicting meaning; cyclic dependencies; invalid references; unknown widgets; unbounded groups; and unsupported schema/rule versions.
3. Reject overrides that weaken mandatory Practice requirements, sensitivity controls or allowed author roles. Resolve conflicts explicitly; do not use a generic last-write-wins object merge.
4. Flatten into a deterministic release definition; record canonical serialization and its checksum algorithm. Store module lineage for authoring, not runtime inheritance.
5. Validate definitions and synthetic responses, including conditional, print, extraction and accessibility behavior. Preview desktop, tablet, mobile and the clinical document.
6. Capture clinical approval against the exact content checksum. Any subsequent change requires validation and approval again.
7. Publish an immutable release in the Practice database with actor, approver, version and audit event. Update availability under optimistic concurrency; reject ambiguous active defaults.
8. Retire or restore selection eligibility through separate audited commands. Existing encounters continue to load their pinned release.

Keep template lifecycle `DRAFT → PUBLISHED → RETIRED`. Review submission/approval are workflow metadata, not a parallel set of clinical encounter states.

A provider administrator with a current explicit managed-administration grant can configure and preview synthetic data and execute approved publication. They cannot approve as an absent clinician or obtain chart access from that grant. The clinical approver and actual publisher remain separately attributable.

### 5.3 Definition contract

Use a versioned application definition validated against a JSON Schema. The application definition is not itself arbitrary executable JSON Schema logic. Keep a bounded declarative rule language and a finite, reviewed component registry. JSON Schema supports structural conditional validation, but does not itself supply clinical authorization, rendering or provenance. [JSON Schema conditional validation](https://json-schema.org/understanding-json-schema/reference/conditionals).

Minimum field metadata:

| Area | Definition |
| --- | --- |
| Identity | Permanent field UUID; readable key; stable concept reference; never reuse an ID for different meaning |
| Type | Text, multiline text, integer, decimal, quantity, date, datetime, single/multiple choice, explicit assessment state, reference, or repeatable group |
| Quantity | Allowed unit codes, precision and measurement context; retain original value/unit and approved conversions separately |
| Meaning | Clinical concept/system/code/version where approved; labels remain display text |
| Input | Label, help, cardinality, reviewed length/size limits, permitted missing states, source/review controls |
| Rules | Visibility, conditional requiredness and bounded validation; stage-specific requirements |
| Access | Sensitivity class and authoring/read policy identifiers, intersected with server permissions |
| Output | Print group/order, inclusion rules, explicit absence wording, summary behavior |
| Extraction | Allowlisted owning service/concept mapping version; never a client-selected SQL table/column |

Use reference widgets for diagnoses, observations, allergies, medications, pregnancy episodes and tasks where those records have lifecycle behavior. A template is not a substitute for their services.

The following JSON is an **illustrative excerpt of the proposed application format**, not an approved clinical form, final OpenAPI contract or published release. UUIDs are synthetic.

```json
{
  "schemaVersion": "1.0",
  "templateKey": "obgyn.prenatal.followup",
  "specialty": "OB_GYN",
  "encounterType": "PRENATAL_FOLLOWUP",
  "businessVersion": "1.0.0",
  "requiredCapabilities": ["pregnancyEpisodeReference", "versionedDraft"],
  "sections": [
    {
      "id": "640f0fd4-99ea-4a61-a59c-a59059211a31",
      "key": "intervalHistory",
      "label": "Interval history",
      "fields": [
        {
          "id": "4c869d59-fcb7-4782-98f9-af9fd00a8d51",
          "key": "concernsReviewed",
          "type": "singleChoice",
          "label": "New concerns discussed",
          "options": [
            {"code": "PRESENT", "label": "Concerns reported"},
            {"code": "NONE_REPORTED", "label": "No new concerns reported"}
          ],
          "allowedMissingStates": ["NOT_ASSESSED", "UNKNOWN", "DECLINED"],
          "defaultValue": null
        },
        {
          "id": "09a25fca-9c19-4ccf-9ef1-bd093e04a9b9",
          "key": "concernDetails",
          "type": "multilineText",
          "label": "Concern details",
          "visibleWhen": {
            "op": "eq",
            "fieldId": "4c869d59-fcb7-4782-98f9-af9fd00a8d51",
            "value": "PRESENT"
          },
          "requiredWhen": {
            "stage": "SIGN",
            "op": "eq",
            "fieldId": "4c869d59-fcb7-4782-98f9-af9fd00a8d51",
            "value": "PRESENT"
          },
          "onHidden": "RETAIN_AND_REQUIRE_REVIEW_IF_ANSWERED"
        }
      ]
    }
  ]
}
```

### 5.4 Answers and repeated groups

Address answers by field UUID plus stable ancestor group-item UUIDs, not labels, array indexes or React positions. For example, deleting prior-pregnancy row 2 must not change row 3's identity or redirect its correction history.

An answer envelope includes typed value **or** an explicit missing state; source; original clinical time; author; recorded time; review status; and any source record/version. Distinguish missing, `NOT_ASSESSED`, `UNKNOWN`, `DECLINED`, and `NOT_APPLICABLE` from a clinically asserted negative or zero. Fetching and failure are transport/UI states, not saved clinical answers.

The server supplies trusted author and receipt timestamps. Client-provided author IDs or role labels cannot override authenticated attribution. Preserve copied source authors separately from the clinician who imports/reviews the value.

### 5.5 Conditional behavior and validation

- Initial operators: equality, set membership, presence, all/any/not, and type-safe comparisons where approved. Define null/unknown behavior explicitly; do not coerce unknown to false or zero.
- Resolve dependencies at publication. Reject cycles, excessive nesting, excessive group sizes, unsafe regex and unbounded execution. No JavaScript, `eval`, SQL, network calls or remote schema fetches from definitions.
- A reveal/collapse is presentation behavior. Server authorization always filters reads and enforces writes independently.
- Hiding an answered branch retains its data and flags it for review. Before signing, the clinician must resolve it: reactivate, explicitly retain as relevant, or mark superseded/not applicable with reason. Preserve the original in revision history. Do not silently print or silently delete contradictory hidden answers.
- Do not hide an unmet mandatory requirement behind a visibility rule. Publication checks should reject contradictory rules; runtime validation must reveal and focus remaining blocking fields.
- Draft saves allow clinically incomplete information but enforce type, bounded size, reference consistency and authorization. Invalid local input stays in memory with an error; it must not replace a valid server revision.
- Sign validation enforces the approved minimum dataset, unresolved hidden answers, source review requirements and required disposition/ownership. Care completion has its own requirements and can leave the note unsigned.
- Use shared JSON test vectors against TypeScript and Java implementations. Zod validates transport/application objects; it is not the only authority for clinical rules.
- Unknown schema/widget/rule versions produce an explicit unsupported-template state; never silently omit fields and allow signing. Existing signed documents remain accessible through their archived artifacts.

## 6. Encounter selection and creation

### 6.1 Selection rules

Selection context includes Practice, site where applicable, responsible clinician, care mode, encounter type and optional episode. Filter to currently authorized, published and effective releases with supported capabilities. Filter before applying favorites.

Suggested order: explicit eligible user choice; eligible clinician preference; eligible Practice default for the encounter type; otherwise ask for a choice. Never silently use a generic form when a required specialty capability is unavailable. Do not equate specialty metadata with credentials or permission.

Display template title, visit type, business version, clinical approval summary, required episode context and preview. The clinician confirms the visible selection as part of Start encounter. Revalidate at creation because a release may be retired between selection and submission. Do not silently substitute a newer release after a stale selection.

### 6.2 Queued care

1. Enter from the selected queue entry. Display patient identifiers, Practice/site, doctor, active versus previewed session, readiness, care time and selected template.
2. If already started, open the existing authorized encounter and its pinned release. A second click must not create another encounter or alter its template.
3. If starting in a previewed session, require explicit activation and overlap handling. Time passing or opening the selector does not activate it.
4. Submit client-generated encounter UUID, queue/session IDs, expected versions, release ID, optional episode ID, care mode, occurrence time and original idempotency key.
5. Authenticate and authorize, resolve the trusted tenant route, lock/revalidate queue and session, validate current eligibility and active-care policy, validate patient/episode agreement, and invoke the encounter module.
6. In one **tenant database transaction**, create/retrieve the encounter, pin release/checksum, capture handoff sources, link the queue entry, transition it to `IN_CONSULTATION`, and persist audit/idempotency/durable event records.
7. Navigate after server confirmation. A timeout retries the same command identity; a different key racing for the same queue entry still cannot create a second active encounter.

Canonical readiness and permitted `CALLED` skips remain the queue module's rules. Do not invent new queue states for template selection.

### 6.3 Non-queued care

Enter from the patient chart's New encounter action. Collect care mode, actual care time, responsible clinician, applicable Practice/site, visit type and template. Reuse/select an episode when applicable; do not create an appointment or check-in. Persist optional session/queue links as null.

For the first vertical slice, create/select the pregnancy episode through an explicit idempotent command before encounter creation. If encounter creation fails, the episode remains a valid independently recorded resource; do not delete clinical history automatically. A prenatal initial visit can reference an existing pregnancy episode. An externally managed pregnancy may be recorded with uncertain details and provenance.

### 6.4 Active-care concurrency across Practices

Tenant-local unique constraints cannot enforce a doctor's activity across separate databases. Before enabling cross-Practice starts, implement and review a minimal control-plane active-care reservation protocol with fencing, request identity and recovery, separate from the browser session. It must prevent two Practices winning concurrent starts, carry only the minimum operational references, and reconcile a crash between reservation and tenant commit.

Reservation timeout must not declare that care ended. An uncertain reservation blocks/reconciles subsequent starts until the owning tenant confirms its outcome; release a reservation after verified failure or explicit care completion. Do not claim a distributed transaction exists. Test crashes before/after both writes. This is required foundation work for the multi-Practice invariant, not a property supplied by an encounter-table index.

### 6.5 After creation

Patient, Practice, site/care context and template release are locked. The template chip opens release details. Do not offer ordinary in-place template replacement in the first release. A later pre-signing migration requires explicit field mapping, review of unmapped answers, clinical validation, reason and retained original revision. Signed notes always use amendments; never retarget them to another patient or release.

## 7. Persistence, module boundaries and APIs

### 7.1 Proposed tenant records

| Owner | Records and integrity |
| --- | --- |
| `patientregistry` | Persisted patient identity and practice-scoped identifiers; provisional identity and duplicate-review foundations |
| `clinicalchart` | Pregnancy episodes/dating/history, observations and source versions, longitudinal facts and correction links |
| `encountertemplates` | Template catalog/drafts/releases/approval, encounter, form revisions, visit diagnoses, handoff snapshot, sign preparation, signed-note metadata and amendment links |
| `schedulingqueue` | Session/queue state, active links and transactional start/complete orchestration |
| `carecoordination` | Owned unsigned-note work, referrals, due follow-up and escalation; durable consumer deduplication |
| `documentscommunication` | Private staged object access, integrity verification and archive/release APIs; no independent rewriting of signed note content |
| `platformoperations` | Narrow audit/event/job facilities according to established module boundaries; no patient payloads in ordinary logs |

Every encounter has practice/patient UUIDs, responsible clinician, care mode, optional site/session/queue/episode IDs, exact release ID/checksum, care state, documentation state, optimistic version, and separate care/recording/signature times. Display numbers are separate from UUIDs.

Every accepted form save appends an immutable `EncounterFormResponse` revision with release ID and authored/received metadata. The encounter points to the latest revision. Use bounded payloads and serialized autosave rather than storing a row per keystroke. Revision retention/compaction policy must preserve clinical corrections and all signed inputs.

Enforce Practice/patient-consistent foreign keys, release-template consistency and unique command identities. Use a partial unique index for at most one active encounter per queue entry and locking for transition races; paused care remains active for this invariant. Add same-tenant clinician active-care constraints where policy requires them. PostgreSQL supports partial unique indexes; cross-row/table rules require appropriate constraints or transaction logic rather than cross-table `CHECK` expressions. [PostgreSQL 17 constraints](https://www.postgresql.org/docs/17/ddl-constraints.html).

### 7.2 Dependency direction

Proposed primary direction: `schedulingqueue → encountertemplates → clinicalchart → patientregistry`, with explicit identity/tenancy and platform interfaces as required. `encountertemplates` may call a narrow documents storage interface. Keep `clinicalchart` independent of encounter repositories; provenance uses IDs and service contracts. Keep document storage independent of encounter internals.

`carecoordination` consumes durable encounter/queue events and owns its worklists; producers must not depend back on its internals. An unsigned-note obligation and owner exist in the authoritative encounter transaction even before its inbox projection catches up. Persist an explicit owned follow-up intent with the encounter event; the consumer creates the task idempotently and reports pending/failed delivery. Never show a task as created before acknowledgment. Signing does not close unrelated tasks.

Queue completion orchestration can synchronously inspect encounter care state through the encounter API; the encounter module must not call queue repositories back. Care finish and queue completion remain distinct commands. Staff/session closure workflows must reconcile their difference explicitly.

Use same-tenant synchronous module calls for critical invariants. Use the durable PostgreSQL publication registry for retryable projections and side effects, with idempotent consumers and failed-publication monitoring. Verify registry placement and transaction participation with the tenant routing implementation; the current control-plane datasource configuration is not sufficient evidence. [Spring Modulith event publication](https://docs.spring.io/spring-modulith/reference/events.html).

### 7.3 Proposed endpoints

All paths below are relative to `/api/v1/practices/{practiceId}` and are **new contracts to implement**. Independently authorize path Practice and all referenced records.

| Method and path | Purpose |
| --- | --- |
| `GET /encounter-templates` | Filter eligible releases by site, clinician context, specialty and encounter type |
| `GET /encounter-template-releases/{releaseId}` | Authorized immutable definition/details; historical references remain resolvable |
| `POST /encounter-template-drafts` | Create/clone governed definition draft |
| `PUT /encounter-template-drafts/{draftId}` | Save with expected draft version; invalidate prior approval |
| `POST /encounter-template-drafts/{draftId}/validation` | Structural/rule/content test report and preview inputs |
| `POST /encounter-template-drafts/{draftId}/approval` | Authorized clinical approval bound to exact checksum |
| `POST /encounter-template-drafts/{draftId}/publication` | Publish the approved immutable release |
| `POST /encounter-template-releases/{releaseId}/retirement` | Change future availability with version and reason |
| `GET /patients/{patientId}/pregnancy-episodes` | Authorized episode list with source/review status |
| `POST /patients/{patientId}/pregnancy-episodes` | Explicit idempotent episode creation |
| `POST /patients/{patientId}/pregnancy-episodes/{episodeId}/dating-assessments` | Append dating evidence; selection is a separately guarded versioned decision |
| `POST /patients/{patientId}/pregnancy-episodes/{episodeId}/selected-dating-assessment` | Select evidence with reason and expected episode version |
| `POST /sessions/{sessionId}/queue-entries/{queueEntryId}/start-consultation` | Atomic queue/encounter handoff owned by scheduling/queue |
| `POST /patients/{patientId}/encounters` | Authorized non-queued creation |
| `GET /patients/{patientId}/encounters/{encounterId}` | Context, versions, pinned release, latest response and authorized references |
| `PUT /patients/{patientId}/encounters/{encounterId}/draft` | Accept a new draft revision under expected version |
| `POST /patients/{patientId}/encounters/{encounterId}/care-transitions` | Pause/resume/finish under role/state/version guards |
| `POST /patients/{patientId}/encounters/{encounterId}/sign-preparations` | Validate/freeze exact candidate and return review status/artifact reference |
| `POST /patients/{patientId}/encounters/{encounterId}/signatures` | Deliberate authorized finalization of that candidate |
| `POST /patients/{patientId}/encounters/{encounterId}/amendments` | New linked correction draft, retaining original |
| `GET /patients/{patientId}/encounters/{encounterId}/activity` | Authorized audit and revision history |

Generated OpenAPI TypeScript contracts should be introduced with these endpoints. Reuse current same-origin session/CSRF behavior; do not introduce browser bearer-token storage. Expected version is an explicit request field consistently across commands; avoid competing version conventions without a reason.

Mutations use UUID `Idempotency-Key` values. Persist key scope, actor, operation, canonical request hash and outcome in the affected tenant transaction. Same key and identical request returns the authorized prior outcome; changed payload with the same key returns a conflict. Reauthorize even on replay. Define retention sufficient for supported retry windows and keep encounter uniqueness independent of key retention.

Return structured errors with stable code, field/group path where relevant, correlation ID and current permitted version information: `401` expired authentication; `403` denied action; privacy-safe `404` unavailable record; `409` stale version/duplicate-active/key mismatch; `422` valid JSON with domain validation failure; `503` unavailable tenant/service. Preserve form contents and distinguish these states. Never return another tenant's details to explain a conflict.

Field-restricted contributors receive only authorized values and permitted metadata. A draft request must declare the fields/group items being changed; the server applies those changes to the current authorized revision and preserves values the contributor cannot read or edit. The proposed `PUT /draft` is a versioned draft command, not permission to replace hidden server content with an incomplete client object. Explicit clearing differs from omission. Reject unauthorized field mutations and authorization-dependent rule inference that would reveal restricted answers. Test a staff contribution to a draft containing clinician-only reproductive information.

For signed documents containing restricted content, authorize access to the complete artifact or use a separately governed redacted release derivative. Do not send the original PDF to the browser and rely on hiding its restricted fields in the UI; do not present a redacted derivative as the unchanged original signed artifact.

### 7.4 Extracting facts without two sources of truth

At accepted draft save, keep narrative/form responses provisional. Existing confirmed chart references remain references. At an explicit clinical confirmation or signing boundary, invoke owning services to create/correct typed facts using an idempotent source key: encounter, response revision, field UUID, group-item UUID and extraction mapping version.

Vitals collected before the encounter are already authored observations; the note references their exact version rather than “creating” them again. For newly entered observations, define whether Save observation independently commits them or signing confirms them; label that boundary consistently. Do not let a background JSON extractor silently overwrite a reviewed chart fact.

Store extraction links and correction/supersession records. Keep units, method, body site/laterality, time, source and status. A changed answer creates a new version or correction, not an in-place historical overwrite. Template updates must not re-extract old signed responses using new mappings.

## 8. Draft saving, concurrency and lifecycle

Care: `IN_PROGRESS → PAUSED → IN_PROGRESS → FINISHED`, also direct finish. Cancellation/entered-in-error requires controlled reasons and dependent-record review; do not cancel away performed care.

Documentation: `DRAFT → READY_TO_SIGN → SIGNED`; an amendment is a new linked draft/signed revision. “Amended” is a historical relationship/status display, not permission to edit the original. Editing a ready candidate invalidates sign preparation and returns to draft. Signing is not care completion, queue completion, result review or follow-up closure.

Autosave behavior:

1. Debounce valid changed data; serialize writes per encounter. Choose the interval after workflow testing rather than treating a proposed interval as an SLA.
2. Retain the last acknowledged revision and the exact in-flight payload/key/version. New keystrokes belong to a later revision.
3. Show Unsaved → Saving → Saved to server, with server time/version; separately show Save failed, Disconnected, or Conflict. A response for an older payload must not mark newer text saved.
4. A timeout retries the same frozen payload and key. Reconcile before sending the next queued edit. Do not relabel changed content as a retry.
5. On conflict, pause autosave, retain local values, fetch authorized current data, and show source/local/server differences. The clinician resolves conflicts into a new versioned save; no silent clinical merge or last-write-wins.
6. On navigation, offer save-and-leave when possible, remain, or deliberate discard of unsaved local changes. Do not block the next patient because the previous note is unsigned once the draft is safely saved and active-care policy permits it.
7. On logout/scope revocation, deny further clinical operations and clear inaccessible cached records. Preserve transient edits only within the still-authorized form context; privacy restrictions override cross-account recovery. Never export/store patient text automatically to rescue it.
8. Keep local edits in memory only. No localStorage, IndexedDB, service-worker response caches or persistent Query cache for clinical data in this release.

Use TanStack Query for server state with Practice/patient/encounter keys. Use React Hook Form for form state and narrow subscriptions for conditional groups. Do not mirror every answer in Zustand. Realtime events invalidate/reconcile server references; they must not replace dirty inputs in place.

## 9. Signing, documents and amendments

Signing is a staged server workflow, not a browser PDF button:

1. Flush/acknowledge the current draft and obtain its exact revision. Validate the pinned release, clinical requirements, author/signer rights, source references and unresolved conflicts.
2. Create a sign preparation bound to patient, Practice, response revision, release checksum, renderer/terminology/extraction versions and candidate hash. Render with fixed dependencies and no arbitrary external resources.
3. Stage candidate artifacts in private storage and verify exact bytes, checksum, object version and durable availability. Surface pending/failure accurately. A failed render/upload leaves an owned unsigned note.
4. Show the exact patient/content review candidate and clear authorship. Capture deliberate confirmation and the configured signer assurance/re-authentication evidence. Define cryptographic signing and any embedded signature rendering explicitly before enabling issuance; a signature image is not an authority model.
5. Finalize under a version check and idempotency key. Reauthorize and bind the final signed bytes/hash and signer evidence to the same reviewed content. If embedding a signature changes bytes, retain both reviewed-content binding and final artifact hash; never imply the preview hash equals a different final PDF hash.
6. Mark signed only when final verified artifact references and immutable signature metadata are persisted. If external signing/storage is asynchronous, return a pending operation and reconcile it; do not show success early.
7. Failed database finalization after object creation leaves a recoverable staged object; failed storage does not create a falsely archived note. Use retryable jobs and retention-aware orphan cleanup. Database and S3 are not one transaction.

Display signed notes primarily from the original archived artifact. A missing artifact is an explicit integrity/unavailability state, not an invitation to rerender from today's template. Store the exact structured revision and extracted source links for authorized review.

Amendments capture reason, actual author, new signature time and original document/revision linkage. Preserve original bytes and evidence. Wrong-patient correction cannot simply change the note's patient ID; require controlled entered-in-error handling and review of dependent orders, prescriptions and disclosures.

Separate artifact mechanics from real-patient release approval of the signature method and clinical outputs. A synthetic integration test proves mechanics, not regulatory acceptance.

## 10. Clinician workspace and template administration UX

### 10.1 Encounter start

Use a compact, accessible start flow showing patient/Practice/site/doctor, care mode/time, specialty, encounter type, episode selection and template release preview. Keep the patient's chart available for review without starting care. Avoid a long wizard when all required context is already known.

For prenatal follow-up, make the selected pregnancy and dating-source summary visible before creation. If data are uncertain, show uncertainty; do not guess. Surface an existing encounter with Resume as the primary action when appropriate.

### 10.2 Editor

- Persistent two-identifier patient banner, Practice/site, care mode/time, responsible clinician, independent care/note badges and critical alerts.
- Release details chip rather than a mutable template dropdown after creation.
- Section navigation with readable labels and unresolved-field counts; the count is documentation progress, not a clinical safety score.
- Common sections: reason/history, relevant examination/observations, assessment/visit diagnoses, plan/counselling, disposition and follow-up. Specialty sections slot into this structure.
- Pregnancy summary shows episode identity, dated source, review status and links to history. Current facts and encounter-time snapshots must be clearly distinguished.
- Reference panels show existing observations, allergies, medication reconciliation, prior notes/results and owned work. Critical alerts remain visible on small screens and when sections collapse.
- Carry-forward is explicit per eligible field/section with visible source/time and review requirement. Do not copy examination findings as if assessed today. Do not transform historical problems into today's diagnoses.
- Save draft, Finish care and Review & sign remain separate actions. Pending prescription/order drafts use their own modules and issuance controls.
- The sign review opens missing sections and focuses the first blocking error. Print/preview includes only approved content with explicit missing-state wording, authorship and provenance where needed.

### 10.3 Responsive and accessible behavior

| Layout | Required behavior |
| --- | --- |
| Desktop, 1440 × 1000 | Existing sidebar/sticky top bar; main editor with secondary references; accessible section navigation |
| Portrait tablet, 834 × 1194 | Existing navigation rail; fewer columns; references accessible without hiding alerts; usable repeated groups |
| Mobile, 390 × 844 | Single-column form; compact section selector; identity/alerts remain accessible; actions clear of bottom navigation and keyboard |

Use Dashboard tokens, Roboto, Lucide and existing shared primitives. Keep meaningful labels, units, source/review states, visible focus, keyboard operation and announced save/errors. Restore focus after dialogs and repeats; label repeated controls by item context. Test zoom, long names, long narrative, many prior-history rows and validation errors in collapsed sections.

### 10.4 Authoring before a visual builder

Initially, keep reviewed definitions in version-controlled JSON and offer governed catalog/preview/validation/approval/publication through services and a minimal authorized screen or CLI. Do not expose raw JSON editing to ordinary clinicians as their normal consultation workflow.

Later, build section/field arrangement, option editors, rule configuration, diff, synthetic preview, approval and publishing on the exact same compiler and APIs. The visual builder cannot bypass any release checks. Tenant template data never inject CSS, HTML or executable components into the app.

## 11. Delivery sequence and concrete work packages

Implement sequential, reviewable work packages. Each has a usable outcome and explicit exit evidence. All are proposed; no completion is implied by this document.

| Package | Deliverable | Prerequisites | Exit evidence |
| --- | --- | --- | --- |
| A — Contracts and clinical brief | Agreed template/answer/rule schema, OB-GYN field dictionary, source/missing-state policy, module/API map, synthetic cases | This plan and pilot clinical owner | Approved dataset for clinical enablement; engineering fixtures clearly marked provisional; versioned schema examples validate |
| B — Clinical foundations | Trusted tenant runtime routing/pools, current scoped authorization, persisted patient identity/references, tenant audit/idempotency/events, schema migration path | Existing provisioning/authentication | Two-Practice API/database/pool isolation; revoked membership denied; patient/site mismatch rejected |
| C — Template registry/compiler | Draft/module composition, flattening, validation, clinical approval, publication, eligible catalog, immutable releases | A, B | Valid release usable; changed checksum invalidates approval; retirement blocks new use but preserves history |
| D — OB-GYN data and non-queued draft | Pregnancy/dating/history services; prenatal initial/follow-up selection; create/save/reload encounters with pinned release | B, C | Two visits reuse one episode; restart/reload preserves server-saved revisions; stale edits conflict; no queue fabricated |
| E — Dynamic editor | Registered widgets, repeated groups, conditions, explicit missing states, provenance, autosave, conflict recovery and responsive UI | C, D | Template-driven prenatal forms work on all three layouts, keyboard and failure paths; no per-template page fork |
| F — Queue integration | Persisted session/queue readiness, transactional handoff, concurrent-start guards, active-care reservation/recovery | B, D; actual queue services must be built | Same/different-key concurrent starts create one active encounter; crash tests pass; active/preview sessions remain separate |
| G — Signature and continuity | Sign preparation, verified artifacts, immutable signatures, amendments, owned unsigned notes/follow-up projection | D, E; private storage and approved signer adapter | Failure/retry/storage tests, historical bytes unchanged, later result/follow-up owner retained |
| H — Complete OB-GYN pack | Gynecology initial/follow-up and postpartum templates; clinician-approved output samples | C–G | Approved cases for each enabled service; uncertain external outcome and sensitive-access scenarios pass |
| I — Pilot hardening | Fleet rollout/rollback, restore, monitoring, approximately 50-user workload, full care-cycle validation | All applicable packages and broader core modules | Product §23 and technical release gates, independent tenant restore and downtime drill |
| J — Governed visual builder | Designer UI and later specialty packs | Proven registry/compiler and clinical governance | New approved form published without engineering edits; historical notes unchanged |

D and E together are the first connected OB-GYN demonstration. F and G are required before claiming full encounter workflow. The broader EPR's prescribing, results, referrals, documents and program requirements remain launch dependencies even if their full implementation is outside this feature.

### Suggested implementation locations

Preserve current chart/history routes while introducing feature ownership; do not move unrelated files simply for uniformity.

```text
web/src/features/encounters/
  EncounterStart.tsx
  EncounterWorkspace.tsx
  components/                    # renderer, section navigation, review, conflicts
  hooks/                         # scoped queries and serialized autosave
  services/                      # typed repository boundary, API and explicit demo adapters
web/src/features/encounter-templates/
  schema/                        # definitions and bounded rule contracts
  renderer/                      # reviewed field registry and rule evaluation
  catalog/                       # eligible selection and release details
web/src/features/obgyn/
  components/                    # pregnancy summary/history, dating review
  services/                      # pregnancy domain contracts
api/src/main/java/ph/epr/api/encountertemplates/
  domain/ application/ infrastructure/ web/
api/src/main/java/ph/epr/api/clinicalchart/
  domain/ application/ infrastructure/ web/  # includes OB-GYN clinical capability
api/src/main/resources/db/tenant-migration/
  <next available Flyway migrations>
```

These are proposed paths, not existing files. Inspect the current tree before choosing exact names or migration numbers. Add shared primitives only when the feature uses them. Use the existing React/Vite/TypeScript, React Hook Form/Zod, TanStack Query and Spring/PostgreSQL stack. Verify current official compatibility before adding a JSON Schema validator or changing runtime/library versions; do not upgrade unrelated dependencies as part of this feature.

### Rollout and migration

- Keep the existing synthetic historical fixtures behind an explicit demo adapter. Do not fabricate persisted signed content from their metadata or turn display IDs into production UUIDs.
- Introduce additive migrations and capability flags per Practice. Run canary tenant migrations, bounded fleet rollout and schema-status checks; fail closed for unsupported tenants.
- Seed development templates through an idempotent import with synthetic-only approval provenance. Production publication requires real clinical approval, not a seeder impersonating a clinician.
- Enable capabilities in order: registry → draft creation/editor → queue start → signing → approved pack. Rollback disables new usage and restores eligible prior releases while retaining historical readers and audit data.
- Do not auto-migrate in-progress notes to new releases. Preserve old renderer/schema support for open drafts and exact artifact access for signed records.

## 12. Verification and acceptance matrix

| Area | Required cases | Proof |
| --- | --- | --- |
| Definition | Duplicate field IDs, changed field meaning, unresolved references, cyclic rules, unsupported widget, contradictory required/hidden rules, excessive nesting | Compiler/domain tests and shared positive/negative JSON vectors |
| Publication | Approval invalidated by edit; support lacks clinical approval; simultaneous publications/default changes; retirement/rollback | JUnit and PostgreSQL transaction tests plus authorized UI flow |
| Selection | Wrong Practice/site, expired release, unavailable capability, stale favorite, retirement between preview/start | Service/API tests; visible conflict without silent replacement |
| Creation | Same-key replay, changed payload same key, different-key same queue race, wrong patient/session/episode, non-queued care | PostgreSQL Testcontainers with competing transactions and rollback assertions |
| Active care | Two Practices race; crash before/after reservation and tenant commit; paused encounter; finished care with unsigned note | Cross-database protocol tests; no double start or silent abandonment |
| Pregnancy | Two visits one episode; uncertain dates; multiple prior entries; duplicate episode retry; corrected dating; external incomplete postpartum source | Domain/API tests and clinician-reviewed synthetic walkthrough |
| Dynamic answers | Branch reveal/hide after entry, explicit missing states, group deletion/reordering, no default negatives, draft versus sign validation | Shared Java/TypeScript rule vectors and renderer interactions |
| Facts | Original units/times/source retained; draft versus confirmed; correction extraction; replay does not duplicate observations | Relational assertions, provenance links and transaction rollback tests |
| Save | Slow/out-of-order response, timeout replay, edit during request, conflict, refresh after save, disconnected input, unsaved navigation | Unit/integration tests and browser interaction assertions |
| Signing | Stale candidate, invalidated preparation, revoked signer, duplicate submission, render/upload/finalize failure, changed final bytes | Integration/failure-injection tests and immutable hash/record assertions |
| History | New template, terminology, dating basis or renderer; retired release; amendment; missing archived artifact | Prior signed bytes/hashes unchanged; explicit unavailability instead of reconstruction |
| Authorization | Secretary versus nurse versus clinician, support configuration without chart access, sensitive field manipulation, export/artifact denial | Backend authorization tests, not UI visibility alone |
| Context | Patient A/B and Practice A/B navigation with pending requests/modals; logout and revocation | No stale data, local draft, source sheet or action crosses context |
| Ownership | Finish care with unsigned note; session closes; clinician leaves; event replay/failure; follow-up remains unresolved | Durable owned obligation, deduplicated task, recovery and reassignment evidence |
| Layout | Desktop/tablet/mobile, long content, repeated rows, modal focus, keyboard, zoom, mobile keyboard | Screenshots inspected plus interactions and assertions at each viewport |
| Operations | Tenant migration failure, pool eviction/reuse, two-tenant restore, files/audit/keys, realistic load | Runbooks and recorded drills; not inferred from build success |

Minimum first-slice demonstration:

1. An authorized clinician opens a synthetic patient's chart without creating care.
2. They explicitly create/select a pregnancy episode and start a non-queued prenatal initial encounter with release 1.
3. They enter narrative, a typed value/reference and a repeatable history item; reveal and resolve a conditional branch; save and reload successfully.
4. Another editor produces a version conflict; local work remains available for explicit resolution.
5. A follow-up visit references the same episode, but has independent answers and no automatic normal findings.
6. Publishing release 2 leaves the first encounter pinned to release 1. Changing episode dating preserves the first note's saved source references; signed snapshot tests follow in package G.
7. A different Practice/user cannot access those records through manipulated requests.

### Actual commands and evidence rules

For implementation work, from repository root:

```sh
npm --prefix web run lint
npm --prefix web run test
npm --prefix web run build
```

For API implementation, run `./mvnw verify` with working directory `api/`, Java 25 and Docker/Testcontainers available. Do not substitute H2 for PostgreSQL behavior. Confirm commands in the current package/wrapper files before use.

The repository currently has no committed `test:e2e` script. Use the available browser workflow and temporary scripts/captures as required by `web/AGENTS.md`, or explicitly introduce/document an enduring suite as a separately included work item. Never claim a nonexistent script passed. Verify desktop 1440 × 1000, portrait tablet 834 × 1194 and mobile 390 × 844 with actual interactions, screenshots, console/error checks and failure states.

For this document-only task, check source paths, links, command consistency, JSON syntax and whitespace. No application build, clinical validation or rendered verification is implied.

## 13. Risks and decisions before enabling clinical use

| Decision | Recommended engineering default | Required owner/evidence |
| --- | --- | --- |
| First clinical forms | Prenatal initial/follow-up, then gynecology and postpartum | Pilot OB-GYN approves dataset and outputs |
| Clinical rule scope | Capture/reference only; no unapproved calculations or suggestions | Clinical owner approves each later rule/version and source |
| Dating | Store evidence and explicit selected basis; no automatic redating | OB-GYN approves method/source/uncertainty handling |
| Pregnancy episode rules | Explicit sourced status; preserve unresolved/conflicting history | Clinical owner finalizes lifecycle and correction cases |
| Restricted reproductive information | Server-enforced role/care/field controls; no automatic proxy disclosure | Practice privacy and clinical policy, including minors and representatives |
| Required fields | Drafts incomplete; sign requirements allow defined missing-state/exception handling | Clinical owner approves requirements by visit type and stage |
| Template upgrades | Disabled on existing encounters initially | Later migration design with mappings, audit and review |
| Clinical signature | Server-confirmed evidence and immutable archived bytes | Approved assurance method, output review and deployment validation |
| Cross-Practice active care | Fail closed on unresolved reservations; explicit recovery | Architecture review and cross-database crash tests |
| Philippine program participation | No eligibility/certification claims from generic fields | Deployment-specific current official requirements and approved workflow |

These decisions do not block engineering with synthetic fixtures. They do block claiming the corresponding clinical capability is approved or ready for real patients. Do not invent clinical values or weaken mandatory requirements to make a demo pass.

## 14. Completion criteria for this feature

The feature is complete for an enabled scope only when the authorized clinician can create/resume the appropriate encounter, select the correct approved template release, use dynamic sections, save/recover drafts, reference/correct episode information with provenance, and complete the applicable signing/amendment workflow without altering history.

For the full OB-GYN scope, all five OB-GYN encounter types must pass their approved scenarios; queue and non-queued entry must both work; chart facts and follow-up remain correctly owned; cross-Practice isolation and active-care guards pass; and the three layouts are verified. The later visual builder is explicitly outside this completion criterion.

Report milestone completion separately from complete-EPR or real-patient readiness. The broader product's required prescribing/results/communication, governance, privacy, restore and downtime gates still apply.

## 15. Copy-ready implementation prompt

Use the following prompt in this repository to begin implementation. It deliberately requests real foundations and a connected vertical slice before expanding the UI. For incremental delivery, set `DELIVERY_TARGET` to the next work package; the default below targets the full feature, not only a mock screen.

```text
Implement the dynamic patient encounter and specialty-template feature in this EPR
repository, with OB-GYN first.

DELIVERY_TARGET: Full encounter feature through work packages A–H in
docs/architecture/encounter-templates-obgyn-implementation-plan.md.
Work sequentially in reviewable milestones; do not call a draft-only demo the full
feature. Work package I contains real-patient release gates. The visual builder
(package J) is later work.

READ BEFORE EDITING
- AGENTS.md; web/AGENTS.md before frontend work; api/AGENTS.md before backend work.
- docs/architecture/encounter-templates-obgyn-implementation-plan.md in full.
- docs/architecture/session-and-client-authentication.md and
  docs/architecture/database-per-practice-tenancy.md.
- Relevant generated-assets product/technical plans when present, especially
  product sections 13.1–13.5, 13.9–13.11, 22–23.
- Current Encounter.tsx, PatientChart.tsx, PatientNotes.tsx, chart model/tests,
  App.tsx, current Dashboard and shared UI. Preserve unrelated uncommitted work.
- Applicable clinical, React, UI-design and frontend-testing skills for actual UI
  work. Use the current Dashboard as the visual authority on all three layouts.

OUTCOME
An authorized clinician can open a chart without creating care; explicitly start
a queued or non-queued encounter; select an approved release by specialty AND
encounter type; use dynamic structured/narrative sections; save/reload and recover
conflicts; finish care separately from signing; view exact signed artifacts; and
create linked amendments. Prenatal visits reuse a sourced pregnancy episode.

FIRST VERTICAL SLICE
Inspect the actual backend rather than assuming clinical services exist. Implement
the minimum trusted tenant routing, current clinical authorization, persisted
patient references, audit/idempotency/events and migration support required for
real synthetic-data persistence. Then deliver registry/publication, pregnancy
episode selection/creation, and a non-queued prenatal initial/follow-up encounter
that saves/reloads a pinned, versioned draft through the Spring API. Integrate the
persisted queue and signing/continuity next. Do not substitute success-only mocks.

TEMPLATE PACK
Use one renderer and reviewed definitions for:
- general.outpatient
- obgyn.gynecology.initial
- obgyn.gynecology.followup
- obgyn.prenatal.initial
- obgyn.prenatal.followup
- obgyn.postpartum.followup
Develop prenatal initial/follow-up first. All clinical fields are provisional
synthetic content until approved by the designated clinical owner. Do not invent
clinical approval, diagnostic thresholds, pregnancy dating rules, screening
schedules, normal findings, treatment regimens or dose calculations.

ENGINE REQUIREMENTS
Compose versioned common/specialty/encounter-type/Practice modules and flatten at
publication. Store immutable release UUID, schema version, checksum, rule,
terminology, renderer and extraction versions. Clinical approval binds the exact
checksum; edits invalidate approval. Retirement affects new selections only.
Use permanent field IDs, stable repeated-group item UUIDs, typed quantities/units,
explicit missing states, source/review metadata, bounded declarative conditions,
stage-specific validation and allowlisted extraction mappings. No eval, arbitrary
scripts, SQL, remote fetches, raw injected HTML or template-controlled permissions.
Reject unsupported versions and cyclic/conflicting rules. Preserve answered hidden
branches and require explicit resolution before signing. No silent clinical merge.
Build shared Java/TypeScript rule test vectors. Doctor preferences may reorder or
favorite eligible content but cannot remove required fields or alter clinical meaning.

OB-GYN DOMAIN
Separate patient history, pregnancy episode data and today's encounter answers.
Implement versioned pregnancy episodes, dating evidence and selected basis with
provenance, explicit correction reasons, prior obstetric-history item IDs and
uncertainty. Reuse episodes across visits. Preserve encounter-time dating/source
references and signed values when episode data later change. Multiple-gestation
support requires stable fetus identities; never infer pregnancy from demographic
sex/age or automatically create a newborn record. Avoid automatic redating or
calculators until their clinical rules are approved and tested.

BACKEND AND INTEGRITY
Use the selected Java/Spring/Modulith/PostgreSQL architecture and module-owned
repositories. Follow the plan's acyclic dependency map; no cross-module repository
access. Authorize every Practice/patient/site/episode/release operation using a
transport-neutral actor and current control-plane membership/grants. Keep HTTP
sessions limited to authentication/CSRF. Resolve tenant routes only server-side.
Use client-generatable UUIDs, optimistic versions and original idempotency keys
with request hashes. Creation retries and concurrent starts must not duplicate care.
Keep queued handoff/link/state/audit in one tenant transaction. Handle cross-Practice
active-care reservations with fencing and recovery; do not assume a unique index
spans databases. Do not auto-release uncertain care because a lease expired.
Keep reusable facts relational, with response/field/group provenance and corrections.
Keep support template administration separate from clinical approval and chart access.

FRONTEND AND WORKFLOW
Use React Hook Form/Zod and TanStack Query with scoped keys, generated OpenAPI
contracts and typed services. Keep temporary edits in memory; no persistent patient
browser cache. Serialize autosave; only acknowledge the exact saved revision;
retain in-flight payload/key for retries; preserve newer edits and conflict data.
Support loading, empty, failure, disconnected, denied, unsupported and stale states.
Keep patient identifiers, Practice/site, clinician, care mode/time and alerts visible.
After creation show a locked release-details control, not a silent template switcher.
Keep care finish, queue completion, note signing, orders, result review and follow-up
closure separate. Preserve independent unsigned-note and follow-up owners. Reuse
the Dashboard design and shared primitives; do not build a new page per specialty.

SIGNING AND HISTORY
Implement revision-bound sign preparation, deliberate clinician review, configured
signer evidence, verified private artifacts, idempotent finalization and recovery.
Do not report signed/archived before final evidence and bytes are confirmed. Handle
the non-atomic database/storage boundary. Preserve original structured revisions,
exact signed bytes/hashes, source versions, authors and signature times. Amendments
link to originals. Do not reconstruct missing demo signed content or retarget signed
notes. Keep unapproved signature methods disabled for real-patient issuance.

VERIFICATION
Add meaningful tests for template publication/pinning, conditional/hidden/repeated
answers, authorization/isolation, two visits sharing an episode, dating corrections,
duplicate retries/concurrent starts, save conflicts, archival failures, amendments
and owned work. Use PostgreSQL Testcontainers for database/transaction behavior.
Run the existing frontend lint/test/build commands and API ./mvnw verify from api/.
Verify rendered UI and interactions at desktop 1440x1000, portrait tablet 834x1194
and mobile 390x844, including keyboard/focus, errors, long content and switching
patient/Practice with in-flight work. Capture/inspect screenshots and check console
health. Do not claim a nonexistent e2e script ran or a build proves clinical readiness.

DELIVERY
Implement the selected target, update relevant documentation/contracts/instructions,
and report actual changed files, working flows, verification and remaining gates.
Keep moving through independent engineering work when clinical approval is pending;
use visibly synthetic fixtures and document the exact unresolved clinical decision.
Never invent external approval, production persistence, legal compliance or completed
tests. If a prerequisite blocks a dependent capability, identify it precisely and
complete the independent work rather than silently downgrading the target to a mock.
```

### Prompt for adding another specialty later

```text
Extend the existing encounter engine with SPECIALTY and ENCOUNTER_TYPES using the
approved clinical field dictionary and sample outputs supplied for that specialty.
Follow docs/architecture/encounter-templates-obgyn-implementation-plan.md and current
repository instructions. Reuse common modules, schema, compiler, renderer, catalog,
authorization, signing and revision services. Create new immutable specialty/type
releases; do not fork the workspace or modify existing releases. Identify any new
longitudinal entity or measurement that needs an owning domain service. Preserve
units, provenance, explicit missing states and source review. Add synthetic approval
fixtures only for development, then require actual clinical approval for publication
into clinical use. Verify old OB-GYN notes are unchanged and the new pack works on
desktop, portrait tablet and mobile with failure, access and historical tests.
```

## 16. Review limitations and document verification

This plan is based on inspected local source, accepted repository plans and the primary references linked near the relevant proposals. It does not certify clinical content, regulatory compliance, runtime security, or the current rendered UI. The full WHO clinical datasets were not imported or validated. Clinical workflows remain non-persistent in the reviewed implementation.

Document verification performed: all linked local source files exist in this checkout; the example JSON parses; frontend commands match `web/package.json`; the API verification command matches `api/AGENTS.md` and `api/README.md`; and whitespace checks passed. Only this Markdown plan was added for this task. Application builds, runtime tests and rendered browser checks were not run because application behavior was not changed.
