# Practice team and clinical access administration

Status: Roadmap. Practice-administrator self-service is now partially implemented; see [verified implementation and remaining gates](practice-team-access-implementation.md). This plan still includes unimplemented clinical offboarding and delegated support.  
Date: 2026-09-19

## 1. Outcome and scope

An authorized administrator can add or link a user to a Practice, assign a doctor role and explicit sites, review the effective access, and save the changes with an audit record. The doctor can then sign in with their own account and reach the connected encounter workflow without relying on the seeded demo doctor's credentials.

An existing Practice administrator who is also a doctor should normally keep one account with two separately assigned Practice roles. Creating another account is not the solution to missing permissions. Administration alone must never confer patient access.

Working product assumption, pending user preference: Practice administrators manage their own team; provider workforce users perform the same operations only through explicit, current delegated administration. Deliver self-service first, followed by delegated support. A superadmin's existing global provisioning authority is not an implicit grant to administer every Practice's clinical access.

The first delivery remains local and synthetic. It does not enable real-patient care, replace Cognito/MFA production gates, or import the old fixture patients into connected records.

### Included

- Discover administrable Practices independently of clinical sites.
- List/search/filter Practice members and inspect effective permissions.
- Add an existing account, create a new local account, or give an existing member a clinician role.
- Select existing sites, grant ordinary clinician access, and deliberately change scoped access.
- Account setup status, one-time local credentials for newly created accounts, and mandatory private-password replacement.
- Practice/site readiness and actionable explanations for blocked encounter access.
- Reasoned, versioned changes; audit history; safe suspension/reactivation and continuity controls.
- Explicit delegated support access with its own authority and audit trail.
- A minimal connected synthetic patient-registration handoff so a newly authorized doctor can actually create an encounter.

### Excluded from this delivery

- A general visual permission-policy editor; staff clinical screens and workflows.
- Full clinician credential verification, prescribing privileges, or legal signature assurance.
- Production invitations, automated email delivery, Cognito integration, or general account recovery.
- Full site creation/editing, scheduling administration, clinical reassignment UI, and bulk imports.
- Automatic patient matching, chart sharing, patient reassignment, or access to every patient in a Practice.
- Clinical approval of provisional template content. Technical readiness is not clinical readiness.

## 2. Verified starting point

| Existing implementation | Consequence for this work |
| --- | --- |
| `platform_user_account` has a single platform role: `SUPERADMIN`, `PROVIDER_SUPPORT`, `PRACTICE_STAFF`, or `CLINICIAN`. | Keep platform identity separate from Practice roles; a single global role must not determine whether a Practice administrator can also be a doctor. |
| `practice_membership` and `practice_role_assignment` already separate membership and multiple Practice roles. | Extend these records rather than adding a second membership model. |
| `practice_clinician_site_grant` includes enabled, reproductive-access and template-approver flags; `practice_clinical_capability` is explicitly synthetic. | Reuse the current authorization source, add safe commands and version/history support, and preserve capability gates. |
| `JdbcClinicalAuthorization` requires the global account role to equal `CLINICIAN`, in addition to Practice membership, role and site grants. | An administrator created as `PRACTICE_STAFF` still fails even if a clinician Practice role is added. Fix both `authorize` and `available` as part of the first milestone. |
| `JdbcPlatformUserDirectory.completeRequiredPasswordChange` looks for `practice_administrator_setup`. | Reusing its current password form for a newly created doctor would fail. Generalize account onboarding first. |
| `TenantAdministrationService` currently restricts global Practice lifecycle operations to superadmin. Provider detail pages have no member administration. | Add a separate Practice administration authorization boundary; do not broaden the existing global service into blanket clinical administration. |
| `PooledTenantTransactions` calls clinical authorization before routing. | A non-clinical administrator cannot use it to list sites. Add an administrative routing boundary without pretending the administrator is a clinician. |
| `JdbcClinicalPatientRegistry` requires an enabled `patient_care_assignment`; registration creates one for the actual registering clinician. | A new doctor will not automatically see the demo doctor's patients. Include a connected registration path and preserve patient authorization. |
| The template seeder publishes provisional releases only to its synthetic Practice/site. | A newly provisioned Practice is not automatically encounter-ready. Report missing releases; do not silently clone or approve them. |
| `EncounterContinuity` exposes clinician-context obligations; the current worklist supports same-clinician ownership. | It is not yet a complete administrative offboarding preflight or reassignment mechanism. |
| `/#/clinical?start=1` selects an authorized site and saved patient; preview charts are separate. | Connect account access diagnosis and administration navigation to this workflow without passing fixture IDs. |

