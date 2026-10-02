# Bookify — Write-up

## The atomic decision

Postgres decides who gets a seat, inside one READ COMMITTED transaction. Nothing is decided in application memory, so any number of app instances can run behind a load balancer.

**The mechanism.** For a request for seats `S`, the transaction:

1. Claims the idempotency key by inserting the reservation row (see Idempotency).
2. Claims the user's quota for the show (see Per-user limit).
3. Locks the seats: `SELECT label, status FROM seats WHERE show_id = ? AND label = ANY(S) ORDER BY label FOR UPDATE`.
4. Declines (rolls back) if any locked seat is not `available`.
5. Runs a guarded write, `UPDATE seats SET status = 'confirmed', reservation_id = ?, user_id = ? WHERE show_id = ? AND label = ANY(S) AND status = 'available'`, and declines if fewer than `|S|` rows changed.

All three claims (key, quota, seats) commit together or not at all.

**Why it's race-free.** When 500 transactions want seat A12, they queue on A12's row lock. The first commits `confirmed`. Under READ COMMITTED, each waiter's `FOR UPDATE` then returns the latest committed version of the row, sees `confirmed`, and rolls back with 409 `SEAT_TAKEN`. There is no window between checking and taking a seat, because both happen while holding the row lock. The `status = 'available'` guard on the `UPDATE` is defence in depth: even without the lock, a second writer could not overwrite a confirmed seat.

The schema backs this up. A seat is one row keyed by `(show_id, label)`, so there is no second copy to sell. A `CHECK` ties `status = 'available'` to `reservation_id IS NULL`, so a seat can't be both owned and available. Every seat has exactly one status, so `available + held + confirmed == total_seats` holds by construction.

**Multi-seat requests and deadlock.** Every transaction takes its locks in one global order: the reservation's key, then the user's quota row, then seats in ascending label order. Postgres takes `FOR UPDATE` locks as the sorted rows are produced, so `[A13, A12]` and `[A12, A13]` both lock A12 first. The second waits on A12 rather than holding A13, so a cycle can't form. As a safety net, a deadlock (`40P01`) or serialization failure (`40001`) is retried up to three times in a fresh transaction, with 10–50ms of random delay. If it still fails, the response is 409 `CONTENTION`, never a 5xx.

**Partial requests are all-or-nothing.** If any seat is unavailable, the whole transaction rolls back and the response lists the unavailable seats.

**A cheap pre-check keeps the pool free.** Before opening the write transaction, the service reads the requested seats without locking. If any is already gone, it declines straight away. During a hot-seat storm, almost every loser is turned away after one short read and never waits for a row lock. The pre-check only ever declines; the decision to sell is always made under the lock.

**Timeouts.** Each connection sets `lock_timeout = 3s` and `statement_timeout = 30s`. A lock wait past 3 seconds means heavy contention, and the request gets 409 `CONTENTION`. Pool exhaustion maps to the same code. Unexpected errors stay 500 on purpose, so bugs aren't hidden as declines.

**How it's tested.** Integration tests run against real Postgres 16 over HTTP:
- 500 users race for one seat: exactly one 201, and every loser gets 409 `SEAT_TAKEN`.
- 200 pairs race with `[A1, A2]` against `[A2, A1]`: exactly one winner, and every loser is `SEAT_TAKEN`. No deadlock ever surfaces as `CONTENTION`.
- Two more tests call the write transaction directly, so all 200 racers reach the row locks. One seat gets exactly one winner. Four seats requested in four different orders get exactly one winner and no deadlock.
- A mixed stampede: 1,000 requests from 200 users, 80% aimed at five hot seats, with same-key retries and key reuse. Responses are only 200, 201 or 409; no seat appears in two 201s; the API's confirmed count equals the number of 201s; and every 200 replays a reservation some 201 created.
- After every test, the suite checks invariants across every show in the database: seat counts sum to the total, every owned seat belongs to a confirmed reservation of the same user, and each user's quota count equals the seats they own.

**Checking that the tests can fail.** I removed `FOR UPDATE` and the `status = 'available'` guard, and eight of the race tests failed, including both hot-seat tests and the stampede reconciliation. With the locking restored, the suite passed five runs in a row.

## Idempotency

**Where the key lives.** In the `reservations` table itself, under `UNIQUE (user_id, idempotency_key)`, next to a `request_hash`: the SHA-256 of the show id and the sorted seat labels. Keys are scoped per user, so two users can't collide, and one user can't reach another's reservation by guessing a key. Storing the key in the same row as the reservation means the key, the booking and the seats are written in one transaction. There is no separate idempotency store that could disagree with the bookings.

**How exactly-once is enforced.** The first statement of the write transaction claims the key:

```sql
INSERT INTO reservations (...) VALUES (...)
ON CONFLICT (user_id, idempotency_key) DO NOTHING
RETURNING id
```

If two requests with the same key arrive together, the unique index lets one insert proceed. The other blocks until the first transaction ends:
- **The first committed:** the second inserts nothing, reads the committed row, and returns it as a replay (200).
- **The first rolled back** (for example, the seat was taken): the second's insert goes ahead and it makes its own attempt.

