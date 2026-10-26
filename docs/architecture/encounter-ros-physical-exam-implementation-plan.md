# Review of systems and physical examination: implementation plan

Status: synthetic engineering implementation completed 2026-10-22; see [implementation evidence](encounter-ros-physical-exam-implementation.md) and the [pending clinical review packet](encounter-ros-physical-exam-clinical-review.md). The connected clinical slice remains synthetic only; clinical content and signing are not approved for real-patient use.

Later user direction on 2026-10-22 changed the quick-entry interaction after review of ROS and examination references: v1.2.0 adds clinician-selected, template-defined system findings with per-system custom text and keeps the narrative as an alternative. No finding is preselected. This supersedes the narrative-first and “no system prompt in the first release” UX choices below for new synthetic encounters; the safety and clinical-approval constraints remain.

## Decision and scope

Add **Review of systems (ROS)** and **Physical examination** to encounter documentation through new immutable template releases. Start with the general outpatient and five OB-GYN synthetic encounter types. Keep the common capture path concise; each encounter type can add clinically approved prompts. ROS is the patient's reported symptoms today. Examination is what the clinician actually observed or performed today. Vitals and other independently authored observations remain their own source records and may be referenced in the note; a typed exam statement does not create a confirmed chart observation.

The first release should support a concise narrative in each section, explicit assessment states, autosave, sign validation and exact signed rendering. Optional system-specific prompts should be shown only when the clinician chooses them or an approved encounter type calls for them. A comprehensive checkbox matrix, one-click “all normal,” automatic negative ROS, automatic normal examination, and copied-forward examination are excluded.

This follows the product plan's [minimum note and copy-forward rules](../../generated-assets/doctor-centric-epr-plan.md#135-encounter-and-documentation-lifecycle--core) and the [template/response/signed-artifact model](encounter-templates-obgyn-implementation-plan.md). The current [general outpatient fixture](../../api/src/main/resources/encounter-templates/synthetic/general.outpatient.json) has reason/history and assessment/plan but no ROS or exam. The gynecology fixtures already have a narrower `gynecology.examination` answer, so their new release must present that field alongside the common exam without duplicating its clinical meaning.

## Evidence informing the design