Primary code anchors:

- [Clinical authorization](../../api/src/main/java/ph/epr/api/identitytenancy/internal/JdbcClinicalAuthorization.java)
- [Account setup and password changes](../../api/src/main/java/ph/epr/api/identitytenancy/internal/JdbcPlatformUserDirectory.java)
- [Clinical grant schema](../../api/src/main/resources/db/migration/V9__add_explicit_clinical_development_grants.sql)
- [Multi-role memberships](../../api/src/main/resources/db/migration/V5__separate_practice_memberships_and_role_assignments.sql)
- [Tenant routing](../../api/src/main/java/ph/epr/api/platformoperations/infrastructure/PooledTenantTransactions.java)
- [Patient care assignment checks](../../api/src/main/java/ph/epr/api/patientregistry/infrastructure/JdbcClinicalPatientRegistry.java)
- [Provider Practice detail](../../web/src/features/provider/PracticeDetail.tsx)
- [Connected encounter navigation](../../web/src/features/encounters/ConnectedClinical.tsx)

## 3. User experience

Use the current Dashboard's visual system and shared components: Roboto, Lucide, navy text, blue actions, restrained surfaces, sticky context, accessible forms, and responsive navigation. This is an operational screen; the primary job is to understand and change one person's scoped access.

### Navigation and identity

Clinical origin:

- `/#/practices`: server-authorized Practice list, available to administrators even when they have zero clinical sites.
- `/#/practices/:practiceId/team`: **Team & access**.
- `/#/practices/:practiceId/team/new`: **Add team member**.
- `/#/practices/:practiceId/team/:userId`: member details, access editor and history.

Provider origin, delivered with delegation:

- `/#/provider/practices/:practiceId/team`, plus member detail routes.
- A persistent **Support mode — {Practice}** indicator, actual operator identity, delegated permissions, expiry and service reason.
- Global Practice detail can offer an entry point; the server must independently authorize the delegated workspace.

Use authenticated identity in the shell. Do not show the fixture doctor or the generic “Authorized clinician” label for an administrator with no clinical scope. Role-aware landing should offer administration to a Practice administrator and clinical work to a clinician; an account with both can switch explicitly. These choices must not activate or change a clinical session.

### Team list

Header: Practice name, current administration mode, and **Add team member** when permitted.

Columns: name, account identifier, Practice roles, assigned sites, account-setup status, access status, and action. Search within the authorized Practice; filter by role, site and membership status. Use server pagination, initially 25 rows with a maximum request size of 100. Distinguish no members, no filter matches, loading and failed requests.

Show separate dimensions rather than a misleading single green badge:

- Account: setup required / ready / disabled.
- Practice membership: enabled / suspended.
- Clinical access: not assigned / enabled at selected sites / blocked, with reason.

Desktop uses a table; tablet preserves identifiers and readable controls; mobile uses labeled rows/cards with the same statuses and a full-page editor. No hover-only actions. Long names and many sites must wrap or expand deliberately.

### Add or edit a member

1. **Identity:** enter exact email/account identifier. Resolve it through a scoped, rate-limited command; no global user autocomplete. For an existing account, show only the minimum identity confirmation permitted to this administrator. Require explicit human confirmation before linking; never overwrite its name, password or memberships elsewhere. An identity discrepancy stops linking.
2. **Practice roles:** Doctor (`CLINICIAN`) and Practice administrator are independent. Existing nurse/secretary roles remain visible; a role label must not imply unimplemented staff features are available. The initial grantable role set is fixed by the server, not a free-form permission editor.
3. **Sites:** choose from real sites returned for this Practice. No default “all sites,” and future sites do not inherit access. At least one selected site is required to activate clinical access.
4. **Additional permissions:** reproductive-record access is explicit and off by default. Template-approver status is visible but not editable in the first slice; introducing its grant workflow requires a separately defined clinical-governance authority and approval evidence. Do not grant it to every doctor or administrator.
5. **Review:** show the actual account, Practice, roles, sites, additional permissions, reason, and resulting access limitations. Save one coherent access command. No optimistic success.
6. **Result:** existing account: “Access updated; use your existing sign-in.” New local account: display the temporary credential once and state that it must be delivered through the operator's approved channel; no email was sent. Do not retain it in query caches, local storage, audit payloads or ordinary logs.

