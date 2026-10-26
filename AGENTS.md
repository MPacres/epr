# EPR development instructions

## Scope and starting point

These instructions apply throughout this repository. Before working under `web/`, also read [web/AGENTS.md](web/AGENTS.md), including when starting a task from the repository root. Add further scoped instructions when a backend or infrastructure directory is actually introduced.

Build a doctor-centric Philippine outpatient Electronic Patient Record (EPR) for authorized work across practices and sites. Complete outpatient care includes registration, scheduling, queues, longitudinal charts, encounters, prescribing, orders/results, clinical inbox, referrals, documents, and follow-up. A queue-only demonstration is not a complete EPR.

Repository baseline as of 2026-10-08:

- `web/` contains a responsive React/TypeScript/Vite Dashboard, doctor schedule workspace, and a historical patient directory at `/patients/preview` with explicit synthetic fixtures, practice-scoped directory search/filtering/pagination, add/edit patient forms with in-memory demo saves, patient previews, global patient search, queue previews, inbox filters, practice-scoped patient charts, a patient-scoped draft/signed note history view, and historical encounter screens with guarded in-memory note editing. The provider-support workspace can create a local Practice tenant, prepare its initial administrator identity, persist its first Philippine clinic location through the Spring API, list/search/filter persisted Practice workspaces, edit a Practice display name, and suspend or reactivate service access with a recorded reason. Final decommission remains unavailable until its clinical-work, export, retention, and restore preflight exists. The schedule provides a practice-filtered day agenda, appointment details, and explicit non-persistent scheduling states. The older fixture-based clinical workspaces remain non-persistent; the opt-in connected slice is described separately below.
- TanStack Query, Zod, and Zustand are installed. Check `web/package.json` and its lockfile for actual dependencies and scripts.
- `api/` contains the Spring Boot/PostgreSQL foundation, framework migrations, a Dockerized local Nginx proxy for `epr.test` and `support.epr.test`, and a local-profile password authentication adapter backed by Spring Session JDBC. The local seeder maintains one configurable `SUPERADMIN` development account; the React app uses the hostname to expose only the clinical or provider-support route family while retaining combined localhost compatibility. The backend includes a development tenant-provisioning and administration slice with a control-plane Practice registry, support-issued one-time administrator passwords or safe existing-account linking, mandatory password replacement at first login, initial membership, idempotent/quarantined provisioning, separate PostgreSQL databases and roles, tenant Flyway bootstrap, first-site persistence, an encrypted local credential adapter, optimistic Practice metadata updates, reversible service suspension, and append-only administration events. No email invitation is sent. Production Secrets Manager/RDS administration, production tenant datasource routing and automated fleet migrations, Cognito/PKCE, MFA, production clinical authorization, decommission orchestration, broader audit workflows, and clinical services outside the opt-in synthetic slice remain unimplemented. Vitest covers the existing frontend selectors, forms, provider administration contracts, and host/role landing, and JUnit/Testcontainers cover modular boundaries, local authentication, provisioning recovery, administrator selection, first-site persistence, Practice administration lifecycle, and PostgreSQL database isolation; browser checks remain temporary rather than committed.
- My Patients at `/patients` now opens the saved authorized patient directory and retains patient context into encounter setup, including same-Practice location selection and Philippine date/time of service. An opt-in local synthetic connected encounter slice now lives in `web/src/features/encounters/` and the API clinical modules. It includes explicit clinical grants, bounded tenant runtime routing, persisted synthetic patient/pregnancy records, immutable template releases and response revisions, dynamic draft editing, persisted session/queue handoff, fenced active-care recovery, verified local HTML artifacts with synthetic signature/amendment mechanics, and a demand-driven owned-work projection. Encounter-linked vital measurements are independently saved, displayed as a dated patient timeline across authorized visits, editable, and removable when entered in error before signing; their exact current values enter the reviewed note artifact. Internal audit evidence is retained without exposing edit versions as historical measurements. Clinicians can soft delete eligible standalone draft encounters; signed, finished, queued, or clinically linked records remain protected, and deleted drafts retain their audit evidence. This does not connect the older historical fixture screens or enable real-patient care. See `docs/architecture/encounter-templates-obgyn-implementation.md` for commands, verification, and remaining clinical/production gates. Tenant clinical routing currently requires schema 19; production routing/signing/storage and pilot hardening remain gated.
- Opt-in synthetic encounter interventions now persist prescriptions, laboratory/imaging requests and groups, referrals, received/corrected reports and owned follow-up work. Shared intervention summaries bind exact manifests into note signing; verified original HTML artifacts remain immutable. Accepted handoffs and a retryable local worker preserve existing obligations when new intervention commands are disabled. Control-plane V16 and tenant V8–V11 add this slice. See `docs/architecture/encounter-interventions-implementation.md` for enablement, verification and remaining clinical/production gates. Procedure requests, real-patient issuance and external transmission remain unavailable.
- The connected synthetic chart backend now persists action-authorized longitudinal allergies, reported medication use and problems with immutable revisions, explicit absence assertions, staleness, proposals and exact encounter reconciliation decisions. Canonical review manifests replace new flat medication-safety review creation, bind prescription preparation/issuance and signed-note bytes, and remain readable after later source corrections. Control-plane V18 and tenant V12–V14 add the clinical slice; control-plane V19 and Practice Team management add readiness-safe per-site source grants without implicit doctor access. Current routing requires schema 19. See `docs/architecture/clinical-lists-reconciliation-implementation-plan.md` and `docs/architecture/clinical-list-access-management-implementation-plan.md`. Terminology search remains a local fixture, legacy flat reviews are historical read-only evidence, clinical access reduction remains gated, and real-patient safety use remains unavailable.
- The local synthetic encounter pack now publishes immutable v1.2.0 releases with template-driven ROS and physical-examination system rows, selected phrases, custom findings and a narrative fallback. No findings are preselected. Original v1.0.0 and narrative v1.1.0 releases remain pinned and readable; site availability for new starts moves to v1.2.0 on opt-in seeding. Explicit assessment states, server-attributed review of fresh clinician entry and exact candidate rendering remain. Clinical-owner phrase approval, usability sessions and real-patient issuance remain gated. See `docs/architecture/encounter-ros-physical-exam-implementation.md` and its clinical review packet.
- Saved patient registration now lives in `web/src/features/patients/SavedPatientForm.tsx` and `SavedPatients.tsx`: all six sections persist through the local synthetic API, with versioned corrections, protected identifiers, server-bound duplicate review, paginated directory/search, recent chart opens, authorized visit projections, and separate chart/encounter-setup destinations. Preview forms are under `/patients/preview/...`. See `docs/architecture/patient-registration-implementation.md` for capability/schema rollout and verification; staff registration and real-patient operation remain gated.
- Local opt-in tenant user management now lives in `web/src/features/practices/` and the `practiceadministration` orchestration module, with identity-owned memberships, multiple roles, explicit clinical sites, per-site longitudinal-list profiles, generic onboarding, one-time credentials, versioned/idempotent atomic commands and structured access history (control-plane V15 and V19). Practice administrators manage their own teams; global provider authority does not authorize team or individual doctor access. Provider Support owns bounded clinical readiness and the guarded local schema/capability actions. Non-clinical suspension/reactivation is available; clinical access reduction/offboarding and delegated support remain gated. See `docs/architecture/practice-team-access-implementation.md` for enablement, verification and remaining boundaries.
- `generated-assets/` contains plans, image concepts, and generation prompts. `.agents/skills/` contains local skill references. Both directories are currently Git-ignored and may be absent in another checkout.

