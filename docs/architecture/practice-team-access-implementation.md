# Practice team management — local synthetic implementation

Implemented 2026-09-19. This delivers Practice-administrator self-service within the existing local identity adapter. It is not the whole [administration roadmap](practice-team-access-administration-plan.md) and does not enable real-patient operation.

## Available behavior

- `/practices` discovers currently administrable Practices independently of clinical scopes. `/practices/:practiceId/team` provides server-paginated search and role, site and membership filters. Full-page add/member editors work on desktop, portrait tablet and mobile.
- Each request resolves current enabled account, Practice membership, administrator role and Practice service state. Global provider authority supplies no team-management permission. Account identity and administration scope remain distinct from patient access.
- Add a new local account, or resolve an exact email and explicitly confirm the existing person. A shared existing account retains its name, password and other memberships. Disabled, unclaimed and provider-workforce accounts cannot be linked. Identity lookup is bounded to 20 attempts per minute per Practice/operator using PostgreSQL; an exhausted budget returns a neutral unavailable result.
- Assign multiple Practice roles and explicit existing clinical sites. Doctor access includes the existing patient-directory, detail, registration and demographic-edit capabilities; patient care assignments still apply. Reproductive access requires an explicit choice. Template approver status is preserved and cannot be assigned by this editor. Nurse/secretary roles are recorded; their clinical workflows remain unavailable.
- Assign explicit per-site profiles for allergies, reported medications and problems: No access, View, Contribute, Maintain, or Maintain and review. These action grants are separate from the doctor role and site access, require schema/capability readiness, save atomically with the member aggregate, appear in the team directory and structured history, and never arise implicitly from Practice clinical activation.
- The global `PRACTICE_STAFF` account family can use clinical workflows only with the same explicit Practice clinician role, site grants, capability, route/schema and patient checks as a legacy `CLINICIAN` account. Both `authorize` and scope discovery use this rule.
- New team accounts receive a one-time temporary credential and must replace it through the existing password form. Generic onboarding no longer requires an initial-administrator setup record. Existing initial-administrator setup remains compatible. Account setup completion has its own audit event.
- Command retries retain the original payload and key. Receipts never contain plaintext credentials; a replay confirms the prior outcome without returning the password. Only the originating Practice can reissue an unclaimed team account's credential. Reissue rotates the password, is versioned/audited and stops after the account is claimed. Established-account recovery is not provided.
- Role/site changes, non-clinical membership suspension and reviewed reactivation require a reason and expected version. A Practice-wide transaction advisory lock serializes team commands, including concurrent last-administrator changes. At least one enabled, setup-complete administrator must remain.
- Access events include actual actor, subject, Practice, reason, before/after membership snapshots and server time. PostgreSQL triggers reject event/receipt updates and deletes. New scoped tables use `FORCE ROW LEVEL SECURITY`. UI history is paginated.
- Forms preserve entries after rejection, distinguish stale versions from uncertain network outcomes, freeze an uncertain command for exact retry, guard unsaved navigation, and keep temporary passwords out of query caches/browser storage. Loss of a discovered administration scope cancels/removes its team queries.

## Deliberate unavailable operations

Clinical access reduction, removal of an enabled clinical site/reproductive grant, and suspension of a member with enabled clinical grants fail closed. A reservation-only check is insufficient: open sessions, paused care, unsigned notes and follow-up also need a fenced handoff. No partial continuity preflight, automatic transfer or emergency suspension is advertised. The full owning-module offboarding/reassignment workflow remains pending. Additive clinical access and administrative-role changes remain supported.

Delegated provider-support administration, delegation issuance, urgent security suspension/remediation, bulk imports, production invitations/email, general account recovery, production Cognito/MFA, and production clinical/signing authority are not implemented. Support cannot administer a tenant team through its existing global role. The team editor does not enable clinical capability or publish templates for a newly provisioned Practice. Provider Support now exposes bounded clinical readiness plus guarded schema-15 upgrade and synthetic-enablement actions; it does not assign individual doctor grants. A local `SUPERADMIN` can enable synthetic clinical access after the explicit schema-15 rollout and a clinician site grant; this creates the three source capabilities but no user grants, does not publish encounter templates, and does not approve real-patient use.

## Ownership and consistency

Control-plane migration V15 adds the membership aggregate version, team onboarding, append-only access evidence/receipts and lookup throttling. Control-plane V19 backfills all three Practice source capabilities for already-enabled synthetic Practices without creating user grants. This access-management extension introduces no tenant-schema change. The intervention extension introduced schema 11; the later [longitudinal clinical-list extension](clinical-lists-reconciliation-implementation-plan.md) added tenant schema 14; the prescribing catalog/refill extension requires schema 15 for current clinical routing.

`identitytenancy.PracticeTeam` owns authorization, account/membership/grant commands and control-plane persistence. The new closed `practiceadministration` module orchestrates these commands with `platformoperations.PracticeSiteDirectory`. The site directory reuses bounded tenant pools and verifies actual Practice/database identity, returning only existing site IDs/names, never an executable clinical context or patient content.

