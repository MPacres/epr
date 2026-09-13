# Frontend development instructions

Applies to all work under `web/`, together with [the root instructions](../AGENTS.md). Paths in commands are repository-root-relative; source paths in prose are relative to `web/` unless stated otherwise.

## Current setup and commands

The application implements a responsive, synthetic-data Dashboard in `src/features/dashboard/` and My Patients directory plus add/edit forms in `src/features/patients/`, composed by `src/App.tsx`. React Router uses hash routes for Dashboard, My Patients, and patient forms, with a data-router blocker for unsaved form navigation. Shared application layout and controls live in `src/components/`; global font/color tokens are in `src/index.css`. Tailwind CSS, Lucide, self-hosted Roboto, and shadcn-style Button/Radix Dialog primitives are installed. Vitest covers dashboard/directory display selectors, form validation, and the in-memory fixture save behavior. React, TypeScript, TanStack Query, Zod, and Zustand are also installed. React Router, React Hook Form, and the Zod resolver are installed. There is no backend connection, authentication, or durable clinical workflow. A typed in-memory fixture repository supports explicitly labeled demo add/edit commands, versions, duplicate-review guards, idempotent retries, and transient history; all reset on reload. Playwright browser checks currently run through temporary scripts outside the repository, not a committed e2e suite.

Use npm and preserve `package-lock.json`. From the repository root:

```sh
npm --prefix web ci
npm --prefix web run dev -- --host 127.0.0.1
npm --prefix web run lint
npm --prefix web run test
npm --prefix web run build
npm --prefix web run preview -- --host 127.0.0.1
```

Run `ci` when dependencies need installation, and `build` before preview. `lint` runs Oxlint; `build` runs TypeScript project checks and Vite. Use the actual URL printed by the dev server. `test` runs Vitest dashboard/directory selectors, form validation, and in-memory fixture repository tests. There is no `test:e2e` or separate `typecheck` script; inspect and update this section when introducing them. Never claim a missing command ran.

## Frontend skills and UI/UX policy

Read applicable skill instructions before using them. Resolve installed skills through the active skill catalog; do not hard-code one developer's home directory.

| Work | Skills to apply |
| --- | --- |
| React UI implementation or modification | `react-best-practices` (catalog name may be `vercel-react-best-practices`) and `frontend-testing-debugging`. Apply Vite/client React rules; Next.js/server-component advice is not applicable to this SPA. |
| EPR/EMR or clinical screens and workflows | Also use `clinical-ui`, including patient forms, queues, scheduling, encounters, charts, inbox, results, prescribing, referrals, and documents. Local source: `../.agents/skills/clinical-ui/SKILL.md`. |
| Creating, redesigning, or materially changing UI/UX | Use `impeccable` alongside the applicable React, testing, and clinical skills for hierarchy, layout, spacing, typography, component presentation, responsiveness, interaction design, and visual polish. Local source: [impeccable/SKILL.md](../.agents/skills/impeccable/SKILL.md). |
| Focused accessibility work | Also use the available `accessibility` skill. Accessibility remains a baseline for every UI change. |

Clinical usability, patient safety, accessibility, and workflow efficiency take precedence over aesthetic recommendations. Follow `clinical-ui` when visual advice conflicts with clinical requirements. Do not change an established clinical workflow solely to make the interface look cleaner or more impressive. Reuse the existing design system and components before introducing new patterns; while scaffolding, establish shared primitives instead of duplicating per-screen controls.

**Availability review (2026-09-12):** `react-best-practices` and `frontend-testing-debugging` are installed locally; `clinical-ui` and `impeccable` are available in the project skill catalog and the ignored project skills directory. Impeccable's instructions have been reviewed. Use its **Operate** mode for clinical application surfaces: scanability, consistency, and task completion take precedence over visual expression. Preserve the approved clinical brief and the clinical/accessibility priorities above.

If a named skill is missing, search the available catalog and project skill sources, report the exact gap, and use the concrete clinical/design requirements below for work they sufficiently specify. Available `make-interfaces-feel-better` or `ui-ux-pro-max` may supplement visual work; disclose the substitution rather than calling it `impeccable`. If the task specifically depends on unavailable skill instructions, obtain that source before the dependent work. Missing optional guidance does not block unrelated work. Do not install or fabricate a skill merely to satisfy its name.

These rules consolidate the proposed Frontend Skills and UI/UX Skill Rules into one policy. Backend-only or documentation-only edits do not require a visual-design or browser-testing workflow.

## Component and state architecture

