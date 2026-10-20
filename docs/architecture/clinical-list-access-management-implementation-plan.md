# Clinical-list access management implementation plan

Status: Implemented 2026-10-08

## Implementation result

Control-plane V19 backfills the three Practice-level clinical-list source capabilities for already-enabled synthetic Practices without creating user grants. Future synthetic activation now enables those source capabilities atomically with the Practice clinical capability.

The identity/tenancy team contract now carries per-site, per-source action grants and a bounded readiness model. Practice-member create/update commands validate the five supported permission profiles, verified-site and clinician prerequisites, schema/capability readiness, optimistic versions, reasons, and idempotency; membership, roles, site grants, list grants, the member version, immutable before/after evidence, and the command receipt commit together. Any requested clinical-list reduction returns 409 and preserves the current access.

Practice administrators can configure No access, View, Contribute, Maintain, or Maintain and review for allergies, reported medications, and problems at each selected site. The directory summarizes effective access, the editor protects reductions, and history renders structured old/new evidence while remaining compatible with older snapshots. Provider Support can inspect schema, rollout, Practice capability, and source capability readiness and invoke the guarded local schema-upgrade and synthetic-enablement commands; it cannot assign individual doctor grants.

The OpenAPI artifact and generated TypeScript contracts include the new readiness and grant fields. The implementation remains local-profile, opt-in, synthetic-only. Clinical offboarding/reduction, delegated support, production identity/authorization, production fleet migration, and real-patient operation remain unavailable.

## 1. Purpose

Provide an auditable user interface and server-backed workflow for enabling longitudinal allergy, reported-medication, and problem-list access for a doctor at an explicitly authorized Practice site.

This plan extends the existing Practice Team administration slice. It does not make a Practice administrator a clinical user, make a doctor role sufficient for chart access, or enable real-patient operation.

## 2. Prior state and problem

The clinical-list backend already supports action-specific grants in the control-plane database:

- source kinds: `ALLERGY`, `MEDICATION_USE`, and `PROBLEM`;
- actions: `READ`, `PROPOSE`, `WRITE`, and `REVIEW`; and
- scope: one Practice, user, site, and source kind.

The connected patient chart consumed these grants and exposed only authorized actions. Before this implementation, the Practice Team UI managed membership, Practice roles, and clinical-site grants but did not read or write `practice_clinical_chart_grant` records. A custom doctor could therefore have the clinician role and a valid site assignment while the clinical lists remained unavailable.

There was a second readiness gap. Control-plane migration V18 backfilled source capabilities and grants for clinicians that were enabled at migration time, but a Practice enabled later could have `practice_clinical_capability.enabled = true` without the three corresponding `practice_clinical_chart_capability` rows. V19 and the updated activation command close that gap without granting any doctor access implicitly.

## 3. Decisions and boundaries

### 3.1 Administrative ownership

- Provider Support owns tenant schema rollout and Practice-level clinical capability activation.
- A current Practice administrator owns doctor membership, clinical-site assignment, and user-level clinical-list grants inside that Practice.
- Provider Support does not assign access to individual doctors.
- Practice administration does not itself grant patient-chart access.

### 3.2 Initial delivery boundary

The initial implementation remains local-profile, opt-in, and synthetic-only. Production identity, MFA, authorization governance, delegated support, real-patient operation, and production fleet migration remain gated.

The first release may grant access or increase existing access. It must not weaken the current fail-closed protection for clinical-access reduction. Reducing a clinical-list grant, removing a clinical site, removing the clinician role, or suspending an enabled clinician remains unavailable until the care-continuity offboarding workflow can inspect and disposition active care, unsigned documentation, results, follow-up, and other owned clinical work.

### 3.3 Permission profiles

The backend remains authoritative and stores individual action booleans. The UI presents a constrained profile per site and source to reduce configuration mistakes:

| Profile | Read | Propose | Write | Review |
| --- | --- | --- | --- | --- |
| No access | No | No | No | No |
| View | Yes | No | No | No |
| Contribute | Yes | Yes | No | No |
| Maintain | Yes | Yes | Yes | No |
| Maintain and review | Yes | Yes | Yes | Yes |