An existing administrator may explicitly assign themself the Doctor Practice role when their current administration capability permits member clinical assignment. This must be a deliberate, reasoned and audited operation, never an implicit consequence of being an administrator. It is not proof of licensure or production credential verification.

### Access diagnosis and encounter handoff

Replace the generic “No enabled clinical sites” dead end with server-derived reasons appropriate to the viewer:

| Situation | Action |
| --- | --- |
| Own account requires setup | Complete password setup. |
| No clinical role/site grant | Administrators: **Manage my Practice access**; other users: explain that a Practice administrator must assign access. |
| Practice suspended, migrating, or clinical capability disabled | Explain the blocker; show administrative diagnostics only to authorized administrators. Do not expose tenant routes or credentials. |
| Membership/grants ready | **Open clinical workspace**; refetch scopes without requiring a new account or an arbitrary logout. |
| No assigned patients | Explain the distinction; offer **Register synthetic patient** to an authorized clinician. |
| No usable template release at this site | Explain that template publication/availability setup is required; do not claim encounter creation is ready. |
| Fetch or service failure | Retry; never present failure as absence of permissions. |

The member screen may report account/site readiness and template availability using privacy-minimized summaries. It must not enumerate patients, diagnoses, notes or clinical document content.

The minimal registration handoff uses the existing registration API and its duplicate-review response, with explicit synthetic-only labeling. It creates a care assignment for the signed-in clinician as the API already does. Existing-patient assignment remains a separate authorized clinical workflow; copying the seeded doctor's assignments is prohibited.

## 4. Authorization and data design

### Authority

| Actor | Permitted administration |
| --- | --- |
| Practice administrator | Team, ordinary clinical site access, member suspension/reactivation and audit within their currently enabled Practice membership and allowed capabilities. No automatic chart access. |
| Doctor without administration role | Own access summary and separately authorized clinical workflows. No member administration. |
| Delegated provider operator | Only actions named in an active Practice delegation, on the support origin, with a recorded purpose. Cannot make themself a tenant doctor or use clinical commands. |
| Superadmin without delegation | Existing platform provisioning/lifecycle operations. No implicit team/clinical-grant administration. |
| Nurse/secretary or unrelated user | No administration unless separately assigned a valid Practice administrator role. No inferred clinical access. |

Resolve authority on every server request, including list/search, identity linking, site metadata, audit, operation-status and retry requests. Hidden buttons and session role claims are not the authority. Preserve the separate browser origins, cookie/CSRF boundaries and transport-neutral actor model.

Protect the last enabled Practice administrator against ordinary removal, suspension or role downgrade, using transactional locking that covers concurrent administrators. Provider support cannot issue or widen its own delegation. Bootstrap an initial administrator through existing provisioning; exceptional recovery is a separately audited process, not an unguarded team-screen override.

### Control-plane changes

- Keep membership and Practice roles authoritative in `identitytenancy`.
- Add a version for the member-access aggregate; serialize writes to membership, roles and site grants under one lock/version. Include `expectedVersion` in updates and return a conflict with safe current state when stale.
- Add append-only access events and command receipts. Record actor, subject user, Practice/site scope, action, before/after permissions, reason, delegation reference, server time and outcome. No patient content or credential material.
- Scope command identity by Practice, actor, operation and idempotency key. Compare a canonical request fingerprint. Same key/same body replays the result; same key/different body conflicts. Reauthorize replay before returning information.
- Add generic account-onboarding records rather than fabricating `practice_administrator_setup` entries for every doctor. Separate account-global password readiness from Practice-specific membership setup. Migrate existing administrator setup compatibly and retain its history.
- Add delegated-administration records with Practice, named provider user, allowlisted actions, issuer, basis, start/expiry, status, version and revocation evidence. Begin with named users; provider groups can follow later.
- Apply `FORCE ROW LEVEL SECURITY` to new Practice-scoped control-plane records. Use transaction-local scope only after application authorization; never accept a privileged database context flag from the client.
- Use additive migrations numbered after the latest migration present at implementation time. Do not rewrite deployed V5/V9 migrations or auto-enable legacy accounts.

### Fix the global-role mismatch

Treat `PRACTICE_STAFF` and legacy `CLINICIAN` as eligible tenant account families. Determine doctor authority from the current enabled Practice `CLINICIAN` role, explicit site grant, Practice readiness, and subsequent patient/field checks. Exclude provider workforce accounts from tenant membership/grants.

Update both clinical authorization queries, account eligibility checks and affected tests. Do not change an existing user's global role merely because one Practice assigns or removes a clinical role; that can affect other Practices and existing sessions. Keep role payloads compatible until a separately planned account-family migration is needed.

