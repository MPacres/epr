---
version: 1
slug: "web-src-features-provider-providerportal-tsx"
primary_target: "web/src/features/provider/ProviderPortal.tsx"
related_targets: ["web/src/features/provider/TenantCreation.tsx"]
---

MODE: Operate. Provider superadministrators need to find a Practice, understand whether it is usable, correct its control-plane identity, and deliberately control service access without seeing patient records.

THESIS: The directory is a tenant-control ledger: every row exposes identity, health, access state, and the next safe action. It refuses the generic empty admin dashboard and the create-only dead end.

OWN-WORLD: Extend the established Secure Clinical Workbench with a restrained white ledger, fine cool borders, navy hierarchy, royal-blue selection and actions, and labelled green, amber, red, or slate status treatments. Keep Roboto, Lucide, modest radii, and the provider shell.

STORY: The operator lands on Practices, immediately sees all persisted tenants and items needing attention, searches by trusted identity, opens a detail workspace, edits only the display name, and can suspend or reactivate service with a recorded reason. Patient and tenant-internal administration remain outside this global view.

FIRST VIEWPORT: A compact page heading and Create practice action lead directly into the workspace count, search and filters, then a productive full-width directory table. Row selection opens a dedicated detail route with identity and status at the top, operational facts in the main column, and bounded actions at the side. Mobile converts rows into complete cards without dropping status or identifiers.

FORM: Existing-surface extension in Operate mode; no concept-seed round applies. Seed key: extension-no-seed. The signature interaction is creation flowing directly into the newly persisted Practice detail workspace, where optimistic updates visibly reconcile the same record.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
