# ROS and physical examination: clinical review packet

Status: **awaiting identified Practice clinical-owner review** (2026-10-22). The v1.2.0 releases are local synthetic engineering fixtures. Their template approval basis explicitly excludes real-patient issuance. This packet records proposed content and review questions; it is not a clinical approval.

## Proposed content for all six encounter types

| Item | Proposed behavior for clinical review |
| --- | --- |
| Review of systems | Required-at-sign **Symptoms reviewed today**. The new system-row template offers selectable symptom statements and per-system custom text, plus a whole-section narrative alternative. No phrases are preselected and no “all normal” action exists. |
| Physical examination | Required-at-sign **Findings assessed today**. The new system-row template offers selectable observed findings and per-system custom text, plus a whole-section narrative alternative. Selecting a phrase creates no chart observation or vital-sign value. |
| Assessment alternatives | ROS: not assessed, unknown, patient declined, not applicable. Examination: not assessed, patient declined, not applicable. An untouched field is distinct from every explicit state. |
| Draft/signing | Drafts may remain incomplete. Signing requires a value or permitted explicit state for each common field, reviewed source content, and the signer's deliberate review of exact candidate bytes. |
| System phrases | The exact proposed labels and phrases are in the six v1.2.0 JSON definitions under `api/src/main/resources/encounter-templates/synthetic/`. Review each phrase for clinical scope, whether it requires questioning or physical examination, wording, specialty fit, and possible contradictory combinations. Existing specialty prompts remain under their original field identities. |
| Provenance | Direct clinician entry is attributed to the authenticated saved-revision actor. Imported/patient-reported or unchanged unreviewed content needs explicit review. The candidate identifies the draft-revision actor and source description. |

| Synthetic encounter type | Purpose and overlap check |
| --- | --- |
| General outpatient | Use the two common narratives with reason/history and assessment/plan. No specialty prompt. |
| Prenatal initial | Keep pregnancy concerns, sourced episode/dating context and independently recorded weight separate from examination prose. |
| Prenatal follow-up | Keep interval pregnancy review and existing observations separate from examination prose. |
| Gynecology initial | Keep the existing restricted `gynecology.examination` field as **Gynecologic examination findings** in the examination section. Its field ID and sensitivity remain unchanged. Consent/chaperone context stays adjacent and restricted. |
| Gynecology follow-up | Apply the same specialty examination and consent handling to follow-up. Do not duplicate the specialty answer in the common narrative. |
| Postpartum follow-up | Keep postpartum-specific review separate from common ROS and examination; no assumed normal recovery or examination. |

## Example note states for review

- **Completed routine example:** ROS: “Reports intermittent cough; denies fever.” Examination: “Observed comfortable breathing while seated; no examination beyond observation performed.” These are illustrative synthetic words, not recommended clinical findings.
- **Declined examination:** ROS contains the patient's account. Examination is explicitly **Patient declined**. The candidate must not say “normal,” and the consent discussion belongs in its relevant specialty field where applicable.
- **Teleconsultation:** ROS contains the patient's account. Examination is explicitly **Not assessed** when none was performed; a video observation may be documented only if it actually occurred.
- **Incomplete draft:** Both common fields and every system row untouched. The draft can save; sign preparation must identify the missing field and the editor must focus it. Untouched rows must not appear in the candidate.
- **Imported content:** A previously authored or externally sourced answer remains unreviewed until a clinician explicitly reviews it. Editing an unrelated field must not review it.

## Approval record to complete before any non-synthetic use

For each of the six encounter types, the designated Practice clinical owner must record their identity, site scope, date, exact published release ID and server `checksum` shown in the pinned release details, accepted wording, permitted missing states, required-at-sign fields, sensitivity decision, and any requested corrections. Approval must be against that exact checksum; changing a definition requires a new release and review. The owner should inspect completed, incomplete, declined and teleconsultation candidate HTML, plus an old v1.0.0 signed artifact. Keep the signed decision and test evidence in the Practice's governed approval system. No identified clinician approval or usability-session result is recorded here.

Before a real-patient pilot, complete the broader authorization, signing, privacy, isolation, restore and clinician-output gates in the repository's encounter and platform plans. The local synthetic confirmation adapter is not a clinical signing authority.