### Account onboarding

Generalize temporary-password issuance and required-password replacement behind an identity-owned public service. Preserve password policy and CSRF behavior. Serialize account creation by canonical identifier and bind a new membership to the confirmed account UUID.

Practice administrators must not reset the password of an established account, including one shared with another Practice. Reissue is restricted to an unclaimed onboarding account, its authorized setup context and current version. Provider-workforce identities and disabled/conflicting accounts fail closed; the screen does not silently reactivate them.

Plaintext credentials are returned once on the initial successful operation, never stored in a replay receipt. If that response is lost, an authorized status query reports that setup was created; a deliberate reissue command rotates the unused credential with a new key and audit reason. The old credential stops working. After the user claims the account, recovery remains outside this local feature.

## 5. Module boundaries and cross-database work

Keep `identitytenancy` dependency-free with respect to clinical business modules. It owns account/membership/grant commands, administration authorization, control-plane audit and receipts.

Add a thin `practiceadministration` orchestration module for administrative workflows that require more than control-plane identity data. It may depend on public interfaces of `identitytenancy`, `platformoperations`, and the relevant clinical modules; those modules must not depend back on it. Add this explicit module to repository guidance when implemented and keep Modulith cycle checks passing.

- `platformoperations`: administrative tenant-route execution and shared bounded pool mechanics. Introduce distinct typed administrative and clinical contexts; an admin context must not be accepted by existing clinical commands.
- Site-owning services: read existing sites through an authorized metadata interface. Do not reuse a clinician identity, accept client database selectors, or make cross-module table writes.
- `patientregistry`: connected registration and any later care-assignment changes, through its public API.
- `encountertemplates`, `schedulingqueue`, `carecoordination`: minimal continuity/readiness interfaces. Administrative responses contain counts/status and routing to an authorized clinical owner, not chart detail.
- `practiceadministration`: coordinates these interfaces and durable operations; it does not own or mutate their tables.

Membership, roles, grants, their audit and the command receipt commit atomically in the control-plane database. Site verification and clinical readiness occur in the tenant database and are not part of that transaction. Persist pending operation state when verification cannot complete; do not report effective access before verification. Revalidate Practice route/schema readiness and site identity before activation. The first release assigns existing sites only; later site suspension/deletion must coordinate with this protocol. Clinical requests continue to validate the actual routed site independently.

Clinical capability, schema readiness and template publication remain distinct operations. Add a local-only, superadmin-controlled synthetic-capability operation with explicit reason, verified route/schema and idempotency; ordinary member administrators cannot bypass this gate. Expose its status in the team workspace. The general production capability remains disabled. For the first acceptance flow use a prepared synthetic Practice with published releases; fresh-Practice template setup remains an explicit dependency, not an automatic side effect of adding a doctor.

## 6. Suspension, revocation and unfinished clinical work

Do not implement member suspension as a single boolean toggle without examining care continuity.

- Normal offboarding preflight checks active/paused care, open sessions and unresolved queue entries, unsigned notes and follow-up ownership for the affected Practice/sites.
- Show only aggregate blockers to administrative users. Detailed disposition is performed by an appropriately authorized clinician.
- Block normal offboarding until work is resolved or explicitly handed over through owning-module services. While reassignment is unavailable, report that limitation and permit only supported disposition paths; never invent a transfer or auto-sign/finish care.
- Add a concurrency guard shared by new work allocation/start and offboarding. A completed preflight alone is insufficient: no new work may slip in between the check and revocation. Fence admission while draining/reconciling admitted work; store operation progress and retain it on crashes. Avoid claiming a distributed transaction across control-plane and tenant databases.
- Define the revocation boundary precisely: requests admitted after committed revocation fail. Already admitted requests require the fenced operation's reconciliation/drain policy; do not promise they can be retroactively cancelled. Test this race explicitly.
- Urgent security suspension is separate: deny further access immediately, preserve all original ownership/authorship and reservations, and durably assign a remediation case to a currently authorized clinical lead. Never retain unsafe access merely because work is unfinished. Do not ship this action until the escalation owner and durable case exist.
- Never hard-delete membership history, release active-care fences by timer, rewrite signed author identity, or automatically re-enable historical grants on reactivation. Restore only explicitly reviewed scope.
- On scope loss, cancel/remove affected client queries and block further actions. Preserve unsent form text in memory behind an access-loss state where appropriate; do not autosave it elsewhere, show newly fetched forbidden records, or add persistent clinical caches.

