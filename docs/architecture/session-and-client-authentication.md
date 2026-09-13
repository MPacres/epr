# Session and client authentication strategy

Status: Accepted  
Date: 2026-09-13

## Decision

Use Cognito and Spring Security for authentication. The React browser application uses a backend-managed session stored through Spring Session JDBC in the existing PostgreSQL service. Future native applications use Cognito bearer access tokens through a separate Spring Security resource-server adapter.

Both authentication mechanisms resolve into the same transport-neutral authenticated actor and application authorization boundary. Redis is not required for the initial release and is introduced only in response to measured operational needs.

## Browser flow

Use authorization code flow with PKCE. The backend keeps Cognito access and refresh credentials out of React and returns a secure, `HttpOnly`, host-only session cookie. Cookie-authenticated state-changing requests require CSRF protection.

The HTTP session is limited to authentication and CSRF concerns. It must not hold:

- The selected practice or active clinical session.
- Practice membership or cached authorization decisions.
- Patient records, clinical drafts, queue state, or workflow progress.
- Authoritative application or domain state.

Current practice membership and authorization for consequential operations are resolved from server-side records. Logging in establishes identity; it does not freeze authorization for the lifetime of the session.

## Horizontal scaling

All application instances share the Spring Session JDBC repository. A load balancer may route consecutive requests to different instances; sticky sessions are not required.

Instances must share compatible:

- Cookie names, scope, security flags, and timeout policy.
- Session attribute and serialization contracts across rolling deployments.
- Relevant token-encryption or cryptographic configuration.

Session attributes should remain minimal. Aggregate application connection pools must stay within the PostgreSQL connection budget, and expired-session cleanup must be enabled and monitored.

Horizontal scaling outside session management remains separate work:

- Durable PostgreSQL-backed events and jobs remain authoritative.
- Job workers claim work atomically so replicas cannot perform a side effect twice.
- SSE delivery must fan out authorized events across instances and reconcile from durable server state after reconnect; polling remains the fallback.
- Files use private S3 rather than instance-local storage.
- Instance-local caches never hold authoritative permissions or clinical state.

## Native and offline clients

Future iOS and Android applications use authorization code flow with PKCE and platform-protected credential storage. While online, they present Cognito bearer access tokens to the native-client security adapter. Browser and native adapters call the same versioned application services and apply the same tenant and clinical authorization rules.

Offline capability does not extend the lifetime or authority of an ordinary login token. Offline preparation requires separately designed device enrollment, encrypted scoped local records, local unlock, expiring and revocable offline authorization, idempotent pending operations, synchronization receipts, and explicit conflict handling. Offline signing and prescribing remain behind their separate release gates.

Application and domain services must not depend on `HttpSession`, cookies, servlet request types, or JWT library types. The security adapters translate their credentials into the common authenticated actor.

## Deferred Redis adoption

Do not deploy Redis merely to support a second application instance. PostgreSQL-backed sessions already support horizontal application scaling at the expected pilot size.

Consider Redis later when measurements show a material benefit for one or more of:

- Session read/write load.
- Shared rate limiting.
- Non-authoritative derived caching.
- Cross-instance live-event fan-out.

Redis must not be the source of truth for clinical records, encounter concurrency, command idempotency, audit history, or durable events. A future session-store cutover may deliberately invalidate active JDBC sessions and require users to sign in again instead of migrating session contents.

## Consequences

- The initial deployment avoids Redis infrastructure cost and operational complexity.
- Browser credentials remain outside JavaScript-accessible storage.
- Additional application instances can share sessions through PostgreSQL.
- Native and offline development does not require replacing the browser authentication model.
- Keeping authentication adapters outside application and domain logic is a required architectural boundary, not optional cleanup for the mobile phase.