- Organize new work by feature, for example `src/features/patients/` and `src/features/queue/`, with shared UI, layout, and API utilities. This is a direction for new code, not a claim those directories already exist. Keep `App.tsx` as composition, not the entire clinical application.
- Build reusable UI primitives in `src/components/ui/` and shared application layouts in `src/components/layout/` as the requested screens need them. Reuse and extend these components instead of duplicating buttons, form controls, dialogs, tables, badges, or navigation across screens. Keep feature-specific components with their feature; do not scaffold an unused component library. Follow the selected Tailwind CSS and shadcn/ui direction when introducing the design system.
- Apply the Vite/client React rules from `vercel-react-best-practices` to implementation and review. Keep components focused, use stable record IDs for list keys, derive values during rendering instead of synchronizing redundant state with effects, and keep user-triggered logic in event handlers. Use effects for external synchronization with appropriate cleanup; add memoization only when justified by measured or clearly expensive work. Prefer direct shared-component imports and statically analyzable library imports; avoid loading entire icon collections or adding Next.js-only configuration.
- Use typed components and domain contracts. Keep clinical transition rules, validation, and transport outside rendering components. Treat frontend validation as usability support; the server remains authoritative.
- Use TanStack Query for server state. Include practice and relevant patient/session identifiers in query keys. Cancel/remove inaccessible cached data on logout or scope revocation, and prevent late responses from populating another context. Do not add SWR to implement generic skill examples.
- Use local React state for local interactions and Zustand only where shared UI state is justified. Do not duplicate server records in a global store or use browser storage as a clinical system of record. Keep active-session state separate from preview selection.
- Put HTTP/session/error handling behind typed services or repositories. Use generated OpenAPI contracts once available, with Zod where runtime validation is needed. Keep typed synthetic fixtures behind an explicit development boundary until then.
- Handle authorization expiry, forbidden access, network failure, validation errors, and version conflicts distinctly. Never show success, clear a draft, or update an issued status until the server confirms the command.
- Reuse the original idempotency key for a retry of the same command. Prevent duplicate submissions in the UI while retaining backend idempotency as the real guarantee. Reconcile live events and invalidate the affected scoped queries after mutations/reconnect.
- Use explicit practice timezone information for session dates; the pilot uses `Asia/Manila`. Preserve date-only birth dates without timezone shifts. Distinguish clinical occurrence, recording, and last-refresh times and display measurement units.

## Navigation and context

- Doctor primary destinations: Dashboard, Queue, My Patients, Schedule, Clinical Inbox, Referrals, Documents, Practices, Settings. Queue remains top-level. Doctor Tasks belong in Clinical Inbox; staff navigation follows authorized role-specific workflows.
- Within a patient chart, keep Overview, Encounters, Medications, Orders & Results, Care Plan, Referrals, Documents, and Patient Details local to that chart. Opening a chart must not implicitly start care.
- Keep patient identity visible using at least two appropriate identifiers, with practice/site context and critical alerts. A queue token alone is insufficient. Do not carry patient A's draft, modal, fetched data, or pending action into patient B's chart.
- Show selected practice, site, session date/time, doctor, and current/upcoming state before a clinical action. Previewing Clinic B while active at Hospital A keeps Hospital A's ready badge unchanged. Starting care at the previewed site requires explicit activation and applicable overlap checks.
- Queue rows communicate patient, arrival/wait, status, appointment or walk-in, preparation/vitals readiness, and encounter state. Preserve session boundaries and map display labels to the root canonical states.
- Inbox items retain source patient/practice, accountable owner, urgency/due date, and a path to the underlying record. Overdue is a filter on tasks, not an extra set to add to the total.

## Clinical forms and interactions

