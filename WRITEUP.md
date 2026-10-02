# Bookify — Write-up

Bookify sells assigned seats under a stampede. Every guarantee (one buyer per seat, a per-user limit, exactly-once retries) is decided inside a single Postgres transaction using row locks, conditional writes and unique constraints. Nothing that matters for correctness lives in application memory. This document explains each mechanism, how it was tested, and what I'd change next.

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

**Timeouts.** Each connection sets `lock_timeout = 3s` and `statement_timeout = 30s`. A lock wait past 3 seconds means heavy contention, and the request gets 409 `CONTENTION`. So does a healthy pool too busy to hand out a connection within 60 seconds. An unreachable database is different: it's a 503 (see the next section). Unexpected errors stay 500 on purpose, so bugs aren't hidden as declines.

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

**Retries after the fact.** Before opening a write transaction, the service looks the key up. A match with the same hash returns the stored reservation in its current state with 200. The lookup and the seat pre-check run in one statement, so they share a snapshot. The reservation commits together with its seats, so if the pre-check saw a seat as taken by this booking, the lookup is guaranteed to see the booking too. A retry of a successful request therefore replays rather than being declined as seat-taken.

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

## Consistency vs availability under a partition

**Bookify chooses consistency.** There is one Postgres primary, and it is the only place a seat can be sold. If the app can't reach it, the app refuses to sell rather than guess. It has no local cache, queue or fallback that could accept a booking optimistically. Accepting a booking it can't make durable would risk selling one seat twice, and an oversold seat is worse than a "try again".

**What happens, measured.** I simulated a partition by freezing the database container, so packets go nowhere. I also stopped it outright, which refuses connections:

| | Frozen (partition) | Stopped |
| --- | --- | --- |
| Readiness | 503 in 2.0s, so the load balancer stops routing here | 503 in 2.0s |
| `POST /reserve` | 503 `DATABASE_UNAVAILABLE`, `Retry-After: 5`, after 35s | 503 in 5ms |
| Liveness | 200: the process is fine, so it isn't restarted | 200 |
| After recovery | readiness 200 within about 1s; same-key retry books normally | same |

Two fixes came out of this test. Readiness took **12 seconds** to fail during a partition, because a connection attempt stalled through several driver timeouts in a row. The check now has a hard 2-second deadline. And a query on a stalled connection could wait **forever**: Postgres's `statement_timeout` can't fire when the server can't reach the client. Connections now have a 35-second socket timeout, just above `statement_timeout`, so it never cuts a legitimate query.

**503 for an outage, 409 for load.** A busy pool on a healthy database is load: 409 `CONTENTION`. A connection failure (SQLSTATE class `08`) or a database shutting down (`57P01`–`57P03`) is an outage: 503 `DATABASE_UNAVAILABLE`. The two are told apart because the pool only attaches a connection error when creating a connection actually failed. Under the burst, the database is up, so the zero-5xx property is unaffected.

**Ambiguous outcomes are what idempotency keys are for.** A partition can hit after the commit but before the response reaches the client, so the client can't know whether it booked. That happened in testing: a request stalled on a frozen connection, the proxy gave up, then the database came back and the commit completed. Retrying with the same key returned 200 with the booking, rather than booking twice or wrongly reporting the seat as taken. Clients should always retry with the same key after a timeout or a 503.

**What stays available.** Nothing that needs the database. In a larger deployment, `GET /shows/{id}` could be served from a read replica with slightly stale counts, which would be AP for reads only. Writes stay on the single primary. For failover without losing confirmed bookings, the primary would replicate synchronously to a standby (RPO 0). That trades a little write latency for never losing a sale.

## Observability

The aim is to see from the outside, while a burst is running, that the service is behaving correctly, not just that it is up.

**Health.** Liveness never touches the database. Readiness runs `SELECT 1` on a separate two-connection pool, so it never waits behind reservation traffic. A hard 2-second deadline makes it fail closed in 2 seconds whether Postgres refuses connections or silently drops packets.