Site verification occurs before the control-plane command. The command reauthorizes after acquiring the Practice team lock and validates every requested site against verified metadata. Site deletion/relocation is not supported by this slice; future lifecycle changes must coordinate with this boundary. Clinical requests independently revalidate their actual tenant route and site. This does not claim a transaction across tenant and control-plane databases.

Audit/receipts commit atomically with membership and grant updates. Credential plaintext exists only in the first command response and transient UI memory. Replay reauthorizes the operator before returning the saved, versioned receipt. New account creation uses the same canonical-email advisory lock as initial administrator provisioning.

## Run and roll back

Use Java 25, the Maven wrapper, and PostgreSQL setup in [the API README](../../api/README.md). Team endpoints require the `local` profile and opt-in `epr.team.enabled=true` (`EPR_TEAM_ENABLED=true`). The flag defaults off. For an isolated local port:

```sh
./api/mvnw -f api/pom.xml spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.arguments='--epr.team.enabled=true --epr.clinical.enabled=true --epr.clinical.demo-seed=true --server.port=8083'
EPR_API_PROXY_TARGET=http://127.0.0.1:8083 npm --prefix web run dev -- --host localhost --port 5176
```

Open `http://localhost:5176/#/practices`. Existing administrator accounts work after password setup. The opt-in synthetic seed's doctor already holds Practice administration and provides prepared templates; seeding does not restore revoked grants. Ordinary team creation uses the UI and requires no SQL. No email is sent.

For the normal host, add `--epr.team.enabled=true` to the local API on port 8080; the existing proxy serves `epr.test`. Use `support.epr.test` only for the existing provider provisioning/lifecycle functions.

For **My Patients** on that host, also set `EPR_CLINICAL_ENABLED=true`. A doctor role and site grant alone do not activate a newly provisioned Practice. The support operator first calls `POST /api/v1/admin/practices/{practiceId}/schema-upgrade` with a fresh `Idempotency-Key`, the current Practice `expectedVersion`, and a reason. After the schema-15 command reports `READY`, call `POST /api/v1/admin/practices/{practiceId}/synthetic-clinical-capability` with another key, the new version, and a reason. This local-only command requires an enabled `SUPERADMIN`, enabled Practice, schema 15, and at least one enabled clinician with explicit patient-directory and patient-detail site grants. It records an append-only Practice administration event and increments the Practice version. Retries use the same key and body. Only synthetic clinical data is permitted; template publication and clinical approval remain separate.

Rollback disables `epr.team.enabled` and leaves all membership, credential, onboarding, audit and receipt records intact. V15 and V19 are additive; repair migration issues with a forward migration. Do not delete evidence or restore revoked access as a rollback mechanism.

## Contracts and verification

The existing exporter now includes administration scopes and all Practice administration routes in [the OpenAPI artifact](../../api/contracts/clinical-openapi.json). Generated team DTOs are included in the existing generated TypeScript file; the team client additionally validates runtime responses with Zod.

```sh
./api/mvnw -f api/pom.xml verify
npm --prefix web run lint
npm --prefix web run test
npm --prefix web run build
EPR_CONTRACT_BASE_URL=http://localhost:8083 node scripts/export-clinical-openapi.mjs
node scripts/generate-clinical-contracts.mjs
git diff --check
```

Do not run Maven builds concurrently against the shared target directory. PostgreSQL integration coverage includes account setup/reissue, immutable credential-free receipts, confirmed existing-account linking, cross-Practice denial, stale edits, explicit clinical authority, site tampering, duplicate concurrent creation, last-administrator concurrency, membership isolation and actual non-bypass RLS/append-only enforcement. Frontend tests cover schemas, scoped keys, CSRF/idempotency requests and distinct rejection/uncertain outcomes.

Browser plugin not available; temporary Playwright scripts use installed Chrome against the real local API. Required viewports are desktop 1440×1000, tablet 834×1194 and mobile 390×844. Flows cover Dashboard navigation, filters, member creation, one-time credentials, unsaved navigation, suspension/reactivation and reload; new doctors complete password setup and register synthetic patients at each size. Desktop additionally covers committed-response loss and exact retry, credential reissue, stale edit preservation/reload, and a newly authorized doctor's persisted encounter with verified actual author ID. Keyboard checks at every size confirm Space toggles roles, discard confirmation focuses the safe action, and Escape preserves edits and restores focus. Screenshots and temporary scripts remain outside committed source.

Verification result on 2026-10-08: 109 backend tests and 103 frontend tests passed; Maven verification, frontend lint/build, contract export/generation and whitespace checks passed. The three-layout browser flow covered Provider Support readiness, readiness-disabled Practice-admin controls, clinician/site setup, source enablement, per-source Maintain and review grants, structured access evidence, and authorized connected-chart controls without reauthentication. Maven reported the existing test-JVM shutdown warning after all tests completed, then packaged successfully.

These checks do not establish clinical validation, production readiness or the unimplemented offboarding/delegation capabilities.