No action other than `READ` may be granted without `READ`. The server validates the action lattice rather than trusting the frontend mapping.

## 4. Target authorization flow

1. Provider Support verifies that the Practice is active and enabled.
2. Provider Support upgrades the tenant database to schema 15 when required.
3. Provider Support enables the Practice clinical capability and all three clinical-list source capabilities.
4. A Practice administrator assigns the member the `CLINICIAN` Practice role and one or more explicit clinical sites.
5. The Practice administrator assigns a permission profile for each source at each selected site and records a reason.
6. The server validates all prerequisites and saves membership, roles, site grants, and list grants atomically.
7. The doctor refreshes the chart. Effective permissions are resolved from current server state without storing them in the browser session.

## 5. Data and migration work

### 5.1 Control-plane migration

Add the next available control-plane migration after inspecting the migration inventory. It should:

- insert enabled `practice_clinical_chart_capability` rows for all three source kinds when the Practice clinical capability is already enabled;
- preserve existing user grants and historical evidence;
- add no implicit user grants; and
- remain additive and idempotent at the data level.

Do not edit V18 after it has been applied.

### 5.2 Future Practice activation

Update `JdbcSyntheticClinicalCapability.enable(...)` so successful synthetic clinical activation also creates or enables the three Practice-level source capabilities in the same control-plane transaction.

The activation event must continue to use optimistic Practice versioning, an original idempotency key, a required reason, and append-only administration evidence.

### 5.3 Grant evidence

Extend the existing Practice member access snapshot so it contains the effective clinical-list grants. `practice_member_event` can remain the append-only member-access history if its before/after JSON captures the complete grant state, actor, reason, and time.

If the existing event cannot represent future independent grant operations cleanly, add an append-only clinical-chart-grant event table owned by identity/tenancy. Do not rely on the mutable `reason` and `granted_at` columns in the current grant row as the only history.

## 6. Backend application changes

### 6.1 Public identity/tenancy contract

Extend `PracticeTeam` with records equivalent to:

```text
ClinicalListGrant:
  siteId
  sourceKind
  read
  propose
  write
  review
```

Add clinical-list grants to:

- `TeamMember`;
- `TeamCreate`;
- `TeamAccess`; and
- access-history snapshots.

Keep the persistence and authorization implementation inside identity/tenancy. The `practiceadministration` module may orchestrate the public interface but must not import repositories or mutate control-plane tables directly.

### 6.2 Readiness contract

Extend administration scope/detail responses with a bounded readiness state:

```text
READY
SCHEMA_UPGRADE_REQUIRED
CLINICAL_DISABLED
SOURCE_CAPABILITY_DISABLED
MIGRATION_IN_PROGRESS
QUARANTINED
```

Include the current schema version, required schema version, and source-capability availability. Do not make the frontend infer readiness from unrelated fields or error strings.

### 6.3 Atomic access command

Prefer extending the existing endpoint:

```text
PUT /api/v1/practices/{practiceId}/administration/members/{userId}/access
```

Create and update commands should save the following in one transaction:

- membership state;
- Practice roles;
- clinical-site grants;
- clinical-list grants;
- member access version;
- append-only access event; and
- idempotent command result.

This avoids a partially configured doctor whose site assignment succeeds but intended list access fails.

### 6.4 Server validation

Every grant command must require:

- a current, enabled Practice administrator as the actor;
- an active and enabled Practice route;
- tenant schema 15 with no non-ready rollout state;
- Practice clinical capability enabled;
- the corresponding source capability enabled;
- an enabled target account and Practice membership;
- the `CLINICIAN` Practice role;
- an enabled site grant for the same Practice and site;
- grant sites that are a subset of verified Practice sites;
- a valid action lattice;
- a non-blank reason of at most 500 characters;
- the current member access version; and
- an original idempotency key.

Authorization must continue to be enforced when reading or writing the patient chart. Frontend visibility never replaces `ClinicalChartAuthorization` checks.

