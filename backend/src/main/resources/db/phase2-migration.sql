-- =====================================================================
-- YatraRail — Phase 2 reservation engine: schema migration
-- =====================================================================
-- Phase 2 adds a real seat-hold table plus the columns the reservation
-- engine depends on. A brand-new database created by `ddl-auto=update`
-- (or created from `schema.sql`) already has all of this, so this script
-- is only needed when upgrading an EXISTING Phase 1 database.
--
-- It is written to be idempotent: every statement is guarded with
-- `IF NOT EXISTS`, so running it twice is harmless. Run it once, as
-- postgres, against the yatrarail database, BEFORE starting the app
-- with `spring.jpa.hibernate.ddl-auto=validate`.
--
--   psql -U postgres -d yatrarail -f backend/src/main/resources/db/phase2-migration.sql
--
-- Any booking whose passenger_status is already null is a legacy Phase 1
-- row; those are backfilled to CONFIRMED (or WAITLISTED when no seat was
-- ever assigned) so that the new NOT NULL-free queue columns are coherent.
-- =====================================================================

BEGIN;

-- ---------------------------------------------------------------------
-- 1. coaches.rac_capacity — how many RAC entries a coach can carry
-- ---------------------------------------------------------------------
ALTER TABLE coaches ADD COLUMN IF NOT EXISTS rac_capacity integer;
UPDATE coaches SET rac_capacity = 2 WHERE rac_capacity IS NULL;
-- Chair car and second seating do not offer RAC in this system.
UPDATE coaches SET rac_capacity = 0 WHERE seat_class IN ('CHAIR_CAR', 'SECOND_SEATING');
ALTER TABLE coaches ALTER COLUMN rac_capacity SET DEFAULT 2;
ALTER TABLE coaches ALTER COLUMN rac_capacity SET NOT NULL;

-- ---------------------------------------------------------------------
-- 2. booking_passengers.passenger_status — CONFIRMED | RAC | WAITLISTED
--    | CANCELLED | EXPIRED
-- ---------------------------------------------------------------------
ALTER TABLE booking_passengers ADD COLUMN IF NOT EXISTS passenger_status varchar(20);

-- Backfill: a passenger holding a seat was confirmed; one without a seat
-- was on the waitlist. Phase 1 had no RAC concept, so nothing maps to it.
UPDATE booking_passengers
   SET passenger_status = CASE WHEN seat_id IS NULL THEN 'WAITLISTED' ELSE 'CONFIRMED' END
 WHERE passenger_status IS NULL;

-- Cancelled/expired legacy bookings should not look like live passengers.
UPDATE booking_passengers bp
   SET passenger_status = 'CANCELLED'
  FROM bookings b
 WHERE bp.booking_id = b.id
   AND b.status = 'CANCELLED'
   AND bp.passenger_status = 'CONFIRMED';

-- 20 chars is enough for the longest value ('WAITLISTED' = 10).
ALTER TABLE booking_passengers ALTER COLUMN passenger_status TYPE varchar(20);

-- ---------------------------------------------------------------------
-- 3. booking_passengers.waitlist_position — stable FIFO queue order
-- ---------------------------------------------------------------------
ALTER TABLE booking_passengers ADD COLUMN IF NOT EXISTS waitlist_position integer;

-- Give every existing queued passenger a deterministic, gap-free ordering
-- per coach + journey date, oldest booking first. New rows continue from
-- the running maximum (see SeatAllocationService.nextQueuePosition).
WITH ranked AS (
    SELECT bp.id,
           row_number() OVER (
               PARTITION BY b.coach_id, b.journey_date
               ORDER BY b.created_at ASC, bp.id ASC
           ) AS pos
      FROM booking_passengers bp
      JOIN bookings b ON b.id = bp.booking_id
     WHERE bp.passenger_status IN ('RAC', 'WAITLISTED')
)
UPDATE booking_passengers bp
   SET waitlist_position = ranked.pos
  FROM ranked
 WHERE bp.id = ranked.id
   AND bp.waitlist_position IS NULL;

CREATE INDEX IF NOT EXISTS idx_bp_journey_queue
    ON booking_passengers (passenger_status, waitlist_position, id);

-- ---------------------------------------------------------------------
-- 4. bookings — segment fare, idempotency, payment window
-- ---------------------------------------------------------------------
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS distance_km integer;
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS fare_per_passenger numeric(10, 2);
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS idempotency_key varchar(80);
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS payment_deadline timestamp;

-- Legacy bookings were always full-route; price them on their own
-- fare_per_passenger so the new invariant totalFare = fare * count holds.
UPDATE bookings
   SET distance_km = COALESCE(distance_km, 0),
       fare_per_passenger = COALESCE(COALESCE(fare_per_passenger, total_fare), 0)
 WHERE fare_per_passenger IS NULL OR distance_km IS NULL;

ALTER TABLE bookings ALTER COLUMN distance_km SET DEFAULT 0;
ALTER TABLE bookings ALTER COLUMN distance_km SET NOT NULL;
ALTER TABLE bookings ALTER COLUMN fare_per_passenger SET DEFAULT 0;
ALTER TABLE bookings ALTER COLUMN fare_per_passenger SET NOT NULL;