## 7. Proposed API contracts

These routes and contracts are planned, not currently available. Final DTOs should be exported through the existing OpenAPI/generated TypeScript workflow. All member routes sit beneath `/api/v1/practices/{practiceId}/administration`.

| Method and route | Purpose |
| --- | --- |
| `GET /api/v1/administration-scopes` | Current actor's administrable Practices/capabilities, independent of clinical scopes. |
| `GET .../members` | Practice-scoped search, filters, server pagination. |
| `GET .../members/{userId}` | Safe identity summary, membership, roles, sites, effective blockers, version, allowed actions. |
| `POST .../member-resolution` | Exact identifier lookup/link preparation with privacy-minimized results and identity confirmation. |
| `POST .../members` | Create/link member plus explicitly selected roles/sites; coherent idempotent command. |
| `PUT .../members/{userId}/access` | Replace the reviewed access set at `expectedVersion`; no arbitrary global-account editing. |
| `GET .../sites` | Authorized metadata for actual Practice sites. |
| `POST .../members/{userId}/offboarding-preflight` | Aggregate, expiring continuity assessment; authorization is rechecked at commit. |
| `POST .../members/{userId}/suspension` | Versioned, reasoned suspension through the fenced operation. |
| `POST .../members/{userId}/reactivation` | Explicit reviewed scope restoration. |
| `POST .../members/{userId}/temporary-password` | Restricted unclaimed-onboarding credential reissue. |
| `GET .../members/{userId}/events` | Paginated, redacted access history. |
| `GET .../operations/{operationId}` | Authorized operation status, partial-failure/retry guidance; no credential replay. |
| `GET /api/v1/my-practice-access` | Minimal own-account access status and actionable blockers. |
| `POST /api/v1/admin/practices/{practiceId}/synthetic-clinical-capability` | Local-only platform activation gate, separate from team administration. |

Delegation list/create/revoke routes also live beneath the Practice administration namespace, with distinct `manage_delegation` authority. Require `Idempotency-Key` for consequential commands, explicit request versions, bounded reason fields and server-generated allowed-action responses. Use 401 for expired authentication, 403/404 for denied scope without leaking existence, 409 for version/idempotency/continuity conflicts, 422 for field/combination errors, and 503 for unavailable routing/readiness. Never map service failures to “no access.”

Example access update: `expectedVersion`, `roles`, `siteGrants[{siteId, enabled, reproductiveAccess}]`, and `reason`. The server fixes Practice/user scope from authorized path parameters, validates site membership, excludes template-approver mutation in this slice, and rejects unsupported permissions.

## 8. Implementation sequence and acceptance

| Phase | Deliverable | Acceptance |
| --- | --- | --- |
| 1. Authorization foundation | Administration scopes/capabilities; member aggregate versions/audit/receipts; global-role mismatch fix; administrative site routing. | A non-clinical administrator can inspect their own team/sites and cannot read charts. Both tenant account families can receive Practice-scoped doctor access; provider accounts cannot. |
| 2. Existing-account workflow | Practice navigation, team list/member editor, explicit roles/sites, readiness diagnosis. | An existing administrator assigns themself or a confirmed existing member doctor access. The saved access survives reload and appears in clinical scopes without duplicate accounts or demo credentials. |
| 3. New-account onboarding | Generic onboarding model, create/link race handling, one-time local credentials, password replacement, setup status. | A doctor who is not the initial administrator completes password setup and uses their own account; existing accounts keep their credentials and other Practice memberships. |
| 4. Usable encounter handoff | Minimal connected synthetic registration and readiness guidance for patient/template dependencies. | Newly authorized doctor registers a synthetic patient, creates an encounter from a published release, reloads the saved record, and sees correct actual authorship. No manual SQL or fixture-ID mapping. |
| 5. Access lifecycle | Fenced offboarding preflight, supported suspension/reactivation, access-loss UI, audit history; urgent security path only with durable remediation ownership. | Concurrent new work cannot race ordinary offboarding; revocation blocks later requests and retains an accountable continuity path. Other Practice access remains intact. |
| 6. Delegated support | Grant issuance/revocation, support-mode routes, narrowly scoped operations and audit. | An individually identified support user can administer only the delegated Practice/actions, loses access on expiry/revocation, and cannot obtain chart/signer authority. |
| 7. Release verification | Contract export, migration evidence, integration/browser checks, documentation and rollback instructions. | All listed checks below pass with synthetic data; remaining production and governance gates remain explicit. |