`ON CONFLICT` matters here. Catching a duplicate-key error instead wouldn't work: by the time the error arrives, Postgres has aborted the transaction.

**Retries after the fact.** Before opening a write transaction, the service looks the key up. A match with the same hash returns the stored reservation in its current state with 200. That lookup deliberately runs after the seat pre-check: the reservation commits together with its seats, so if the pre-check saw a seat as taken by this booking, the lookup is guaranteed to see the booking too. A retry of a successful request therefore replays rather than being declined as seat-taken.

**Same key, different body.** A different hash (other seats, or another show) is 409 `IDEMPOTENCY_KEY_REUSED`, whether it's detected by the lookup or after waiting on the unique index. Nothing changes.

**Declines aren't stored.** A declined attempt rolls back its reservation row along with everything else, so only successful bookings occupy a key. A client that retries after a 409 `SEAT_TAKEN` gets a fresh attempt, not a cached decline. That is the right behaviour for seats that may have been released in the meantime.

**Why replay is 200.** Replays return 200 so that load tests can tell a retry from a new booking. Each raced seat has exactly one 201, however often its winner retries.

**How it's tested.**
- 20 parallel requests with one key: one 201, nineteen 200s, one reservation row, and every response carries the same `reservation_id`.
- 20 parallel requests sharing a key but split between two seats: one booking. Only the winner's own body replays; the rest are 409 `IDEMPOTENCY_KEY_REUSED`.
- Sequential tests cover seat order, key reuse across shows, per-user key scope, and a declined attempt freeing its key.

## Per-user limit

Each `(show, user)` pair has a row in `user_show_counts`. The second statement of the write transaction claims the quota:

```sql
INSERT INTO user_show_counts (show_id, user_id, held) VALUES (?, ?, n)
ON CONFLICT (show_id, user_id) DO UPDATE
  SET held = user_show_counts.held + EXCLUDED.held
  WHERE user_show_counts.held + EXCLUDED.held <= limit
RETURNING held
```

`ON CONFLICT DO UPDATE` takes the counter's row lock and re-checks the `WHERE` against the latest committed value. Parallel requests from one user are therefore serialised on that row, and the total can never pass the limit. If no row comes back, the transaction rolls back with 409 `PER_USER_LIMIT`. A `CHECK (held >= 0)` keeps the counter from going negative.

A seat decline rolls the quota claim back with everything else, so a lost race never uses up a user's allowance.

Tested with one user firing 10 parallel single-seat requests at a limit of 4: exactly four 201s and six 409 `PER_USER_LIMIT`. Six parallel two-seat requests give exactly two 201s. After every test, each user's counter must equal the seats they actually own.

## Holds and expiry

**The model: confirm on reserve, explicit cancel.** A successful reserve is `confirmed` immediately; there is no timed hold. Seats are released by `POST /reservations/{id}/cancel`, which only the owner can call. I chose this over a timed hold because it has one state path to make race-free instead of two. A timed hold needs a sweeper that expires holds while reserves and cancels are running, and a confirm step that races the sweeper.

**Cancel uses the same lock order as reserve.** In one transaction:

1. `SELECT ... FROM reservations WHERE id = ? AND user_id = ? FOR UPDATE`. No row is a 404, whether the reservation doesn't exist or belongs to someone else, so ids can't be probed. Already `cancelled` returns 200 with no changes.
2. Return the seats to the user's quota: `UPDATE user_show_counts SET held = held - n`.
3. Lock the reservation's seats in label order, then free them: `UPDATE seats SET status = 'available', reservation_id = NULL, user_id = NULL WHERE reservation_id = ?`.
4. Mark the reservation `cancelled`.

Reservation row, then user counter, then seats by label: the same order reserve uses, so a cancel and a reserve can't deadlock each other.

**A release can never free someone else's seat.** Step 3 is guarded by `reservation_id`, not by seat label, so it only touches seats this reservation owns at that moment. Once a freed seat is rebooked, it carries the new reservation's id, and a stale or repeated cancel of the old reservation can't reach it. Concurrent cancels of one reservation queue on its row lock. The first does the work; the rest see `cancelled` and return it unchanged, so the quota is returned exactly once.

**A cancelled booking stays cancelled.** Retrying the original reserve request with its key returns the cancelled reservation (200) and never books again, because the key still belongs to that reservation.

**How it's tested.**
- Cancel, then 50 users race for the freed seat: exactly one new winner, and the old reservation stays cancelled.
- A cancel fired at the same instant as 50 rebook attempts: at most one winner, never a double sale.
- Ten cancels and ten same-key retries of one reservation at once: all 200 with the same reservation, one row, and the quota back to exactly 0.
- Sequential tests cover owner-only read and cancel, a repeat cancel as a no-op, and the quota returning after a cancel.

**Adding a timed hold later.** The schema already has the `held` status and counts it in the show state. A hold would add `held_until` to reservations and a scheduled sweeper that runs this same cancel transaction for expired holds. Reserve would then also accept expired holds as available when it locks the seats.

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
- Race tests that call the write transaction directly, not just over HTTP. Over HTTP, the pre-check turns most losers away before they reach the row locks, so the HTTP tests alone would barely exercise the locking.