Do not describe a planned component, control, or release gate as implemented. Keep these instructions current when the repository structure or verified commands change.

## Planning sources and conflict resolution

Read the relevant sections before implementation; paths below are relative to the repository root:

| Source | Use |
| --- | --- |
| `docs/architecture/session-and-client-authentication.md` | Accepted browser-session, horizontal-scaling, native-client authentication, offline-boundary, and deferred-Redis decisions. |
| `docs/architecture/database-per-practice-tenancy.md` | Accepted control-plane, database-per-Practice routing, provisioning, fleet-migration, pooling, restore, and relocation decisions. |
| `generated-assets/doctor-centric-epr-plan.md` | Product scope; domain model; canonical workflows; roles; clinical integrity; delivery phases and acceptance criteria. Start with sections 3–7, then the feature-specific sections and sections 22–23. |
| `generated-assets/epr-technical-architecture-plan.md` | Selected stack, modular boundaries, tenancy, initial online behavior, staged offline roadmap, infrastructure and release tests. |
| `generated-assets/epr-patient-forms-v2-notes.md` | Current registration/edit form structure and optional PhilHealth fields; supersedes the v1 form concepts. |
| `generated-assets/epr-desktop-workspaces-v1.md` | Schedule, Clinical Inbox, Referrals, and Documents concept index. |
| `generated-assets/epr-portrait-design-prompts-v1.md` | Tablet and phone layout references. |
| Adjacent `*-prompt.txt` files and PNGs | Visual reference for the relevant screen; illustrative data rather than runtime specifications. |

