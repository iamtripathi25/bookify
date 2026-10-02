# Bookify

A seat reservation service for assigned-seat shows. It is built to stay correct when thousands of buyers hit the same seats at once: no seat is sold twice, no user goes over their limit, and a retried request never books twice.

Java 21, Spring Boot 3.5, PostgreSQL 16. JSON over HTTP; money is always integer paise.

Design reasoning and trade-offs are in [WRITEUP.md](WRITEUP.md).

## Run locally

Requires Docker.

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
| `GET /actuator/health/liveness` | None | 200 | — |
| `GET /actuator/health/readiness` | None | 200 | 503 when the database is unreachable |
| `GET /actuator/prometheus` | None | 200 | — |

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
./mvnw test
```

Integration tests run against a real Postgres 16 in Testcontainers, so Docker must be running.