### 6.5 Reduction guard

Compare the requested grants with the current grants. If any action changes from allowed to denied, return a specific 409 response until the clinical offboarding preflight is implemented. The response should explain that existing access was preserved.

Do not silently retain only part of the requested configuration.

## 7. Practice Team user experience

### 7.1 Team directory

Extend the Team & access directory with a compact Clinical lists column or status in the responsive member row:

- No access;
- View only;
- Contribute;
- Maintain;
- Maintain and review; or
- Mixed by site.

The status must include text and must not rely on color alone. Do not report a positive status when a schema, Practice capability, membership, role, or site prerequisite is missing.

### 7.2 Member editor

Add a **Longitudinal clinical lists** section after **Clinical site access** in the existing member editor.

For every selected clinical site, show three source rows:

- Allergies and adverse reactions;
- Reported medications; and
- Problems and diagnoses.

Each row receives a permission-profile selector. Include an **Apply to all three lists at this site** control for the usual doctor configuration, while keeping the resulting per-source settings visible.

The section must:

- default new assignments to No access;
- preserve entered values after validation or network failure;
- explain the difference between site access and list permissions;
- show why controls are disabled when Practice readiness is incomplete;
- prevent configuration for an unselected site;
- display protected existing permissions when reduction is unavailable; and
- remain fully usable by keyboard and screen reader.

### 7.3 Review and save

Extend the existing review summary with human-readable statements such as:

```text
Maintain and review allergies, reported medications, and problems at Makati Clinic
```

Use the existing required reason instead of adding a second confirmation dialog. The saved result should identify the member, sites, and effective list profiles.

### 7.4 Access history

Render structured before/after evidence rather than only a raw count:

- site name;
- source name;
- previous profile;
- next profile;
- actor;
- reason; and
- Philippine occurrence time.

Historical events that predate list-grant snapshots must continue to render safely as older evidence.

### 7.5 Responsive behavior

- Desktop: show site sections with three aligned source rows.
- Portrait tablet: retain the same information with narrower labels and wrapping controls.
- Mobile: stack each source name, current profile, and selector without hiding any source or permission state.

The interface should follow the current Dashboard and Practice Team visual system. It should not introduce a new settings shell, nested modal workflow, or permission table that requires horizontal scrolling on mobile.

## 8. Provider Support user experience

Add a **Clinical readiness** section to the existing Practice detail workspace showing:

- current and required tenant schema;
- rollout state;
- Practice clinical capability state;
- each clinical-list source capability; and
- the most recent rollout or activation failure when available.

Expose the existing support-only operations as guarded actions:

- **Upgrade to schema 15**; and
- **Enable synthetic clinical workflows**.

Each command requires a reason, expected Practice version, and idempotency key. Version conflicts reload the latest Practice state. Migration-in-progress and quarantined states remain explicit and fail closed.

Provider Support must not receive controls for assigning individual doctor grants.

## 9. Client contracts and failure handling

Update the generated OpenAPI contract and frontend Zod schemas for the new readiness and grant fields.

Retain the existing administration request guarantees:

- CSRF protection;
- `Cache-Control: no-store`;
- original-key retry after an uncertain outcome;
- double-submission prevention;
- optimistic version conflicts;
- preserved form contents on failure; and
- explicit handling for 401, 403, 409, 422, and server/network failures.

After a confirmed save, invalidate Practice Team and connected clinical-scope queries. A new login must not be required because clinical permissions are not stored in the HTTP session.

## 10. Verification plan

### 10.1 Backend tests

Add tests proving that:

- a doctor role and site grant do not implicitly create clinical-list access;
- an authorized Practice administrator can grant each supported profile;
- a non-administrator cannot grant access;
- a technical or global support role cannot manage an individual doctor's grants;
- disabled membership, account, site, Practice, or source capability fails closed;
- schema versions below 14 fail closed;
- cross-Practice and cross-site writes fail closed;
- invalid action combinations are rejected;
- create and update save role, site, and list access atomically;
- stale versions do not overwrite newer grants;
- duplicate and concurrent retries create one result and one audit event;
- RLS works using a non-owner control-plane role;
- permission reductions remain blocked and preserve existing access;
- member history is append-only; and
- future Practice activation creates all three source capabilities.