- Keep critical allergies, medication warnings, abnormal/critical results, and relevant alerts prominent. Do not hide them only in tooltips, hover states, or initially collapsed content. Use text/icon labels with color.
- Distinguish loading, load failure, unknown/not recorded, not reviewed, verified absence, stale data, and confirmed values. Never display “No allergies” during loading or infer reviewed absence from an empty unreviewed record.
- Preserve entered values after validation, timeout, server, or conflict errors. Associate errors with fields; make required, optional, and read-only states explicit. Autosave shows saving, server-saved, failed, or disconnected truthfully. Guard unsaved navigation without implying browser-close durability.
- Group long forms logically. The v2 patient form has six expandable sections: Demographics; Contact & Preferences; Address; PhilHealth; Representatives & Emergency Contacts; Identifiers. Keep headings visible and one form-wide save action. Default New Patient to Demographics and PhilHealth open; default Edit Patient to Address and PhilHealth open.
- Keep PhilHealth PIN optional, distinct from MRN and identity UUID. Capture role, contributor category, source, and verification state without asserting eligibility. Support unknown/unavailable values, mask existing PINs, and conditionally expose a dependent's principal-member name/PIN/relationship as separate identifiers. Optional information must not block basic registration.
- Address fields cover street, barangay, city/municipality, province/region, and postal code. Support absent phone/government ID and unknown or estimated birth date without fabricated values. Show duplicate candidates for human review while preserving original entered names.
- Provide intentional confirmation for destructive or irreversible product actions, explaining the affected patient/record and outcome. This is an application interaction requirement, not a request for developer approval of routine code edits. Signed clinical records use amendments, not in-place edits or ordinary deletion.
- Repetitive care actions should be easy to reach with minimal unnecessary navigation or dialogs. Signing/issuing still requires deliberate review of the patient and clinical content. Do not substitute cosmetic success toasts for persisted outcomes.

## Visual references and accessibility

### Current Dashboard is the design baseline

All new and updated frontend views must follow the **current implemented Dashboard**, including the shared shell, visual language, and interaction patterns. Inspect the running Dashboard and the relevant implementation before making UI changes:

| Source (relative to `web/`) | Design responsibility |
| --- | --- |
| [src/features/dashboard/Dashboard.tsx](src/features/dashboard/Dashboard.tsx) and [dashboard.css](src/features/dashboard/dashboard.css) | Visual hierarchy, information density, surfaces, spacing, typography, badges, responsive composition |
| [src/index.css](src/index.css) | Shared color and font tokens, global accessibility defaults |
| [src/components/layout/workspace-shell.tsx](src/components/layout/workspace-shell.tsx) | Sticky top bar, desktop sidebar, tablet rail, mobile bottom navigation |
| [src/components/ui/](src/components/ui/) | Reusable buttons and accessible detail sheets |
| [src/features/dashboard/PatientSpotlight.tsx](src/features/dashboard/PatientSpotlight.tsx) and [patient-spotlight.css](src/features/dashboard/patient-spotlight.css) | Centered patient search, keyboard interaction, bounded scrolling, empty states |

Reuse the existing tokens and components. Preserve the light neutral canvas, white surfaces, navy text, royal-blue actions, pale-blue selection, restrained labeled status colors, fine borders, and modest radii. Match the Dashboard's Roboto typography, Lucide icon treatment, spacing rhythm, readable clinical hierarchy, and responsive navigation. Adapt composition to each workflow rather than copying dashboard content or introducing a different visual system per view.

Keep the top navigation sticky on all three layouts. Preserve the OS-neutral search affordance and “Search patients…” wording. Global patient search spans the physician's separately authorized clinic/practice scopes, shows each result's clinic, and must not switch the active clinical session. Preserve Spotlight's centered presentation, live filtering, independently scrollable results, keyboard navigation, dismissal, and focus restoration. Synthetic fixtures demonstrate these interactions; they are not backend authorization.

The user's latest explicit design direction takes precedence. Otherwise, the rendered Dashboard and its current source take precedence over older generated concepts and generic skill aesthetics. Clinical safety and accessibility still take precedence over visual fidelity. Do not replace the shared design or revert newer Dashboard behavior just to match an older mockup.

### Supporting concepts and accessibility

- Use **Lucide** through `lucide-react` for application UI icons. Import only the icons needed through supported, typed exports; avoid dynamic full-library icon registries and mixing icon libraries. Keep sizes and stroke widths consistent through shared styles/components. Hide decorative icons from assistive technology, give icon-only controls accessible names, and retain visible text for clinical meaning and status.
- Use **Roboto** for application body text, headings, and form controls, with a shared font token and a system sans-serif fallback. Self-host the required font files/weights, for example through `@fontsource/roboto`, and use `font-display: swap`. Form controls should inherit the application font; reserve monospace for actual code where needed. Lucide and self-hosted Roboto are installed and used by the dashboard; reuse their shared styling for new views.

Use the relevant existing concept and its prompt for feature-specific content and layout ideas, adapting them to the current Dashboard baseline:

