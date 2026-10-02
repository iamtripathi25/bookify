# Bookify

A seat reservation service for assigned-seat shows. It is built to stay correct when thousands of buyers hit the same seats at once: no seat is sold twice, no user goes over their limit, and a retried request never books twice.

Java 21, Spring Boot 3.5, PostgreSQL. JSON over HTTP; money is always integer paise.

**Live deployment:** [details below](#live-deployment). The URL, the admin key and a screen recording of the live burst are shared with the submission.

Design reasoning, trade-offs and test results are in [WRITEUP.md](WRITEUP.md).

**Quick start** (Docker and JDK 21+):

```bash
docker compose up --build -d          # Postgres, app and proxy on http://localhost:8080
./burst.sh http://localhost:8080      # 20k-request on-sale stampede, checked end to end
```

## Live deployment

| | |
| --- | --- |
| Base URL | Shared with the submission (`<live-url>` below) |
| Liveness | `<live-url>/actuator/health/liveness` |
| Readiness (checks the database) | `<live-url>/actuator/health/readiness` |
| Prometheus metrics | `<live-url>/actuator/prometheus` |

**Hosting.** Railway runs two services: the app, built from this repo's `Dockerfile` on every push to `main`, and a Postgres database reachable only over Railway's private network. Railway routes traffic to a new deployment only after `/actuator/health/readiness` passes, and restarts the app if it exits (`railway.json`). Railway's managed Postgres is version 18; tests and the local stack use 16, and the schema and SQL are standard across both.

**Cold start.** About 3 seconds from container start to serving (2.96 s and 3.25 s measured). On first boot, `schema.sql` creates the tables; later boots leave them unchanged. Data survives app restarts and redeploys, and the app recovers on its own after a database restart.

**Tokens on the live service.**
- User tokens: open, exactly as locally (`POST /auth/token` with a `user_id`).
- Admin tokens (needed to create a show): need the admin key. It isn't in this repo; it's shared privately with the submission, along with the URL.

**Run the burst against it:**

```bash
BOOKIFY_ADMIN_KEY=<admin key> ./burst.sh <live-url>
```

**Live burst results** (the recorded run, 2 October 2026, from a laptop in India to Railway):

| Check | Result |
| --- | --- |
| Hot seat A12, 500 users at once | 1 × 201, 499 × 409 `SEAT_TAKEN` |
| Stampede of about 20,000 requests | each of the 10 hot seats sold to exactly one buyer |
| Confirmed | 715 (713 in the burst + 2 spoof-check bookings) |
| Declined | 19,695: 19,626 `SEAT_TAKEN`, 63 `IDEMPOTENCY_KEY_REUSED`, 6 `PER_USER_LIMIT` |
| Idempotent replays (200) | 101 |
| 5xx, contention declines, invariant drift | 0, 0, 0 |
| Metrics vs responses | every counter and gauge equal to the response counts |
| Database pool | peak 235 requests queued for 30 connections; mean wait 81 ms |
| Reserve p99, server-side | 653 ms |

A practice run from the same laptop shows the client-side view: about 1,470 requests/s, p50 324 ms, p95 965 ms, p99 1.5 s. Every request crosses the internet to Railway's region, which accounts for most of the difference from local figures.

**Logs.** Railway's logs aren't public, so the screen recording shared with the submission shows them under load: the burst script running to `PASS`, the Grafana dashboard watching the live service, and Railway's live log stream side by side. Railway accepts at most 500 log lines per second per service, and a burst writes about 1,500 access lines a second, so some routine lines (mostly `SEAT_TAKEN`) are dropped from Railway's log view during the peak; Railway notes how many. The metrics, and the burst script's reconciliation against them, are the exact record.

## Run locally

Requires Docker. The burst script and the tests also need JDK 21 or newer.

```bash
docker compose up --build
```

This starts Postgres 16, the app and a Caddy reverse proxy. The service is on **http://localhost:8080** and is ready when:

```bash
curl -s localhost:8080/actuator/health/readiness
# {"status":"UP"}
```

No `.env` file is needed for local runs; compose has working defaults. To override them, copy `.env.example` to `.env`.

To reset the database (needed after a schema change): `docker compose down -v`.

## Get a token

Every API call needs a bearer token. While `BOOKIFY_DEV_AUTH_ENABLED=true`, the service mints test tokens.

**User token**, open to anyone:

```bash
curl -s -X POST localhost:8080/auth/token \
  -H 'content-type: application/json' \
  -d '{"user_id": "u-1"}'
```

**Admin token**, needed to create shows. Requires the admin key in the `X-Admin-Key` header (local default: `local-dev-admin-key`):

```bash
curl -s -X POST localhost:8080/auth/token \
  -H 'content-type: application/json' \
  -H 'X-Admin-Key: local-dev-admin-key' \
  -d '{"user_id": "admin", "role": "ADMIN"}'
```

Response:

```json
{ "token": "eyJ...", "user_id": "u-1", "role": "USER", "expires_in": 3600 }
```

Tokens last one hour. The acting user is always the token's subject; any `user_id` sent in a request body is ignored.

## API

| Method and path | Auth | Success | Errors |
| --- | --- | --- | --- |
| `POST /auth/token` | None (admin role needs `X-Admin-Key`) | 200 token | 400, 403; 404 when dev auth is disabled |
| `POST /shows` | Admin | 201 show, every seat `available` | 400, 401, 403 |
| `GET /shows/{id}` | Any user | 200 seat statuses and counts | 400 (id not a UUID), 401, 404 |
| `POST /shows/{id}/reserve` | Any user | 201 new reservation; **200 replay** of an earlier request with the same key | 400, 401, 404, 409, 503 |
| `POST /reservations/{id}/cancel` | Owner | 200 cancelled reservation; cancelling again is a no-op 200 | 400, 401, 404 |
| `GET /reservations/{id}` | Owner | 200 reservation in its current state | 400, 401, 404 |
| `GET /actuator/health/liveness` | None | 200 | — |
| `GET /actuator/health/readiness` | None | 200 | 503 when the database is unreachable |
| `GET /actuator/prometheus` | None | 200 | — |

Any endpoint that reads the database returns 503 `DATABASE_UNAVAILABLE` if Postgres can't be reached.

### Create a show

```bash
curl -s -X POST localhost:8080/shows \
  -H "authorization: Bearer $ADMIN_TOKEN" \
  -H 'content-type: application/json' \
  -d '{"name": "friday-night", "seats": ["A1", "A2", "A3"], "price_paise": 25000, "per_user_limit": 4}'
```

| Field | Rules |
| --- | --- |
| `name` | Required, up to 200 characters |
| `seats` | Required, 1–10,000 labels, no duplicates, each up to 32 characters |
| `price_paise` | Required, integer ≥ 0 |
| `per_user_limit` | Optional, integer ≥ 1, default 4 |

### Show state

```bash
curl -s localhost:8080/shows/$SHOW_ID -H "authorization: Bearer $TOKEN"
```

```json
{
  "id": "266ef3c4-…",
  "name": "friday-night",
  "price_paise": 25000,
  "per_user_limit": 4,
  "total_seats": 3,
  "counts": { "available": 3, "held": 0, "confirmed": 0 },
  "seats": [ { "label": "A1", "status": "available" }, … ]
}
```

`available + held + confirmed == total_seats` always holds. Seats are listed in the order they were created.

### Reserve seats

```bash
curl -s -X POST localhost:8080/shows/$SHOW_ID/reserve \
  -H "authorization: Bearer $TOKEN" \
  -H 'content-type: application/json' \
  -H 'Idempotency-Key: 7b1e4c2a-…' \
  -d '{"seats": ["A12", "A13"]}'
```

```json
{
  "reservation_id": "…",
  "show_id": "…",
  "user_id": "u-1",
  "seats": ["A12", "A13"],
  "amount_paise": 50000,
  "status": "confirmed"
}
```

- **All-or-nothing.** If any requested seat is unavailable, nothing is booked and the response is 409 `SEAT_TAKEN`, listing the unavailable seats in `seats`.
- **Idempotency key is required.** Send it in the `Idempotency-Key` header or as `idempotency_key` in the body; the header wins if both are present. Up to 128 characters. Keys are scoped per user.
- **Retries are safe.** Repeating a request with the same key returns **200, not 201**, with the original reservation in its current state. Nothing is booked twice. Seat order doesn't matter: `["A2","A1"]` is the same request as `["A1","A2"]`.
- **A key belongs to one request.** Reusing a key with different seats, or for a different show, is 409 `IDEMPOTENCY_KEY_REUSED`, and nothing changes. A declined request doesn't use up its key; a retry with the same key tries again.
- **Seats:** at least one, no duplicates, every label must exist in the show (unknown labels are a 400).
- **Per-user limit:** a user can hold at most the show's `per_user_limit` seats in total, across all their reservations for that show. A request that would go over is 409 `PER_USER_LIMIT`.
- **Identity comes from the token.** A `user_id` in the body is ignored.
- `amount_paise` is the show's price times the number of seats.

### Cancel a reservation

```bash
curl -s -X POST localhost:8080/reservations/$RESERVATION_ID/cancel -H "authorization: Bearer $TOKEN"
```

Returns the reservation with `"status": "cancelled"`.
- Only the owner can cancel or read a reservation. For anyone else it's a 404, the same as a reservation that doesn't exist.
- Seats are freed immediately and can be booked by anyone. They also go back to the owner's per-user limit.
- Retrying the original reserve request with its key after a cancel returns the cancelled reservation (200). It never books again; use a new key to book again.

### Errors

Every error has the same shape:

```json
{ "code": "INVALID_REQUEST", "message": "Invalid id: 'abc'", "request_id": "…" }
```

| Status | Codes |
| --- | --- |
| 400 | `INVALID_REQUEST` |
| 401 | `UNAUTHORIZED` |
| 403 | `FORBIDDEN` |
| 404 | `NOT_FOUND` |
| 409 | `SEAT_TAKEN`, `PER_USER_LIMIT`, `IDEMPOTENCY_KEY_REUSED`, `CONTENTION` (the database was up but too busy to decide in time; safe to retry) |
| 503 | `DATABASE_UNAVAILABLE`, with a `Retry-After` header: Postgres can't be reached. Nothing was booked; retry with the same idempotency key |

Declines are always 4xx. A 503 means the database is down or unreachable; a 500 means a bug.

**Counting outcomes in a load test:** a seat raced by many users gets exactly one 201. If the winner retries, those retries are 200s, not extra 201s.

## Burst test

One command reproduces an on-sale stampede against any deployment and checks every correctness property from the outside. It needs only JDK 21+; there is no build step.

```bash
./burst.sh http://localhost:8080                               # local (compose)
BOOKIFY_ADMIN_KEY=<admin key> ./burst.sh https://<live-url>    # deployed
```

It runs these steps against a fresh show of 1,000 seats (price 25000 paise, limit 4):

1. **Tokens:** mints an admin and 2,000 users.
2. **Hot-seat storm:** 500 users race for seat A12 at once.
3. **Stampede:** about 20,000 requests, 80% aimed at 10 hot seats. 10% are retried with the same key and 2% reuse a key for a different seat. `GET /shows/{id}` is polled every 200ms throughout to check the invariant while it runs.
4. **Limit storm:** one user sends 10 parallel requests on the limit-4 show.
5. **Spoof checks:** a body `user_id` is ignored; another user's reservation can't be cancelled or read.
6. **Report:** prints the outcome table and latency percentiles.
7. **Reconciliation** against `GET /shows/{id}` and `/actuator/prometheus`:
   - exactly one winner per hot seat
   - zero 5xx
   - the invariant holds
   - confirmed seats equal the seats in 201 responses
   - every metric equals the matching response count

It exits 1 if any check fails.

| Option | Default | Meaning |
| --- | --- | --- |
| `--requests N` | 20000 | Stampede size |
| `--users N` | 2000 | Distinct users |
| `--hot-storm N` | 500 | Users racing for one seat |
| `--concurrency N` | 1000 | Maximum requests in flight |
| `--admin-key KEY` | `$BOOKIFY_ADMIN_KEY`, else the local default | Needed to create the show |
| `--seed N` | random | Repeat a run exactly |

Sample output (local compose stack, trimmed):

```
== Hot-seat storm: 500 users -> A12 ======================================
  201                                1    0.2%  confirmed (new booking)
  409 SEAT_TAKEN                   499   99.8%
  ok  hot seat A12 has exactly one 201 (got 1)

== All reserve requests ==================================================
  200                               87    0.4%  idempotent replay (same key retried)
  201                              713    3.5%  confirmed (new booking)
  409 IDEMPOTENCY_KEY_REUSED        64    0.3%
  409 PER_USER_LIMIT                 6    0.0%
  409 SEAT_TAKEN                 19589   95.7%
  total                          20459
reserve latency (per request, at --concurrency in flight): p50=253.7ms p95=963.2ms p99=1505.9ms

== Reconciliation ========================================================
GET /shows: total=1000 available=285 held=0 confirmed=715
  ok  available + held + confirmed == total_seats
  ok  API confirmed (715) == seats in 201 responses (715)
  ok  each stampede hot seat has at most one winner (10 of 10 sold)
  ok  zero 5xx responses (got 0)
  confirmed                          metric    715   responses    715   ok
  replayed                           metric     87   responses     87   ok
  declined:seat_taken                metric  19589   responses  19589   ok
  ...
== PASS ==================================================================
```

**Local figures** (MacBook, Docker Desktop with 10 CPUs; the burst client runs on the same machine, through Caddy):
- About 3,400–4,900 reserve requests/s.
- With 1,000 requests in flight: typical p50 about 200–250 ms, p95 about 570–960 ms, p99 about 0.8–1.5 s.
- Zero 5xx over more than 30 full runs.
- With 200 in flight: p50 about 25 ms, p99 about 85 ms.

Latency at high concurrency is queueing for a database connection, by design: requests wait their turn instead of being rejected.

## Metrics, logs and alerts

### Metrics

The app exposes Prometheus metrics at `/actuator/prometheus` (public, no token).

| Metric | Type | Meaning |
| --- | --- | --- |
| `bookify_reservations_confirmed_total{show}` | Counter | New reservations (each was a 201) |
| `bookify_seats_confirmed_total{show}` | Counter | Seats booked by those reservations |
| `bookify_reservations_declined_total{show,reason}` | Counter | Reserve requests that booked nothing; `reason` is `seat_taken`, `per_user_limit`, `idempotent_replay`, `idempotency_mismatch` or `contention` |
| `bookify_reservations_replayed_total{show}` | Counter | Retries answered with the original reservation (each was a 200) |
| `bookify_reservations_cancelled_total{show}`, `bookify_seats_released_total{show}` | Counter | Cancels and the seats they freed |
| `bookify_seats{show,status}` | Gauge | Seats per status, read from the database every second |
| `bookify_show_seats{show}` | Gauge | Total seats per show |
| `http_server_requests_seconds_*` | Histogram | Request counts, status codes and latency per endpoint |
| `hikaricp_connections_*{pool="main"}` | Gauge | Database pool usage and queueing |

How they reconcile:
- `sum by (show) (bookify_seats) == bookify_show_seats`, always.
- `bookify_seats{status="confirmed"}` equals `confirmed` in `GET /shows/{id}`.
- Counter changes during a run equal the responses: 201s = confirmed, 200s = replayed, 409s = declines by reason.

Invalid requests (400) and unknown shows (404) are not declines. Counters reset when the app restarts; the seat gauges come from the database and don't.

### Dashboard

An optional observability stack comes with a ready-made dashboard: Prometheus for metrics, Loki for logs (fed by Grafana Alloy, which reads the app container's output), and Grafana.

```bash
docker compose --profile observability up --build
```

| What | Where |
| --- | --- |
| Grafana dashboard (no login) | http://localhost:3000/d/bookify |
| Prometheus and its alerts | http://localhost:9090/alerts |
| Logs Drilldown (browse logs, no query needed) | http://localhost:3000/a/grafana-lokiexplore-app/explore |
| Logs in Grafana Explore (Loki queries) | http://localhost:3000/explore |
| Raw metrics from the app | http://localhost:8080/actuator/prometheus |

The dashboard has three rows:
- **Reservations:**
  - seat invariant drift, which must be 0
  - confirmed, declined, replayed and contention counts
  - outcomes per second
  - seats by status
  - declines by reason
- **Traffic:** the 5xx count (must be 0), reserve responses by status, requests per endpoint.
- **Latency, database pool and JVM:** p50/p95/p99, pool usage and connection waits, heap.
- **Logs:** log lines by outcome, warnings and errors, and the access log. Paste an id into the **Request id** box at the top to see one request.

Use the **Show** selector at the top to focus on one show. Prometheus scrapes every 5 seconds.

**Watching a deployed instance.** The local stack can also scrape a deployment:

```bash
observability/watch-live.sh https://<live-url>     # start; --off to stop
```

Then pick **Environment = live** at the top of the dashboard. The metrics panels switch to the deployed service. The Logs row always shows the local stack; for the deployed service's logs, use Railway's log view (service → Deploy Logs).

### Logs

Every log line is one JSON object. Each request gets an id: send `X-Request-Id` to set it, or one is generated. It comes back in the `X-Request-Id` response header and in every error body. Each request writes one access-log line:

```json
{"ts":"2026-10-02T08:31:14.964Z","level":"INFO","logger_name":"bookify.access",
 "request_id":"7b356a69-…","user_id":"u3","show_id":"2538ce8e-…","outcome":"confirmed",
 "method":"POST","path":"/shows/2538ce8e-…/reserve","status":201,"latency_ms":27}
```

`outcome` is what the request ended as: `confirmed`, `idempotent_replay`, `seat_taken`, `per_user_limit`, `idempotency_mismatch`, `contention`, `database_unavailable`, `cancelled`, `invalid_request`, `not_found`, `unauthorized`, and so on. Health checks and metric scrapes aren't logged.

In Grafana (with the observability profile), query Loki in Explore. A few examples:

```
{service="app", logger="bookify.access"}                  # every request
{service="app", level=~"WARN|ERROR"}                      # problems only
{service="app"} | json | request_id="<id>"                # one request
{service="app", outcome="seat_taken"} | json | user_id="u-42"
```

`level`, `outcome` and `logger` are Loki labels. Other fields are filtered with `| json`.

Without the observability stack:

```bash
docker compose logs -f app                                            # follow
docker compose logs app --no-log-prefix | grep '"request_id":"<id>"'  # one request
```

### Alerts

`observability/alerts.yml` defines what would page someone. The local Prometheus loads it.

| Alert | Fires when | Severity |
| --- | --- | --- |
| `ServerErrors` | Any 5xx for 1 minute | Page |
| `SeatInvariantDrift` | Seat counts stop adding up to the total for 30s | Page |
| `AppDown` | Prometheus can't scrape the app for 30s | Page |
| `ReserveLatencyHigh` | Reserve p99 above 2s for 5 minutes | Page |
| `DbPoolQueueing` | Requests waiting for a database connection for 5 minutes | Ticket |
| `ContentionDeclines` | `CONTENTION` declines for 5 minutes | Ticket |

## Configuration

All configuration comes from environment variables.

| Variable | Purpose | Local default |
| --- | --- | --- |
| `DB_URL` | JDBC URL | `jdbc:postgresql://postgres:5432/bookify` |
| `DB_USER`, `DB_PASSWORD` | Database credentials | `bookify` / `bookify` |
| `BOOKIFY_JWT_SECRET` | HS256 signing key, at least 32 bytes | A local-only value |
| `BOOKIFY_DEV_AUTH_ENABLED` | Enables `POST /auth/token` | `true` in compose, `false` otherwise |
| `BOOKIFY_ADMIN_KEY` | Required to mint admin tokens, at least 16 bytes | `local-dev-admin-key` |

## Tests

```bash
./mvnw verify      # everything, including the concurrency suite (ConcurrencyIT)
./mvnw test        # unit and API tests only
```

Tests run against a real Postgres 16 in Testcontainers, so Docker must be running. The concurrency suite races hundreds of requests over HTTP: hot seats, opposing seat orders, idempotency storms, limit storms, cancel-and-rebook and spoofing. After each test it checks the invariants across every show.
