# Connected synthetic encounter vital signs

The connected encounter note now has a **Vital signs** section before the template fields. Clinicians can add measured values, review the values recorded for that encounter, and correct a set with a reason. Blood pressure requires both systolic and diastolic values; other measurements may be left unrecorded. Times are entered and displayed in Philippine time. The section is available only in the opt-in local synthetic clinical slice.

Tenant migration V17 stores an encounter-linked vital-set identity and immutable revisions. Each revision retains the measured time, original units, source or method, authenticated recorder, server receipt time, values, and correction reason. A correction appends a revision and advances the version with an optimistic conflict guard. Create and correct commands have idempotency receipts and audit events. They lock the encounter row shared with signing, so a correction and signature cannot interleave unnoticed. The API authorizes the exact encounter and site before every read or write. A set saved through this section is a confirmed source record independent of note autosave.

The encounter form records the fixed source `Clinic (manual)` for new entries and corrections, with no source field to fill out. The current reading appears once; an expandable history lists only earlier revisions. The current correction reason remains visible with its reading.

The exact note candidate includes the current encounter vital-set revisions and their provenance. Signature confirmation re-renders the candidate and rejects a change made after preparation. Verified signed bytes stay immutable when a later correction is recorded; a linked note amendment is required to change the signed narrative. Existing signed notes remain archived as originally rendered.

Routes under `/api/v1/practices/{practiceId}/patients/{patientId}/encounters/{encounterId}/vital-sets` support `GET` and `POST`; `POST /{vitalSetId}/corrections` appends a versioned correction. Commands require `siteId` and `Idempotency-Key`. Current clinical routing requires tenant schema 17.

Verification: `./api/mvnw -f api/pom.xml -Dtest=ConnectedEncounterTests,JdbcTenantDatabaseManagerTests test` covers migration and the vital create, replay, correction, conflict, candidate staleness and immutable signed bytes. The React lint, test and build scripts are listed in `web/AGENTS.md`. The frontend was also exercised with synthetic HTTP responses at the three required viewports; that browser check does not establish authorization or real-patient suitability.

Remaining gates: this slice has no nurse intake role, device integration, validated measurement ranges or clinical alert thresholds, longitudinal trend view, or real-patient approval. The clinician-only encounter authorization applies to the current endpoints. It does not claim a complete observation service for other workflows.