- [AHRQ's diagnostic-documentation review](https://www.ahrq.gov/diagnostic-safety/resources/issue-briefs/dxsafety-ehr-impact4.html) describes both the efficiency of templates and the need for free text to preserve the patient's story; rigid lists and copied text can impair note quality. Use a narrative-first, structured-when-useful editor.
- [AHRQ's EHR display research](https://digital.ahrq.gov/program-overview/research-stories/choosing-what-clinicians-see-electronic-health-record-can-reduce-cognitive-burden-and-improve) reports that forcing clinicians through multiple screens increases cognitive burden. Keep ROS, exam, chart references and sign progress in one encounter workspace.
- The [WHO antenatal Digital Adaptation Kit](https://www.who.int/publications/i/item/9789240020306) provides workflow and data-dictionary references for obstetric content. Use it as input to local clinician review, not as automatic approval of a Philippine template or a mandate for a generic full-system ROS.

## Clinical content contract

| Section | Initial fields | Optional detail | Recorded states |
| --- | --- | --- | --- |
| ROS | “Symptoms reviewed today” narrative, with pertinent positives and negatives in the clinician's words | Clinician-selected system-specific rows, if approved for that encounter type | Entered findings; not assessed; unknown; patient declined; not applicable where appropriate |
| Physical examination | “Findings assessed today” narrative | Clinician-selected body-system or specialty rows; relevant observed measurements through exact chart references | Entered findings; not performed/not assessed; declined; not applicable where appropriate |

The designated Practice clinical owner decides which prompts belong to each encounter type, which assessment states are valid, and which fields must be answered at signing. An initial safe rule is **one answer or an explicit assessment state for each of the two common sections at signing**; drafts remain incomplete and savable. This is a documentation requirement, not an instruction to perform an examination. The present `requiredAtSign` validator accepts either a value or an allowed missing state, which supports that rule. Do not treat “not assessed,” an empty field, and a normal finding as equivalent.

Use stable field UUIDs and concepts for new answers. Keep the gynecology-specific examination and its separate consent/chaperone context under the existing restricted reproductive sensitivity policy. Review each OB-GYN template for overlap before publication. Do not put history, a copied prior exam, or a vital-sign value into a new encounter field merely to fill it. If a later clinical need makes a measurement reusable, add an approved extraction/reference mapping through its owning chart service with provenance; do not extract arbitrary prose.

## Low-click encounter UX

1. **Show both sections in the existing editor sequence:** Reason and history → ROS → Examination and observations → Assessment and plan. Keep the patient identifiers, Practice/site, care mode, note state and save state visible. The new sections appear in the existing section navigation with an incomplete indicator only when an approved signing requirement is unmet.
2. **Put one labeled text area at the top of each section.** A clinician can click or tab into ROS, type, tab into exam, type, and rely on the existing debounced server autosave. The ordinary path requires no “add row,” modal, review dropdown or manual save click. Text areas expand for longer content and preserve draft text on failure.
3. **Keep optional detail behind a visible “Add system finding” action.** Present a short, encounter-specific list or search only after that action. Add a row adjacent to its narrative; support keyboard selection and focus. No default list of negative/normal answers. If a clinician repeatedly needs particular detail, measure usage before proposing approved template changes or display preferences.
4. **Make assessment states an inline alternative to typing.** Offer concise text buttons or a compact labeled control for “Not assessed,” “Patient declined,” “Unknown,” and “Not applicable” only where the release permits them. Choosing one explicitly clears the current unsaved value only after a clear warning when text already exists; prior server revisions remain. Never silently turn an untouched field into `NOT_ASSESSED` in the signed note.
5. **Remove redundant source-review clicks for fresh clinician entry.** When an authorized clinician creates or changes an answer directly in this encounter, the server should associate that action with the authenticated author and treat the newly entered answer as reviewed by that actor. A deliberate assessment-state choice can be reviewed the same way. Imported, copied, staff-entered and previously authored values still need an explicit clinician review; the signer's final candidate review remains mandatory. Design and test this narrowly rather than setting `REVIEWED` on every answer in the browser. The current renderer creates answers as `NOT_REVIEWED` and shows a per-field source-review select, so this behavior needs coordinated client/server work.
6. **Keep review and correction in the same context.** `Review & sign` shows the exact ROS and exam wording, assessment states, patient identity and source/author information in the candidate note. A missing or unreviewed answer links back to that field and focuses it. Signing remains a deliberate separate action, not an autosave side effect.

### Responsive behavior

| Layout | Plan |
| --- | --- |
| Desktop 1440 × 1000 | Use the current editor plus reference column; keep section links and sign progress available without a step-by-step wizard. |
| Portrait tablet 834 × 1194 | Reflow to one primary editor column, with references available close to the relevant section and patient identity retained. |
| Mobile 390 × 844 | Single-column sections; compact section navigation; no horizontal form scrolling; controls above the bottom navigation and visible when the software keyboard opens. Keep the patient banner and clinical alerts accessible. |

Use the Dashboard's blue/white visual system, existing controls, semantic labels, 44 px touch targets, visible focus and screen-reader announcements for save/error state. Do not hide critical alerts in collapsed ROS or exam sections. The interaction should be tested for keyboard-only entry, long narratives, zoom, slow connection and interrupted autosave.

## Implementation work packages

### 1. Approve the content before publication

Create a clinical approval packet for general outpatient and each OB-GYN encounter type: purpose, example completed/incomplete notes, relevant ROS and exam prompts, permitted missing states, requirements at signing, sensitive fields, note wording and test cases. Obtain an identified clinician's approval against the exact release checksum. Synthetic engineering approval is insufficient for real-patient use.

### 2. Publish additive template releases

- Add new ROS and exam sections/fields in version-controlled JSON, with new stable UUIDs, new business versions and new release UUIDs. Preserve existing field IDs and meaning. Do not overwrite published definitions or mutate encounters pinned to old releases.
- For the gynecology templates, retain `gynecology.examination` as a distinct specialty finding or move its display into the new exam section while preserving its field ID, sensitivity and stored answers. Do not show two indistinguishable “exam” boxes.
- Extend the [local synthetic pack seeder](../../api/src/main/java/ph/epr/api/encountertemplates/infrastructure/LocalSyntheticTemplatePack.java): it currently derives one release ID per template key and rejects a changed checksum. Introduce version-specific release identities and idempotent publishing; preserve the old release and explicitly retire/replace site availability for **new** encounters after validation. Existing drafts and signed artifacts retain their old release.
- Validate the definitions with `TemplateCompiler` and the published template JSON schema. Do not add a schema/widget version unless the approved interaction truly needs a new contract. The initial narrative fields and missing states fit the current contract.

### 3. Improve the generic editor, not one template-specific page

Build the compact ROS/exam treatment in the [dynamic renderer](../../web/src/features/encounter-templates/renderer/DynamicFields.tsx) and [encounter workspace](../../web/src/features/encounters/EncounterWorkspace.tsx), using field metadata or a reviewed concept-to-presentation mapping. Other templates must keep rendering correctly. Make source/review metadata easy to inspect while avoiding an extra click for fresh clinician-authored answers. Preserve the current serialized draft writer, idempotency keys, conflict resolution, navigation guard and scope-bound query keys.

If the first iteration needs only a better narrative layout, keep it presentational. Introduce structured per-system fields only after clinical content approval and usability testing show which systems earn their added interaction cost. Any new metadata in a template definition requires matching Java, JSON Schema, generated TypeScript and backward-compatibility handling.

### 4. Enforce attribution and signing rules on the server

Update answer review rules only after distinguishing direct clinician entry from copied/imported/staff entry using authenticated actor and saved revision evidence. The browser must not be able to assert another clinician's review. Continue to reject signing for absent required answers, unreviewed imported values, unresolved hidden answers or stale source references. Keep draft save permissive. Render ROS and exam from the exact response revision into the verified candidate/signed HTML; an explicit missing state must have accurate wording. Amendments create linked new revisions and leave originals unchanged.

### 5. Verify and roll out

- **Domain tests:** unchanged old-release rendering; new release selection; field-ID compatibility; required value-or-state at sign; missing versus normal semantics; clinician versus staff/imported review; duplicate/retry/concurrent draft saves; scope isolation; amendment/original immutability; exact archived bytes.
- **Frontend tests:** keyboard entry and inline states; no fabricated defaults; dirty/failed/conflict preservation; focus from sign error to ROS/exam; correct patient/practice after switching; no redundant review step for freshly authored findings.
- **Rendered checks:** lint/build plus real interactions and inspected screenshots at desktop 1440 × 1000, portrait tablet 834 × 1194 and mobile 390 × 844, as required by [web/AGENTS.md](../../web/AGENTS.md). Include long text, no-exam teleconsult, failure/retry and signed review. A passing build alone is insufficient.
- **Clinician usability sessions:** use synthetic cases for routine follow-up, complex multi-system symptoms, a declined exam and a teleconsult. Record total time, pointer/keyboard actions, correction rate, missed findings and note readability. Aim for the common path to be two field entries plus final review/sign, with no per-system or per-field acknowledgement clicks. Treat that as a design target to validate, not a claimed result. Revise prompts when clinicians omit relevant content or enter inaccurate boilerplate.
- **Rollout:** opt-in synthetic Practice/site first. Publish new releases and choose them only for new encounters after the old/new note outputs and historical access pass. Do not enable real-patient use until the repository's clinical, authorization, signing, privacy and pilot gates are met.

## Acceptance criteria

1. A clinician can document pertinent ROS and examination findings in the same encounter screen, with autosave confirmation and no mandatory per-field review click for directly authored content.
2. A clinician can explicitly record that a section was not assessed or was declined, and the signed note does not imply a normal examination or negative ROS.
3. The signer sees and confirms exact patient-specific content; missing, copied or unreviewed source content cannot be silently signed.
4. Existing encounters keep their original pinned template, revisions and signed bytes; newly created encounters select an approved new release.
5. The common path, edge cases and accessibility checks pass at desktop, portrait tablet and mobile sizes with synthetic data.