| Surface | Repository-root-relative reference |
| --- | --- |
| Desktop dashboard | `generated-assets/epr-desktop-dashboard-modern-v3.png` and its `-prompt.txt` |
| Queue and patient directory | `generated-assets/epr-desktop-queue-v1.png`, `generated-assets/epr-desktop-my-patients-v1.png`, and their prompts |
| Registration/edit | `generated-assets/epr-desktop-new-patient-v2.png`, `generated-assets/epr-desktop-edit-patient-v2.png`, their prompts and v2 notes |
| Schedule/inbox/referrals/documents | Respective `generated-assets/epr-desktop-*-v1.png` files and prompts |
| Portrait layouts | `generated-assets/epr-tablet-dashboard-portrait-v1.png`, `generated-assets/epr-mobile-dashboard-portrait-v1.png`, and portrait design notes |

The generated dashboard and portrait concepts are supporting references, not the current design authority. Reuse the implemented shared tokens; do not copy image dimensions rigidly or introduce decorative charts, glass effects, or excessive whitespace that impairs clinical scanning. Treat image text and example counts as illustrative.

Use semantic HTML, labeled controls, visible focus, keyboard access, accessible dialogs/tabs, announced save/error/status changes, and sufficiently contrasting text and controls. Support zoom and touch without losing identity, alerts, or primary actions. Avoid icon-only meaning, hover-only controls, and color-only urgency.

On desktop, support productive tables and persistent context. On tablet/phone, reflow cards and forms and use deliberate table scrolling or detail views; do not drop clinically important data. The product plan prioritizes Queue, Patients, Inbox, and More on mobile. Portrait concepts may also show Dashboard; preserve access through the responsive navigation without silently adopting conflicting workflow behavior. Keep critical information readable rather than squeezing every desktop panel above the fold.

## Verification and completion

For React code/configuration changes, run `npm --prefix web run lint` and `npm --prefix web run build`. Add focused unit/integration tests when implementing state transitions, validation, permission-sensitive presentation, query scoping, or draft/conflict behavior; use actual configured scripts. Avoid tests that only mirror trivial markup.

For every rendered UI change, follow `frontend-testing-debugging` and verify **all three layouts** with Playwright interactions and inspected screenshots before considering UI verification complete. This includes changes to screens, shared components, styles, typography, icons, copy, navigation, forms, responsive behavior, patient context, and clinical status presentation. Do not skip tablet or mobile because the edit appears desktop-specific.

Use this minimum viewport matrix, in CSS pixels:

| Required layout | Viewport | Expected composition |
| --- | --- | --- |
| Desktop web | 1440 × 1000 | Persistent sidebar, sticky top navigation, productive multi-column layout |
| Portrait tablet | 834 × 1194 | Compact navigation rail, sticky top navigation, reflowed content |
| Mobile | 390 × 844 | Sticky top navigation, bottom navigation, readable single-column content and touch controls |

These are minimum verification sizes, not fixed design dimensions. Add 320px mobile width, intermediate breakpoint widths, zoom, or landscape checks when the change could affect those cases; extra checks do not replace any of the three required layouts. Shared shell, token, or primitive changes require checking the Dashboard as well as the changed feature at all three sizes.

1. Define the target flow and expected result. Start the app using the actual package scripts.
2. Use the Browser plugin's Playwright workflow when that plugin and its browser skill are available. Otherwise use regular Playwright and record `Browser plugin not available`. For invocation failure, follow the testing skill's fallback rules and report the exact failure; do not silently switch paths.
3. At each required viewport, verify page identity, meaningful content, absence of a framework error overlay, console health, and at least one real target interaction followed by a state assertion. A screenshot alone does not prove behavior.
4. Capture and inspect screenshots at all three required viewports. Inspect the initial viewport, scroll through the changed content, and inspect affected open menus/dialogs or result lists. Check sticky navigation, responsive reflow, text readability, touch targets, focus visibility, scrolling, and absence of clipping, horizontal page overflow, or content trapped behind fixed navigation. Compare the result with the current Dashboard design baseline and explain intentional deviations.
5. Exercise applicable loading, empty, failed, stale/disconnected, unauthorized, conflict, and duplicate-submission states. Verify preserved form data, keyboard/focus behavior, and no patient/practice leakage during switching. In fixture-only work, distinguish simulated responses from verified backend behavior.
6. Keep temporary scripts, screenshots, and traces outside committed source unless requested. Report the actual viewport, interaction, screenshot evidence, and pass/fail result for desktop web, tablet, and mobile, plus commands and material untested cases. Do not claim responsive verification is complete unless all three layouts pass. If tooling prevents a required check, report that layout as unverified and overall rendered validation as incomplete; a build or another viewport is not a substitute.

Documentation-only work needs link, source, command, and whitespace checks, not Playwright. Passing frontend tests never establishes server isolation, clinical approval, regulatory certification, or pilot readiness.