Within the planning material, use the product plan for clinical behavior and the technical plan for selected architecture and connectivity phases. The current implemented Dashboard is the shared visual and interaction baseline for new and updated frontend views; follow the design authority and mandatory three-layout verification rules in [web/AGENTS.md](web/AGENTS.md). Screen concepts inform feature-specific composition and content but must be adapted to that baseline unless the user explicitly requests a different design. Product safety, authorization, and canonical state rules take precedence over mockup labels, sample counts, or aesthetics. Explain material discrepancies instead of silently changing clinical behavior.

In particular, the product plan's general offline-tolerance ideas do not authorize offline persistence or issuance in the initial release. Follow the technical plan's online-first release and later Stage A/Stage B gates.

Some asset notes link to an old absolute `Projects/EPR/outputs/` location. Resolve their basenames against the actual `generated-assets/` directory. Use portable relative paths in repository documentation.

If ignored references are unavailable, use the essential requirements in these instructions and report the missing source. Request only the missing detail needed for a consequential decision; continue independent work. Do not invent unseen requirements or change ignore rules merely to expose local artifacts.

## Product and clinical invariants

- **Practice is the tenant.** A site belongs to a practice; a facility is a physical place. Sharing a hospital building, doctor, or patient identity does not grant access to another practice's chart.
- A `PracticeSession` belongs to one doctor, one site, and one practice. Each `QueueEntry` belongs to one session and patient and can have at most one active `Encounter`.
- Keep the explicitly active clinical session separate from the previewed queue. Opening an upcoming queue early, previewing it, or passing a scheduled start time must not silently change the doctor's active site.
- The primary Queue badge counts authorized `READY_FOR_DOCTOR` entries in the active clinical session only. Hide the numeric badge when none are ready or no session is active. Upcoming activity has a separate signal. Inbox counts deduplicate distinct actionable items across overlapping filters.
- Starting consultation revalidates patient, practice, session, authorization, readiness/override, and record version. Atomically create or retrieve the encounter, link it, update queue state, and record the audit event. Retry and concurrent requests must not duplicate care records.
- Chart review does not create an encounter. Support appropriately authorized non-queued care without fabricating an appointment or queue entry.
- Ending care, completing the queue entry, signing a note, reviewing a result, and closing follow-up are separate transitions. Session closure or clinician departure must not leave clinical work ownerless.
- Preserve original clinical values, authors, measurement times, sources, and correction history. Unknown, unreviewed, absent, loading, and failed are distinct states.
- Require human review of demographic duplicate candidates. Never auto-merge charts on name, date of birth, or phone; preserve original identifiers and reviewed links. Support provisional identities without inventing missing information.
- Secretary, nurse, clinician, covering clinician, and administrator permissions differ. Queue preparation and technical administration do not imply unrestricted chart access. Record the actual author and authorized signer.
- Urgent concerns require a visible owner, acknowledgement, and disposition through clinic policy. Do not invent diagnostic thresholds or represent the outpatient queue as an emergency triage service.

### Canonical state machines

```text
PracticeSession:
DRAFT -> OPEN -> IN_PROGRESS -> CLOSING -> CLOSED
DRAFT / OPEN -> CANCELLED

QueueEntry:
EXPECTED -> CHECKED_IN -> WAITING_FOR_PREPARATION -> READY_FOR_DOCTOR
         -> CALLED -> IN_CONSULTATION -> COMPLETED
Exception outcomes: CANCELLED, NO_SHOW, LEFT_WITHOUT_BEING_SEEN,
                    TRANSFERRED, DEFERRED
```

