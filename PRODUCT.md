# EPR product context

Status: inferred from the accepted repository plans and the requested provider-login slice on 2026-09-13. The repository’s `AGENTS.md`, clinical product plan, and authentication decision remain the detailed authorities.

EPR is a doctor-centric Philippine outpatient record system for authorized work across practices and sites. Its first usable product must cover the complete outpatient core, not only a queue demonstration, while preserving practice tenancy, patient identity, clinical authorship, immutable signed history, and separately accountable work.

Primary users are clinicians and their authorized practice staff. Provider-workforce administrators are a distinct non-clinical audience: they operate the SaaS platform and may enter a practice only through an explicit, currently valid managed-administration grant. Global provider views contain no patient records, and support access never confers doctor, signer, prescriber, or result-review authority. Each Practice owns a separate logical PostgreSQL database; shared platform identity, tenant routing and browser-session data remain in a separate control plane.

The browser experience is responsive, high-scanability application UI. The implemented Dashboard is the visual authority: Roboto typography, navy text, royal-blue actions, white surfaces on a light neutral canvas, fine borders, modest radii, restrained status colors, Lucide icons, and explicit accessibility states.

Production browser authentication uses Cognito authorization code flow with PKCE and MFA. The clinical application runs at `app.<product-domain>` and provider support at `support.<product-domain>`, using distinct Cognito clients and separate secure, host-only `HttpOnly` Spring Session JDBC cookies with CSRF protection. A local-profile password adapter and seeded superadmin exist only for development. They must never be represented as the production identity design or as proof that tenant authorization, tenant database routing, and provider operations are implemented.