**Metrics that reconcile.** Two kinds of metrics check each other:
- **Counters from the request path:** confirmed, declined by reason, replayed, cancelled. They are incremented only after the transaction has committed, so a rolled-back attempt never counts as confirmed. Every counter for a show is registered at 0 when the show is created. That way Prometheus sees a zero before the first booking, and a burst's first second isn't lost.
- **Gauges from the database:** `bookify_seats{show,status}` and `bookify_show_seats{show}` are read with one `GROUP BY` every second, on the same small pool readiness uses. They keep flowing while the main pool is saturated, and they can't drift from the data because they are the data.

The invariant `available + held + confirmed == total_seats` is a live PromQL expression: `sum by (show) (bookify_seats) - on (show) bookify_show_seats`. It is on the dashboard and has an alert. In a test run, the counter changes matched the HTTP responses exactly: 40 × 201, 80 × 200 and 780 × 409 gave confirmed 40, replayed 80 and `seat_taken` 780, and the confirmed-seat gauge matched `GET /shows/{id}`.

**Counting over a time range.** Prometheus's `increase()` gets bursts wrong in two ways. It treats the first scraped value of a new series as its starting point, and it extrapolates across the window. In testing it reported 0 confirmed for a burst of 180. The dashboard counts with `max_over_time(x[range]) - (x offset range, or 0 for a series born inside the range)` instead, which matched the responses exactly. The remaining gap: increments made in the last few seconds before a crash are never scraped.

**Logs.** One JSON object per line, written through an async appender so requests don't wait on stdout. A filter ahead of Spring Security assigns each request an id. It takes the caller's `X-Request-Id` if it looks safe, otherwise generates one. The id goes on every log line, in the response header and in every error body, including 401s. Each request writes one access-log line with `request_id`, `user_id`, `show_id`, `outcome`, `status` and `latency_ms`, so a grader's failed request can be found from its response alone.

**What would page me at 2am.** These are Prometheus rules in `observability/alerts.yml`:
- **Any 5xx for a minute.** Declines are always 4xx, so a 5xx is a bug or an outage.
- **Seat invariant drift for 30 seconds.** The one thing that must never happen.
- **The app can't be scraped for 30 seconds**, meaning it is down or not ready.
- **Reserve p99 above 2 seconds for 5 minutes.**

Two more raise a ticket rather than a page: requests queueing for a database connection for 5 minutes (the pool is undersized), and sustained `CONTENTION` declines.

**Cardinality.** Metrics carry a `show` label. That's fine for a handful of shows during grading. With thousands of shows, I'd drop the label from counters, keep it only on the seat gauges, and limit those to shows currently on sale.

## Load testing and tuning

`./burst.sh` runs the graders' scenario against any URL: hot-seat storm, a 20,000-request stampede with retries and key reuse, limit storm and spoof checks. It then reconciles every response against the API and the metrics. Locally it passed more than 30 full runs with zero 5xx. Tuning was driven by measurements, and three findings changed the setup:

- **A bigger pool is slower.** With 1,000 requests in flight, pools of 15 and 30 performed about the same, while 45 and 60 were noticeably slower. More connections means more contention inside Postgres, not more work done. The pool stays at 30. Requests queue for a connection (mean wait about 150 ms under the full burst) rather than failing.
- **Measure the right thing.** Server-side timing from the app's own metrics (about 156 ms mean) showed the client's first latency figures (p50 of 2.7 s) were mostly the client queueing for its own in-flight slots. The burst script now times only the HTTP request.
- **The local proxy ran out of ports.** After a few back-to-back bursts, Caddy returned a handful of 502s with `cannot assign requested address`. With its default small idle-connection pool, every burst opened and closed thousands of upstream connections, and the closed ones sat in TIME_WAIT until the container's ephemeral ports ran out. A large keep-alive pool and TIME_WAIT reuse fixed it. Afterwards Caddy reused about 980 connections, with no 502s in more than 30 runs.