Display labels map to these states; do not create parallel states such as `PLANNED` for queue entries. Appointment planning stays separate. Validate transitions on the server with role, state, version, and reason guards. Walk-ins may enter at `CHECKED_IN`; permitted skipped steps, preparation overrides, return from `CALLED`, reordering, transfers, and reopening must retain their audit history. Never cancel away care already performed. Resolve or explicitly disposition outstanding queue entries before clean session closure.

## Architecture to implement

The following is the selected target, not a claim that these services exist:

| Area | Direction |
| --- | --- |
| Frontend | React + Vite + TypeScript; React Router; React Hook Form + Zod; Tailwind CSS + shadcn/ui; TanStack Query. See `web/AGENTS.md`. |
| Backend | Java 25, Spring Boot 4.1, Maven, Spring Modulith 2.1 modular monolith. Verify compatibility and pin tested patches when scaffolding. |
| Data | PostgreSQL 17 on Amazon RDS; a control-plane database plus one logical database per Practice tenant from launch; distinct per-tenant runtime/migration credentials; JPA/Hibernate plus explicit SQL where needed; controlled Flyway fleet migrations. |
| Identity/API | Cognito with MFA; separate `app.<product-domain>` and `support.<product-domain>` browser origins and Cognito clients; Spring Security; Spring Session JDBC in the control-plane database; origin-specific secure HttpOnly cookies and CSRF protection; REST/OpenAPI with generated TypeScript contracts. Future native clients use Cognito bearer tokens through a separate security adapter. |
| Live updates | Server-Sent Events with polling fallback; authorized subscriptions, event deduplication and server reconciliation after reconnect. |
| Hosting | Docker Compose on EC2 behind Nginx; private S3; KMS, Secrets Manager, CloudWatch; Terraform and GitHub Actions/ECR. |
| Verification | JUnit, Testcontainers, Modulith/ArchUnit; Vitest and Playwright, introduced as the corresponding implementation is added. |

Keep business modules separate: identity/tenancy, patient registry, scheduling/queue, clinical chart, encounters/templates, prescribing, orders/results, care coordination, documents/communication, and platform operations. Add synchronization later. Modules own persistence and expose application interfaces; no cross-module repository imports or direct table mutations. Enforce acyclic dependencies. Use a single transaction for critical workflows and durable PostgreSQL-backed events/jobs for retryable side effects.

Use the control-plane PostgreSQL-backed Spring Session repository for browser login from launch; it supports multiple application instances without sticky sessions and does not require Redis. Keep clinical-app and provider-support cookies host-only, separately named and unavailable to the sibling origin. Limit HTTP session contents to authentication and CSRF concerns. Do not store selected practice, tenant database routes, active clinical session, permissions, clinical drafts, or workflow state in the session. Map both browser sessions and future native-client bearer tokens into one transport-neutral authenticated-actor/application authorization boundary, and resolve current practice membership for consequential requests. Redis is an optional later optimization for measured session, rate-limit, cache, or cross-instance fan-out load; it is never the clinical source of truth or the durability mechanism for critical commands and events.

Use `/api/v1/practices/{practiceId}/...` with independently authorized practice context. Establish client-generatable UUIDs distinct from display record numbers, optimistic versions, original idempotency keys on retry, explicit state transitions, versioned contracts, and separate occurrence/client/server-receipt timestamps. Isolate UI from transport through application services/repositories. Avoid silent last-write-wins for clinical or identity conflicts.

Store reusable clinical facts relationally with units and provenance. Use JSONB for validated template definitions and responses. Publish immutable, flattened template releases with stable field identities and declarative rules; never execute arbitrary template code. Pin each encounter to its exact release. Signing preserves structured revision, exact document bytes, signer evidence and hashes; amendments link to originals. Template, renderer, terminology, and patient-link changes must not rewrite signed history.

PDF generation in the browser is not signing authority. Stage and verify uploaded/generated files before reporting archival success: database and S3 writes are not one transaction.

## Isolation, privacy, and resilience

