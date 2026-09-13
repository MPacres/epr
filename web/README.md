# EPR clinical workspace

Responsive doctor dashboard and My Patients directory with add/edit forms based on the desktop v3, tablet portrait, and mobile portrait references in `../generated-assets/`.

## Run and verify

From the repository root:

```sh
npm --prefix web ci
npm --prefix web run dev -- --host 127.0.0.1
npm --prefix web run lint
npm --prefix web run test
npm --prefix web run build
npm --prefix web run preview -- --host 127.0.0.1
```

`test` runs Vitest. TypeScript checks run as part of `build`. Rendered verification uses Playwright through temporary scripts; there is no committed e2e runner.

## Implemented views

- Sticky top navigation, desktop sidebar, tablet rail, and mobile bottom navigation; layouts support 320px and larger widths.
- Active-session queue summary and patient detail sheets. Upcoming-session previews retain the active Hospital A context and badge.
- Centered Spotlight-style patient search opens on click or Cmd/Ctrl+K. Typing filters patients across the physician’s clinic memberships by name or MRN; each result retains its clinic label; arrow keys navigate, Enter opens the overview, and Escape or an outside click dismisses it. Results scroll independently while the page remains locked. Includes initial and empty states plus 33 initial synthetic patients across Hospital A and Clinic B to test scrolling and cross-clinic search. The header uses a platform-neutral keyboard icon.
- My Patients (`#/patients`): independent practice scope, name/MRN/birth-date search, visit-history and provisional-identity filters, sorting, six-record pagination, and patient details. Desktop uses a side preview; portrait tablet and mobile use accessible detail sheets. Recent patients tracks overviews opened from the directory in memory until leaving the view. Counts reflect the fixtures: 30 Hospital A records and 3 Clinic B records.
- Same-name records remain separate, including within one practice. Provisional identity, unknown birth date/age, and no recorded visits have distinct displays. Directory previews retain the active session and do not start care. The add/edit forms apply explicitly labeled demo changes in memory. Real registration, scheduling, and the full longitudinal chart remain unconnected.
- React Router hash navigation supports Dashboard, My Patients, New patient, and Edit patient. Unsaved forms guard Cancel, sidebar navigation, and browser Back/Forward; refresh/close uses the native before-unload warning.
- Clinical inbox categories, overlapping overdue filter, care follow-ups, and item details with patient, practice, due date, and owner.
- Schedule summaries and profile preview; unavailable workspaces are identified as outside this slice.
- Keyboard tabs, focus restoration, accessible Radix dialog sheets, reduced motion, Lucide icons, self-hosted Roboto, and shared Tailwind/shadcn-style controls.

The blue/white references remain the visual basis. The implementation adds explicit preview labeling, retains patient identifiers on mobile, and uses detail sheets rather than fabricating complete destination workflows. Desktop date is the fixed fixture date, September 12, 2026; waits are a snapshot at 10:00 AM Asia/Manila.

## Structure

- `src/features/dashboard/`: screen, styles, typed fixtures, display selectors, selector tests.
- `src/features/patients/`: directory, responsive patient details, synthetic demographic fixtures, search/scoping/pagination selectors, and focused Vitest tests.
- `src/components/layout/`: responsive clinical shell.
- `src/components/ui/`: shared Button and Radix-based Sheet.
- `src/index.css`: font, theme tokens, global accessibility defaults.

## Data boundary

The feature `demo-data.ts` files are explicit synthetic seeds. PatientPreviewProvider and PatientPreviewRepository keep demo additions, edits, revisions, retry outcomes, and before/after history in application memory until reload. Global search uses the same expanded directory identities; birth dates match existing dashboard fixture ages at the snapshot date. No backend, authentication, authorization enforcement, durable clinical persistence, live connection status, or offline patient storage exists. Opening a patient does not create an encounter. Results cannot be marked reviewed and notes cannot be signed. No result content or allergy absence is fabricated. Display selector tests do not establish server authorization or readiness for real patients.

When connecting services, replace fixtures through typed repositories, establish independently authorized practice scopes, and use practice-scoped TanStack Query keys. Preserve active versus previewed session semantics and all root/scoped AGENTS.md invariants.

## My Patients validation

Run the lint, test, and build commands above. Directory selector tests cover practice display scoping, duplicate names, date search and age boundaries, unknown values, filters, recent-record ordering, and stale pagination. These are client display tests, not backend isolation tests.

Rendered Playwright checks use desktop 1440×1000, portrait tablet 834×1194, mobile 390×844, and small mobile 320×740. Exercise navigation, same-name and birth-date search, empty results, filtering, pagination, patient overview, recent patients, practice switching without active-session changes, keyboard/focus restoration, global search, reload/history, sticky navigation, and horizontal overflow. Dashboard is checked at the same sizes because it shares the shell. Temporary scripts and screenshots live outside this repository.

## Add and edit patient forms

Open **New patient** from My Patients, or **Edit patient details** from a selected patient preview. Routes:

- `#/patients/new?practice=practice-a`
- `#/patients/practice-a/patient-maria/edit`

The six expandable sections follow the v2 references: Demographics; Contact & Preferences; Address; PhilHealth; Representatives & Emergency Contacts; Identifiers. New opens Demographics and PhilHealth. Edit opens Address and PhilHealth. Both use one persistent form-wide save bar; edit retains the original patient name, MRN, and practice in the save context while changes are reviewed.

React Hook Form and Zod validate names, exact/estimated dates, optional contacts and identifiers. Unknown birth dates and single-name provisional identities are supported. PhilHealth fields are optional, masked by default, and separate from practice IDs; dependents have separate principal-member fields. Verification and eligibility are not asserted. The seeded `DEMO-PIN-4821` is deliberately synthetic. Supporting-document upload remains disabled and visibly unconnected.

Possible name/birth-date matches are practice-scoped and require explicit review before saving a separate record. Changes to matching information invalidate the review. This limited fixture check does not establish a production duplicate-detection service and never merges records.

**Add demo patient** and **Save demo changes** update the directory and global search in memory. Edit shows changed fields and an optional change note. Existing identifiers, before/after revisions, and notes remain in transient demo history. New records receive a UUID and a visibly synthetic display number. No queue entry or encounter is created. Reload resets the workspace; no localStorage, sessionStorage, or patient-response caching is used.

The collapsed **Demo save scenarios** control exercises connection-failure and version-conflict responses while keeping the draft intact. These are simulated responses. The fixture repository also rejects stale versions, wrong-practice record updates, stale duplicate reviews, and changed-payload reuse of a completed idempotency key. These tests do not establish backend isolation, durable audit, or clinical readiness.

Form-specific files live in `src/features/patients/`: `PatientForm.tsx`, `PatientFormSections.tsx`, `PatientFormField.tsx`, `patient-form-model.ts`, `patient-form-schema.ts`, `patient-form.css`, and the preview provider/repository. Form and validation bundles load on demand. `src/components/ui/confirmation-dialog.tsx` provides the shared discard dialog.

Validation uses the commands above (27 Vitest tests) and temporary Playwright scripts at desktop 1440×1000, portrait tablet 834×1194, mobile 390×844, and small mobile 320×740. Covered flows include add/edit, section defaults, field errors, PIN masking, dependent fields, duplicate review, successful demo save, failed/conflicting responses with draft preservation, unsaved navigation, global-search updates, estimated/unknown identity facts, practice context, unchanged identifiers, and reset on reload. Screenshots/scripts remain outside this repository.