One local artefact remains. In about 600,000 requests through Caddy plus Docker Desktop's port forwarding, 3 responses arrived truncated at the client. In each case the server had handled the request correctly and counted it. The truncation didn't reproduce with either hop removed, and the hosted deploy has neither hop. The burst script still counts these as failures rather than hiding them.

The two pre-transaction reads (seat states and the idempotency-key lookup) were also merged into one statement. Throughput barely moved, but it halves the pool checkouts for declined requests, and both reads now come from the same snapshot. That makes the "a booking the seat read saw is also found by key" argument hold by construction.

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
- The observability setup. I chose a local Prometheus and Grafana stack, then asked for logs in Grafana too (Loki), and for exact numbers on the dashboard instead of rounded ones.
- I separated what the graders need from what's nice to have. That led to keeping the Grafana stack local, for watching bursts and making the recording, instead of deploying it.
- After the partition test showed an outage returning 409 `CONTENTION`, I chose to make it a 503.

**Decided by the AI (reviewed by me):**
- Making the readiness pool a plain component rather than a second `DataSource` bean. A second bean would have switched off Spring Boot's auto-configured main pool.
- A `seat_no` column, so seats display in creation order (`A1, A2, A10`) rather than alphabetical order.
- `logstash-logback-encoder` 8.1 rather than 9.0, because 9.0 needs Jackson 3.
- Merging the seat pre-check and the idempotency lookup into one statement.
- The partition fixes: a hard deadline on readiness, a socket timeout on database connections, and treating Postgres shutdown states as "unavailable". All three were found by testing the outage rather than assuming the timeouts worked.
- Loki's configuration for Grafana's Logs Drilldown, after the pattern endpoint returned 404 with the default config.
- Plain HTTP on local Caddy instead of HTTPS, because Java's HTTP client rejects Caddy's self-signed certificate.
- The time-range formula on the dashboard. Prometheus's `increase()` misreported bursts on new series, so the AI tested alternatives against known response counts until the numbers matched exactly.
- Race tests that call the write transaction directly, not just over HTTP. Over HTTP, the pre-check turns most losers away before they reach the row locks, so the HTTP tests alone would barely exercise the locking.
- The load-test diagnosis: comparing client latency with the app's server-side metrics, finding Caddy's port exhaustion from its error logs, and isolating the rare truncated responses by removing one network hop at a time.

## What I'd do next

**Product**
- **A timed hold and a payment step:** hold for, say, 10 minutes, then confirm on payment. The schema already has the `held` status. This adds a sweeper that runs the cancel transaction on expired holds, and a confirm step that must lose cleanly to it.
- **Seat maps and adjacent seats.** "Two seats together" needs one transaction that picks and locks a block, not two independent requests.

**Reliability**
- **A circuit breaker for outages.** Today a request during a partition waits up to 35–60 seconds before its 503. A breaker could reject immediately once the database is known to be down. It needs care: a breaker that trips under burst load would itself cause 5xx.
- **Per-user rate limiting at the edge**, so one client can't take most of the database pool during an on-sale.
- **Schema migrations with Flyway.** `schema.sql` with `IF NOT EXISTS` was right for one schema version; the first change after deploy is the moment to switch.
- **Clean up idempotency keys** after a retention window, for example by archiving reservations older than the show.

**Scale and deployment**
- **ECS Fargate or Kubernetes behind a load balancer, with RDS Multi-AZ.** Several app instances (the app is stateless), synchronous replication to a standby, and read replicas for `GET /shows`.
- **Partition the `seats` table by show** once there are many shows, so each on-sale touches its own index.
- **An outbox for downstream events** (booking confirmed or cancelled), written in the same transaction, for payments, notifications and analytics.
- **Load tests on production-shaped infrastructure**, from more than one client machine, to find the real limits rather than a laptop's.

**Security**
- **A real identity provider** (Auth0, Keycloak, Cognito) with RS256/JWKS, instead of the test-token endpoint.
- **Drop the `show` label from counters** once there are many shows, keeping it only on the seat gauges for shows on sale.