The first reviewable increment is Phase 2. It resolves the current account-access problem without waiting for new-account creation. It is not the completed administration feature: phases 3–7 remain tracked, and lifecycle/support actions stay unavailable until their guards exist.

## 9. Verification plan

Backend JUnit/Testcontainers/Modulith coverage:

- Two Practices, at least two sites, two administrators and several user families; cross-Practice listing, lookup, writes, audit and operation-status denial.
- Administrator-only access never reaches patients, encounters, artifacts, templates requiring clinical authority, or clinical signing.
- `PRACTICE_STAFF` plus explicitly assigned clinician Practice role succeeds; missing role, membership, site grant, clinical capability, care assignment or schema readiness fails appropriately.
- Practice/site ID tampering; disabled account; required password change; suspended Practice; quarantined route; delegated-grant expiry/revocation.
- Existing account linking never resets identity/credentials or reveals unrelated memberships; provider-workforce identities cannot become tenant doctors.
- New clinician onboarding without an initial-administrator setup row; safe legacy administrator migration; mandatory password replacement; one-time credential behavior after timeout and deliberate reissue.
- Concurrent create/link, duplicate requests, changed payload with reused key, stale update, and concurrent last-administrator removal.
- Site verification failure, pending-operation recovery, pool/context reuse, FORCE RLS enforcement and absence of cross-module repositories/cycles.
- Concurrent clinical start/assignment versus offboarding, paused care, unsigned work, open sessions, immediate denial after revocation, preserved historical authorship and emergency-remediation ownership.
- Foreign-origin/session misuse and CSRF denial; authorization stays current across multiple application instances and existing sessions.

Frontend Vitest covers runtime DTO/schema validation, allowed-action presentation, practice-scoped query keys, aggregate conflict handling, exact timeout retries, transient credential cleanup, onboarding states and diagnosis-to-action mapping. Do not substitute visibility tests for backend authorization tests.

Browser flow: sign in as Practice administrator → Team & access → add/link or edit a doctor → review and save explicit site permissions → sign in as that doctor → complete setup if needed → register a synthetic patient → start encounter → reload persisted encounter. Separately exercise denied access, missing templates, failed saves, stale versions, unsaved navigation, membership revocation, and support delegation boundaries.

Required viewports: desktop 1440×1000, portrait tablet 834×1194, mobile 390×844. At each, verify real interactions, visible patient/Practice/account identity, keyboard/focus behavior, loading/error states, sticky navigation, no page overflow, and inspected screenshots. Check the Dashboard after shared shell changes. Use temporary browser evidence outside committed source unless a committed suite is separately introduced.

Commands from repository root when implemented:

```sh
./api/mvnw -f api/pom.xml verify
npm --prefix web run lint
npm --prefix web run test
npm --prefix web run build
node scripts/export-clinical-openapi.mjs
node scripts/generate-clinical-contracts.mjs
git diff --check
```

Extend the contract exporter/generator as needed to include new administration controllers and schemas; the present clinical export is not automatically evidence of administration coverage. Run Maven builds serially against the shared target directory. No implementation test suite or browser check is claimed by this planning document.

## 10. Rollout and completion criteria

Use an opt-in local administration feature flag, default off until the acceptance flow passes. Apply additive control-plane migrations, and any required tenant continuity migrations through the existing rollout mechanism. Update the required tenant schema version only when a tenant migration actually ships; never assume schema 5 is permanently sufficient.

Canary with a prepared synthetic Practice, then verify a second Practice for isolation. The seeder must not restore revoked memberships or grants on restart. Rollback disables administrative writes and preserves new audit, onboarding, credentials and grant records; do not erase changes or silently restore prior access. Document a forward-fix strategy for migrations.

Completion means a Practice administrator can configure an existing or new doctor, the doctor can start a persisted synthetic encounter using their own account, and revocation/continuity/isolation checks pass. The current preview charts remain explicitly separate. Real-patient rollout still requires production authentication, clinical authorization/content approval, validated outputs, storage/signing, restore/downtime and the broader pilot gates.

Planning references: [browser-session strategy](session-and-client-authentication.md), [database-per-Practice decision](database-per-practice-tenancy.md), [connected encounter implementation](encounter-templates-obgyn-implementation.md), root and scoped AGENTS instructions, product-plan sections 3–7, 9, 13 and 22–23, and the technical plan's online-first and delegated-administration boundaries. The tenancy decision's older “current implementation status” predates the connected slice; use the connected implementation and current source for runtime-pool and clinical-routing status.