-- Idempotency replay depends on this.
CREATE UNIQUE INDEX IF NOT EXISTS uk_booking_idempotency_key
    ON bookings (idempotency_key)
 WHERE idempotency_key IS NOT NULL;

-- Drives the pending-expiry job: 'find PAYMENT_PENDING rows past their deadline'.
CREATE INDEX IF NOT EXISTS idx_booking_expiry ON bookings (status, payment_deadline);

-- ---------------------------------------------------------------------
-- 5. Enum CHECK constraints — the one thing `ddl-auto=update` never fixes
-- ---------------------------------------------------------------------
-- Hibernate generates a CHECK constraint listing the allowed values of every
-- `@Enumerated(EnumType.STRING)` column, but `update` only ever ADDS columns:
-- it never widens an existing CHECK. So a Phase 1 database still rejects the
-- new Phase 2 statuses with a constraint violation, and the scheduled expiry
-- job silently fails forever. Drop and re-create them with the full value list.
ALTER TABLE bookings DROP CONSTRAINT IF EXISTS bookings_status_check;
ALTER TABLE bookings ADD CONSTRAINT bookings_status_check
    CHECK (status IN ('PAYMENT_PENDING', 'PAYMENT_FAILED', 'CONFIRMED', 'RAC',
                      'WAITLISTED', 'CANCELLED', 'EXPIRED'));

ALTER TABLE booking_passengers DROP CONSTRAINT IF EXISTS booking_passengers_passenger_status_check;
ALTER TABLE booking_passengers ADD CONSTRAINT booking_passengers_passenger_status_check
    CHECK (passenger_status IN ('CONFIRMED', 'RAC', 'WAITLISTED', 'CANCELLED', 'EXPIRED'));

-- ---------------------------------------------------------------------
-- 6. payments — idempotency key + lifecycle timestamp
-- ---------------------------------------------------------------------
ALTER TABLE payments ADD COLUMN IF NOT EXISTS idempotency_key varchar(80);
CREATE UNIQUE INDEX IF NOT EXISTS uk_payment_idempotency_key
    ON payments (idempotency_key)
 WHERE idempotency_key IS NOT NULL;

-- Phase 2 tracks payment lifecycle transitions, so the row is stamped on every update.
ALTER TABLE payments ADD COLUMN IF NOT EXISTS updated_at timestamp;
UPDATE payments SET updated_at = COALESCE(updated_at, created_at) WHERE updated_at IS NULL;
ALTER TABLE payments ALTER COLUMN updated_at SET NOT NULL;

-- ---------------------------------------------------------------------
-- 7. seat_reservations — the authoritative seat/date inventory
-- ---------------------------------------------------------------------
-- The UNIQUE constraint is the hard, database-level guarantee that two
-- concurrent bookings can never be granted the same seat on one date.
CREATE TABLE IF NOT EXISTS seat_reservations (
    id                  bigserial PRIMARY KEY,
    seat_id             bigint      NOT NULL REFERENCES seats (id),
    booking_passenger_id bigint     NOT NULL REFERENCES booking_passengers (id),
    journey_date        date        NOT NULL,
    status              varchar(20) NOT NULL,   -- HELD | CONFIRMED
    expires_at          timestamp,              -- set while HELD, null once confirmed
    coach_id            bigint      NOT NULL,
    train_id            bigint      NOT NULL,
    created_at          timestamp   NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_seat_reservation_seat_date
    ON seat_reservations (seat_id, journey_date);
CREATE INDEX IF NOT EXISTS idx_resv_seat_date   ON seat_reservations (seat_id, journey_date);
CREATE INDEX IF NOT EXISTS idx_resv_passenger  ON seat_reservations (booking_passenger_id);
CREATE INDEX IF NOT EXISTS idx_resv_journey    ON seat_reservations (journey_date);
CREATE INDEX IF NOT EXISTS idx_resv_hold_expiry ON seat_reservations (status, expires_at);

-- Match the value list Hibernate generates for a fresh database, so `validate`
-- and the dev/test databases agree with the migrated one.
ALTER TABLE seat_reservations DROP CONSTRAINT IF EXISTS seat_reservations_status_check;
ALTER TABLE seat_reservations ADD CONSTRAINT seat_reservations_status_check
    CHECK (status IN ('HELD', 'CONFIRMED'));

-- Seed confirmed reservations for legacy bookings that already hold a seat,
-- so a Phase 1 database does not appear fully empty after the upgrade.
-- (HELD rows are deliberately not created: legacy bookings are already paid.)
INSERT INTO seat_reservations (seat_id, booking_passenger_id, journey_date, status,
                               expires_at, coach_id, train_id, created_at)
SELECT bp.seat_id, bp.id, b.journey_date, 'CONFIRMED', NULL,
       b.coach_id, b.train_id, now()
  FROM booking_passengers bp
  JOIN bookings b ON b.id = bp.booking_id
 WHERE bp.seat_id IS NOT NULL
   AND bp.passenger_status = 'CONFIRMED'
   AND b.status IN ('CONFIRMED', 'RAC', 'WAITLISTED')
   AND NOT EXISTS (
         SELECT 1 FROM seat_reservations r
          WHERE r.seat_id = bp.seat_id AND r.journey_date = b.journey_date);

COMMIT;
