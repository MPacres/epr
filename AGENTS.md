# EPR development instructions

## Scope and starting point

These instructions apply throughout this repository. Before working under `web/`, also read [web/AGENTS.md](web/AGENTS.md), including when starting a task from the repository root. Add further scoped instructions when a backend or infrastructure directory is actually introduced.

Build a doctor-centric Philippine outpatient Electronic Patient Record (EPR) for authorized work across practices and sites. Complete outpatient care includes registration, scheduling, queues, longitudinal charts, encounters, prescribing, orders/results, clinical inbox, referrals, documents, and follow-up. A queue-only demonstration is not a complete EPR.

Repository baseline as of 2026-09-13:

- `web/` contains a responsive React/TypeScript/Vite Dashboard, doctor schedule workspace, and My Patients directory with explicit synthetic fixtures, practice-scoped directory search/filtering/pagination, add/edit patient forms with in-memory demo saves, patient previews, global patient search, queue previews, inbox filters, practice-scoped patient charts, and historical encounter screens with guarded in-memory note editing. The schedule provides a practice-filtered day agenda, appointment details, and explicit non-persistent scheduling states. It does not implement persisted clinical workflows.
- TanStack Query, Zod, and Zustand are installed. Check `web/package.json` and its lockfile for actual dependencies and scripts.
- Backend, database migrations, infrastructure, and authentication have not been scaffolded. Vitest covers dashboard/directory and schedule display selectors, patient-form validation and in-memory demo save guards, and chart/encounter fixture scoping; there is no committed browser or backend test suite.
- `generated-assets/` contains plans, image concepts, and generation prompts. `.agents/skills/` contains local skill references. Both directories are currently Git-ignored and may be absent in another checkout.

Do not describe a planned component, control, or release gate as implemented. Keep these instructions current when the repository structure or verified commands change.

## Planning sources and conflict resolution

Read the relevant sections before implementation; paths below are relative to the repository root:

| Source | Use |
| --- | --- |
| `docs/architecture/session-and-client-authentication.md` | Accepted browser-session, horizontal-scaling, native-client authentication, offline-boundary, and deferred-Redis decisions. |
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
| Data | PostgreSQL 17 on Amazon RDS; shared tenant tables with RLS from launch; JPA/Hibernate plus explicit SQL where needed; Flyway migrations. |
| Identity/API | Cognito with MFA; Spring Security; Spring Session JDBC for the browser; secure HttpOnly cookies and CSRF protection; REST/OpenAPI with generated TypeScript contracts. Future native clients use Cognito bearer tokens through a separate security adapter. |
| Live updates | Server-Sent Events with polling fallback; authorized subscriptions, event deduplication and server reconciliation after reconnect. |
| Hosting | Docker Compose on EC2 behind Nginx; private S3; KMS, Secrets Manager, CloudWatch; Terraform and GitHub Actions/ECR. |
| Verification | JUnit, Testcontainers, Modulith/ArchUnit; Vitest and Playwright, introduced as the corresponding implementation is added. |

Keep business modules separate: identity/tenancy, patient registry, scheduling/queue, clinical chart, encounters/templates, prescribing, orders/results, care coordination, documents/communication, and platform operations. Add synchronization later. Modules own persistence and expose application interfaces; no cross-module repository imports or direct table mutations. Enforce acyclic dependencies. Use a single transaction for critical workflows and durable PostgreSQL-backed events/jobs for retryable side effects.

Use the shared PostgreSQL-backed Spring Session repository for browser login from launch; it supports multiple application instances without sticky sessions and does not require Redis. Limit HTTP session contents to authentication and CSRF concerns. Do not store selected practice, active clinical session, permissions, clinical drafts, or workflow state in the session. Map both browser sessions and future native-client bearer tokens into one transport-neutral authenticated-actor/application authorization boundary, and resolve current practice membership for consequential requests. Redis is an optional later optimization for measured session, rate-limit, cache, or cross-instance fan-out load; it is never the clinical source of truth or the durability mechanism for critical commands and events.

Use `/api/v1/practices/{practiceId}/...` with independently authorized practice context. Establish client-generatable UUIDs distinct from display record numbers, optimistic versions, original idempotency keys on retry, explicit state transitions, versioned contracts, and separate occurrence/client/server-receipt timestamps. Isolate UI from transport through application services/repositories. Avoid silent last-write-wins for clinical or identity conflicts.

Store reusable clinical facts relationally with units and provenance. Use JSONB for validated template definitions and responses. Publish immutable, flattened template releases with stable field identities and declarative rules; never execute arbitrary template code. Pin each encounter to its exact release. Signing preserves structured revision, exact document bytes, signer evidence and hashes; amendments link to originals. Template, renderer, terminology, and patient-link changes must not rewrite signed history.

PDF generation in the browser is not signing authority. Stage and verify uploaded/generated files before reporting archival success: database and S3 writes are not one transaction.

## Isolation, privacy, and resilience

- Require `tenant_id`, tenant/patient-consistent foreign keys, RLS read/write policies, and `FORCE ROW LEVEL SECURITY` on tenant-owned data. Separate runtime roles from migration/table-owner roles; runtime roles cannot bypass RLS.
- Set tenant context transaction-locally after authorization, including jobs; missing context fails closed. Test pooled-connection reuse. Application services additionally enforce site, assignment, role, field, and care-relationship restrictions.
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