- Resolve `practiceId` through an authorized control-plane tenant registry to a server-owned database/secret reference. Never accept a database name, URL, schema or credential selector from the client. Missing, disabled, migrating or mismatched routes fail closed.
- Provision a separate database, runtime role and migration role per Practice. Retain `practice_id` plus tenant/patient-consistent foreign keys within each tenant database for defense-in-depth and audit/export integrity. Runtime roles never own tables or connect to another tenant database. Use `FORCE ROW LEVEL SECURITY` for shared tenant-scoped control-plane or operational tables.
- Bound aggregate tenant datasource connections, lazily open and evict pools, and test route/pool reuse for tenant leakage. Jobs carry an explicit tenant route; application services additionally enforce site, assignment, role, field, and care-relationship restrictions.
- Run Flyway as an observable tenant-fleet operation with canaries, per-tenant schema status, bounded concurrency and a quarantined failure state. Do not assume one migration transaction can span tenant databases.
- Apply equivalent authorization to search, files, signed URLs, exports, caches, notifications, live events, and future sync feeds. Aggregate cross-practice worklists from separately authorized scopes. Identity linking does not share charts.
- Use synthetic data for development, demos, fixtures, browser captures, and tests. Keep patient content and secrets out of ordinary logs, analytics, crash reports, and unapproved third-party services.
- Keep attachments private, encrypted, and quarantined until scanning succeeds. Audit consequential changes and privileged reads/releases with actor, scope, record, time, action, outcome, and reason where required. Preserve append-only history.
- Initial persisted registration, encounter creation, signing, prescribing, and other clinical actions require server confirmation. Preserve open form contents on failure, report autosave accurately, expose stale data/conflicts, and warn before leaving unsaved work. Open form memory is not durable offline storage.
- Do not add persistent clinical caches, service-worker patient-response caching, offline signing, or offline prescribing as a convenience. Stage A adds encrypted enrolled/scoped offline preparation and sync; Stage B adds separately validated device-bound clinical issuance. Preserve those boundaries.
- Before real-patient use, verify isolation, membership revocation, MFA/session controls, secure exports, backup restoration including files/audit/keys, and paper downtime reconciliation. The technical plan's five-minute data-loss/four-hour restore targets are provisional and require drills. Single-instance/Single-AZ pilot infrastructure is not high availability.

## Delivery boundaries

Implement the requested slice while retaining the foundations above. Product phases 1 and 2 together form the recommended multi-site MVP: a safe outpatient core plus multi-practice operation. The technical sequence is foundation -> online core -> online pilot -> offline preparation -> offline issuance -> native clients/growth. A visual template builder and optional integrations are later work.

Hospital admissions/beds, ED operations, pharmacy/lab operations, full billing/claims, inventory, and payroll are outside the generic core. Prescribing, requisitions, reviewing external results, and printable documents are inside it.

Resolve deployment-specific governance and PhilHealth/YAKAP/GAMOT/NHDR applicability before the affected site's launch. Do not treat optional identifiers as verified eligibility or a generic EPR as certified. Verify current official requirements when implementing regulated/program-specific behavior; planning notes are requirements, not legal advice or certification. Required program capabilities cannot be deferred simply because generic integrations are later work.

## Working and verification rules

1. Inspect existing code, relevant plans, scoped instructions, and applicable skills before edits. Prefer established components and conventions; introduce only dependencies needed for the requested slice.
2. Preserve unrelated work. Keep changes focused and distinguish functional mocks from connected behavior. Do not implement success-only mocks as production persistence or authorization.
3. Add meaningful tests for changed domain behavior: isolation, transition guards, duplicate retries/concurrent starts, conflicting edits, signed-record immutability, patient context, or continuity of ownership as applicable. Do not substitute UI visibility tests for backend authorization tests.
4. For frontend code/configuration changes, run the actual lint/build scripts and the rendered checks in `web/AGENTS.md`. Every rendered UI change must be verified on all three layouts: desktop web, portrait tablet, and mobile. A desktop-only check or passing build does not satisfy this requirement. For future backend work, add and document real verification commands when scaffolding; do not claim nonexistent suites passed.
5. Before an online pilot, cover the product plan's section 23 and technical release gates, including two-practice access, later results/follow-up, timeout retries, template changes, restore/downtime drills, clinician-approved outputs, and representative load (approximately 50 concurrent users in the plan).
6. Documentation-only changes require source/path/command consistency checks and whitespace review; no application build or Playwright run is needed unless behavior also changed.
7. Report what changed, verification actually performed, and material remaining gaps. Do not equate a passing build with clinical validation or readiness for real patients.

Keep root guidance focused on shared invariants and scoped files focused on their directories. See [OpenAI's AGENTS.md guidance](https://learn.chatgpt.com/docs/agent-configuration/agents-md) for instruction discovery and layering.