### 10.2 Frontend tests

Cover:

- response-schema validation;
- permission-profile mapping;
- role, site, and readiness validation;
- default No access behavior;
- disabled and protected-reduction states;
- long names and multiple sites;
- conflict reload;
- uncertain-result retry using the original request; and
- readable historical evidence from both old and new events.

### 10.3 Browser verification

Verify the complete path in desktop web, portrait tablet, and mobile layouts:

1. Provider Support upgrades and enables a synthetic Practice.
2. A Practice administrator grants a doctor site-scoped list access.
3. The doctor refreshes the connected chart.
4. Authorized Add, Correct, and Review controls appear for the granted sources.
5. An ungranted doctor receives an explicit unavailable or read-only state.
6. Cross-site access remains unavailable.
7. Access history shows the actor, reason, and exact before/after state.

The browser run must inspect console errors, keyboard interaction, focus management, loading states, permission failures, and responsive screenshots.

## 11. Delivery sequence

### Change set 1: readiness and contracts

- Add the capability backfill migration.
- Update future synthetic Practice activation.
- Extend Practice Team domain contracts and read models.
- Add backend validation and persistence tests.

### Change set 2: Practice administrator management

- Extend the atomic create/update command.
- Add list-grant audit evidence.
- Add the Team directory status and member-editor controls.
- Add frontend unit and browser coverage.

### Change set 3: Provider Support readiness

- Add the Practice clinical-readiness panel.
- Connect schema upgrade and synthetic enablement commands.
- Cover migration, quarantine, conflict, and retry states.

### Separate gated change set: safe reduction and offboarding

- Implement the clinical-work preflight across encounters, results, follow-up, and other owned obligations.
- Require reassignment or explicit disposition where needed.
- Revoke list grants, site grants, and clinician role atomically only after the preflight succeeds.
- Preserve append-only evidence and current work ownership.

## 12. Acceptance criteria

The initial grant-management slice is complete when:

- a schema-15, clinically enabled synthetic Practice exposes source readiness correctly;
- a Practice administrator can grant a doctor explicit per-site clinical-list profiles without SQL;
- the server records one atomic, versioned, idempotent, auditable access change;
- the doctor receives the new chart capabilities after refresh without reauthentication;
- ungranted actions continue to fail on the server;
- the UI distinguishes unavailable, read-only, writable, and review-authorized states;
- access reductions remain visibly protected rather than partially applied;
- all backend, frontend, and required three-layout browser checks pass; and
- documentation continues to state that real-patient operation and production authorization remain unavailable.

## 13. Verification evidence

Verified on 2026-10-08:

```sh
./api/mvnw -f api/pom.xml verify
npm --prefix web run lint
npm --prefix web run test
npm --prefix web run build
EPR_CONTRACT_BASE_URL=http://localhost:8083 node scripts/export-clinical-openapi.mjs
node scripts/generate-clinical-contracts.mjs
git diff --check
```

- Backend: 109 tests passed; Maven packaged successfully. The existing test-JVM shutdown warning appeared after assertions completed and did not fail the build.
- Frontend: 23 files and 103 tests passed; lint and production build passed.
- Browser: a real local API flow provisioned a synthetic Practice, completed administrator password setup, demonstrated readiness-disabled list controls, assigned the clinician and site, enabled all three source capabilities through Provider Support, granted Maintain and review for all sources, created a synthetic patient, and verified the authorized chart controls after refresh.
- Rendered checks passed at desktop 1440×1000, portrait tablet 834×1194, and mobile 390×844. Each layout covered Provider Support readiness, the Practice Team list-access editor/history, and connected-chart source switching; keyboard disclosure/focus, horizontal overflow, console output, and failed network responses were checked. Screenshots and the temporary Playwright script remained outside committed source.

These checks do not establish clinical validation, production readiness, regulatory certification, or authorization for real-patient use.
