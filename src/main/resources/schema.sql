-- Bookify schema. Runs on every startup (spring.sql.init.mode=always), so every statement is
-- IF NOT EXISTS. IF NOT EXISTS never alters an existing table: after editing this file locally,
-- reset the database with `docker compose down -v`. The first post-deploy change moves to Flyway.

CREATE TABLE IF NOT EXISTS shows (
  id              UUID PRIMARY KEY,
  name            TEXT   NOT NULL,
  price_paise     BIGINT NOT NULL CHECK (price_paise >= 0),
  per_user_limit  INT    NOT NULL DEFAULT 4 CHECK (per_user_limit > 0),
  total_seats     INT    NOT NULL CHECK (total_seats > 0),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per seat with exactly one status, so available + held + confirmed == total_seats
-- holds by construction.
CREATE TABLE IF NOT EXISTS seats (
  show_id         UUID NOT NULL REFERENCES shows(id),
  label           TEXT NOT NULL,
  -- Position in the show's seat list; display order only (lock order is by label).
  seat_no         INT  NOT NULL,
  status          TEXT NOT NULL CHECK (status IN ('available','held','confirmed')),
  reservation_id  UUID NULL,
  user_id         TEXT NULL,
  PRIMARY KEY (show_id, label),
  -- A seat can't be owned and available at the same time.
  CHECK ((status = 'available') = (reservation_id IS NULL))
);
CREATE INDEX IF NOT EXISTS seats_reservation_idx ON seats(reservation_id);

CREATE TABLE IF NOT EXISTS reservations (
  id               UUID PRIMARY KEY,
  show_id          UUID NOT NULL REFERENCES shows(id),
  user_id          TEXT NOT NULL,
  seats            TEXT[] NOT NULL,
  amount_paise     BIGINT NOT NULL,
  status           TEXT NOT NULL CHECK (status IN ('confirmed','cancelled')),
  idempotency_key  TEXT NOT NULL,
  -- SHA-256 of show_id + sorted seat labels; same key with a different hash is rejected.
  request_hash     TEXT NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  -- Concurrent retries collapse onto one row.
  UNIQUE (user_id, idempotency_key)
);

CREATE TABLE IF NOT EXISTS user_show_counts (
  show_id  UUID NOT NULL REFERENCES shows(id),
  user_id  TEXT NOT NULL,
  held     INT  NOT NULL CHECK (held >= 0),
  PRIMARY KEY (show_id, user_id)
);
