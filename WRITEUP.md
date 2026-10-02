# Bookify — Write-up

## Holds and expiry

A reservation is confirmed immediately; there is no timed hold. Seats are released by an explicit `POST /reservations/{id}/cancel`, which only the reservation's owner can call. The `held` status and count are kept in the schema and the show counts so a time-boxed hold can be added later without migrating data.

## Health checks

Liveness never touches the database. Readiness runs `SELECT 1` on a separate two-connection pool with 2-second timeouts, so it never waits behind reservation traffic and fails closed within about 2 seconds when Postgres is unreachable.

## Auth and the test-token endpoint

**Identity.** The acting user is always the JWT `sub` claim. Request bodies have no `user_id` field, and unknown JSON fields are ignored, so a spoofed `user_id` in a body has no effect.

**Verification.** Every request's token is checked for an HS256 signature, expiry, issuer (`bookify`) and audience (`bookify-api`). A token signed with the right key but meant for another issuer or audience is rejected. The `roles` claim maps to Spring authorities; only `ADMIN` can create shows.

**The trade-off.** There is no identity provider, so the service mints its own test tokens through `POST /auth/token`. Graders need to act as thousands of users, so:

- `USER` tokens are open to anyone. Anyone who can reach the service can act as any user, which is acceptable for a grading environment and not for production.
- `ADMIN` tokens require an `X-Admin-Key` header matching `BOOKIFY_ADMIN_KEY`. The key is shared privately, compared in constant time, and admin minting is off entirely when it is blank.
- The endpoint exists only when `BOOKIFY_DEV_AUTH_ENABLED=true`. It defaults to off, and when off the route returns 404.
- Tokens last one hour.

**In production** the service would not issue tokens at all. An identity provider (Auth0, Keycloak, Cognito) would issue them after a real login, signed with an asymmetric key (RS256 or ES256). Bookify would verify them against the provider's public JWKS, so a compromised instance could verify tokens but never forge them. Admin would be a role assigned in the provider behind MFA, or a narrower scope such as `shows:write`.

## AI usage

I used Claude Code (Claude Opus) throughout. The split:

**Directed (I decided, AI implemented):**
- The design doc and the step-by-step implementation plan are mine. The AI implemented them one step at a time, and I reviewed each step before committing it.
- Spring Boot 3.5 instead of the 4.x default. When the version change broke dependencies, I had the AI map the Boot 4 starter names back to their 3.5 equivalents.
- I questioned the open admin-token minting from the original plan. After asking for the security best practice, I chose the tightened version: open `USER` minting, admin gated by a private key, `iss`/`aud` validation and a one-hour token lifetime.
- I keep control of the git history: the AI never stages, commits or pushes.

**Decided by the AI (reviewed by me):**
- Making the readiness pool a plain component rather than a second `DataSource` bean. A second bean would have switched off Spring Boot's auto-configured main pool.
- A `seat_no` column, so seats display in creation order (`A1, A2, A10`) rather than alphabetical order.
- `logstash-logback-encoder` 8.1 rather than 9.0, because 9.0 needs Jackson 3.
- Plain HTTP on local Caddy instead of HTTPS, because Java's HTTP client rejects Caddy's self-signed certificate.
