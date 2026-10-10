-- =====================================================================
-- YatraRail -- Phase 4: booking quotas and configurable fare breakdown
-- =====================================================================
-- Phase 4 adds two new tables and widens three existing ones:
--   * quota_configs  - how many seats a class of a train keeps aside
--   * quota_fares    - how much off a quota earns in a class
--   * bookings/booking_passengers/seat_reservations gain the fare snapshot
--     and the quota actually granted.
--
-- Same rules as Phases 2 and 3: a brand-new database created by
-- `ddl-auto=update` already has all of this, so this script is only needed
-- when upgrading an EXISTING Phase 1-3 database. Every statement is guarded
-- so running it twice is harmless. Run it once, as postgres, against the
-- yatrarail database, BEFORE booting with `ddl-auto=validate`.
--
--   psql -U postgres -d yatrarail -f backend/src/main/resources/db/phase4-migration.sql
--
-- It also seeds the demo tariffs and concessions, because a fare rule that
-- predates Phase 4 has no platform fee or GST, and would silently price every
-- new booking at zero extra.
-- =====================================================================

BEGIN;

-- ---------------------------------------------------------------------
-- 1. New tables
-- ---------------------------------------------------------------------
-- The unique constraints are the point of these tables: a split exists once
-- per (train, class) and a concession once per (class, quota). The database
-- enforces it, so two concurrent admin saves cannot create a duplicate row
-- that the application would then read as a doubled quota.
CREATE TABLE IF NOT EXISTS quota_configs (
    id                  bigserial PRIMARY KEY,
    train_id            bigint       NOT NULL REFERENCES trains(id),
    seat_class          varchar(20)  NOT NULL,
    tatkal_seats        integer      NOT NULL DEFAULT 0,
    senior_seats        integer      NOT NULL DEFAULT 0,
    ladies_seats        integer      NOT NULL DEFAULT 0,
    divyang_seats       integer      NOT NULL DEFAULT 0,
    staff_seats         integer      NOT NULL DEFAULT 0,
    tatkal_window_days  integer      NOT NULL DEFAULT 1,
    active              boolean      NOT NULL DEFAULT true,
    CONSTRAINT uk_quota_config_train_class UNIQUE (train_id, seat_class),
    CONSTRAINT ck_quota_config_seats_non_negative CHECK (
        tatkal_seats >= 0 AND senior_seats >= 0 AND ladies_seats >= 0
        AND divyang_seats >= 0 AND staff_seats >= 0
    ),
    CONSTRAINT ck_quota_config_window CHECK (tatkal_window_days BETWEEN 0 AND 7)
);

CREATE INDEX IF NOT EXISTS idx_quota_config_train ON quota_configs (train_id);

CREATE TABLE IF NOT EXISTS quota_fares (
    id                bigserial PRIMARY KEY,
    seat_class        varchar(20) NOT NULL,
    quota_type        varchar(20) NOT NULL,
    discount_percent  integer     NOT NULL DEFAULT 0,
    note              varchar(200),
    CONSTRAINT uk_quota_fare_class_quota UNIQUE (seat_class, quota_type),
    CONSTRAINT ck_quota_fare_discount CHECK (discount_percent BETWEEN 0 AND 90)
);

CREATE INDEX IF NOT EXISTS idx_quota_fare_class ON quota_fares (seat_class);

-- ---------------------------------------------------------------------
-- 2. fare_rules -- the platform fee and GST that make a breakdown
-- ---------------------------------------------------------------------
ALTER TABLE fare_rules ADD COLUMN IF NOT EXISTS platform_fee numeric(10, 2);
ALTER TABLE fare_rules ADD COLUMN IF NOT EXISTS gst_percent numeric(5, 2);

-- A rule that predates Phase 4 has neither column, so it would price every new
-- booking with no platform fee and no GST and the breakdown would look broken.
-- This is a demo-data script (it seeds the demo concessions below too), so the
-- demo charges are applied - but only to a rule still sitting at the untouched
-- zero state. A rule an administrator has already given a fee or a GST rate is
-- left exactly as it is, because silently rewriting a live tariff is worse than
-- a missing charge.
UPDATE fare_rules SET platform_fee = 0 WHERE platform_fee IS NULL;
UPDATE fare_rules SET gst_percent = 0 WHERE gst_percent IS NULL;

UPDATE fare_rules
   SET platform_fee = 30.00, gst_percent = 5.00
 WHERE platform_fee = 0 AND gst_percent = 0;

ALTER TABLE fare_rules ALTER COLUMN platform_fee SET DEFAULT 0;
ALTER TABLE fare_rules ALTER COLUMN platform_fee SET NOT NULL;
ALTER TABLE fare_rules ALTER COLUMN gst_percent SET DEFAULT 0;
ALTER TABLE fare_rules ALTER COLUMN gst_percent SET NOT NULL;

ALTER TABLE fare_rules DROP CONSTRAINT IF EXISTS ck_fare_rule_platform_fee;
ALTER TABLE fare_rules ADD CONSTRAINT ck_fare_rule_platform_fee CHECK (platform_fee >= 0);
ALTER TABLE fare_rules DROP CONSTRAINT IF EXISTS ck_fare_rule_gst_percent;
ALTER TABLE fare_rules ADD CONSTRAINT ck_fare_rule_gst_percent CHECK (gst_percent >= 0);

-- ---------------------------------------------------------------------
-- 3. bookings -- the immutable fare snapshot
-- ---------------------------------------------------------------------
-- The booking stores the tariff numbers and every intermediate total instead
-- of a foreign key to fare_rules, so a ticket can still explain its own price
-- after an administrator changes the tariff.
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS fare_base numeric(10, 2);
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS fare_per_km numeric(10, 2);
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS fare_minimum numeric(10, 2);
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS distance_fare numeric(10, 2);
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS fare_basis varchar(20);
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS passenger_fare_total numeric(10, 2);
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS discount_total numeric(10, 2);
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS platform_fee numeric(10, 2);
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS gst_percent numeric(5, 2);
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS gst_amount numeric(10, 2);

-- Pre-Phase-4 bookings were charged exactly fare_per_passenger * passenger
-- count, so that product is the honest backfill for their subtotal - not the
-- per-passenger figure on its own. fare_basis is deliberately left NULL: it is
-- the flag the application uses to tell a Phase 4 snapshot from a legacy row.
-- Their total_fare already matches and is deliberately left alone.
UPDATE bookings b
   SET passenger_fare_total = COALESCE(b.fare_per_passenger, 0) * COALESCE(
           (SELECT count(*) FROM booking_passengers bp WHERE bp.booking_id = b.id), 0)
 WHERE b.passenger_fare_total IS NULL;

UPDATE bookings SET fare_base = COALESCE(fare_base, 0);
UPDATE bookings SET fare_per_km = COALESCE(fare_per_km, 0);
UPDATE bookings SET fare_minimum = COALESCE(fare_minimum, 0);
UPDATE bookings SET distance_fare = COALESCE(distance_fare, 0);
UPDATE bookings SET discount_total = COALESCE(discount_total, 0);
UPDATE bookings SET platform_fee = COALESCE(platform_fee, 0);
UPDATE bookings SET gst_percent = COALESCE(gst_percent, 0);
UPDATE bookings SET gst_amount = COALESCE(gst_amount, 0);

ALTER TABLE bookings ALTER COLUMN passenger_fare_total SET NOT NULL;
ALTER TABLE bookings ALTER COLUMN discount_total SET NOT NULL;
ALTER TABLE bookings ALTER COLUMN platform_fee SET NOT NULL;
ALTER TABLE bookings ALTER COLUMN gst_percent SET NOT NULL;
ALTER TABLE bookings ALTER COLUMN gst_amount SET NOT NULL;

ALTER TABLE bookings DROP CONSTRAINT IF EXISTS ck_booking_fare_basis;
ALTER TABLE bookings ADD CONSTRAINT ck_booking_fare_basis
    CHECK (fare_basis IS NULL OR fare_basis IN ('COACH_BASE', 'DISTANCE', 'MINIMUM'));

-- ---------------------------------------------------------------------
-- 4. booking_passengers -- which quota each passenger actually got
-- ---------------------------------------------------------------------
-- `quota` is what was GRANTED, not what was requested, so it is also what the
-- fare was computed from. Keeping both in one column is deliberate: storing
-- the request separately would allow the two to drift, and the fare would stop
-- matching the quota on the ticket.
ALTER TABLE booking_passengers ADD COLUMN IF NOT EXISTS quota_type varchar(20);
ALTER TABLE booking_passengers ADD COLUMN IF NOT EXISTS quota_discount_percent integer;
ALTER TABLE booking_passengers ADD COLUMN IF NOT EXISTS fare_amount numeric(10, 2);
ALTER TABLE booking_passengers ADD COLUMN IF NOT EXISTS quota_note varchar(200);

-- Everything that existed before Phase 4 travelled under general quota at the
-- full fare, which is exactly what the columns say. fare_amount takes the
-- booking's per-passenger fare so the stored lines agree with the subtotal above.
UPDATE booking_passengers SET quota_type = 'GENERAL' WHERE quota_type IS NULL;
UPDATE booking_passengers SET quota_discount_percent = 0 WHERE quota_discount_percent IS NULL;
UPDATE booking_passengers bp
   SET fare_amount = COALESCE((SELECT b.fare_per_passenger
                                 FROM bookings b
                                WHERE b.id = bp.booking_id), 0)
 WHERE bp.fare_amount IS NULL;

ALTER TABLE booking_passengers ALTER COLUMN quota_type SET DEFAULT 'GENERAL';
ALTER TABLE booking_passengers ALTER COLUMN quota_type SET NOT NULL;
ALTER TABLE booking_passengers ALTER COLUMN quota_discount_percent SET DEFAULT 0;
ALTER TABLE booking_passengers ALTER COLUMN quota_discount_percent SET NOT NULL;
ALTER TABLE booking_passengers ALTER COLUMN fare_amount SET DEFAULT 0;
ALTER TABLE booking_passengers ALTER COLUMN fare_amount SET NOT NULL;

ALTER TABLE booking_passengers DROP CONSTRAINT IF EXISTS ck_bp_quota_type;
ALTER TABLE booking_passengers ADD CONSTRAINT ck_bp_quota_type CHECK (quota_type IN
    ('GENERAL', 'TATKAL', 'SENIOR', 'LADIES', 'DIVYANG', 'STAFF'));
ALTER TABLE booking_passengers DROP CONSTRAINT IF EXISTS ck_bp_quota_discount;
ALTER TABLE booking_passengers ADD CONSTRAINT ck_bp_quota_discount
    CHECK (quota_discount_percent BETWEEN 0 AND 90);

CREATE INDEX IF NOT EXISTS idx_bp_quota ON booking_passengers (quota_type);

-- ---------------------------------------------------------------------
-- 5. seat_reservations -- a reservation is a claim on a quota
-- ---------------------------------------------------------------------
-- Quota usage is counted from this table rather than from the booking rows,
-- because a hold is a real claim: an unpaid booking that is holding a seat has
-- already used its quota, and deleting the row on cancellation or expiry
-- releases it again.
ALTER TABLE seat_reservations ADD COLUMN IF NOT EXISTS quota_type varchar(20);

UPDATE seat_reservations SET quota_type = 'GENERAL' WHERE quota_type IS NULL;

ALTER TABLE seat_reservations ALTER COLUMN quota_type SET DEFAULT 'GENERAL';
ALTER TABLE seat_reservations ALTER COLUMN quota_type SET NOT NULL;

ALTER TABLE seat_reservations DROP CONSTRAINT IF EXISTS ck_reservation_quota_type;
ALTER TABLE seat_reservations ADD CONSTRAINT ck_reservation_quota_type CHECK (quota_type IN
    ('GENERAL', 'TATKAL', 'SENIOR', 'LADIES', 'DIVYANG', 'STAFF'));

-- The quota ledger is read per (train, class, journey date, quota) every time
-- a class is booked, so that is the index order.
CREATE INDEX IF NOT EXISTS idx_resv_quota_usage
    ON seat_reservations (train_id, journey_date, status, quota_type);

-- ---------------------------------------------------------------------
-- 6. Demo tariffs and concessions
-- ---------------------------------------------------------------------
-- Inserted with ON CONFLICT DO NOTHING so a re-run, or a database that already
-- carries its own values, is never overwritten. Admins are expected to tune
-- these from the admin UI afterwards.
INSERT INTO fare_rules (seat_class, fare_per_km, minimum_fare, platform_fee, gst_percent)
VALUES
    ('FIRST_AC',    3.20,  500.00, 30.00, 5.00),
    ('SECOND_AC',   1.90,  300.00, 30.00, 5.00),
    ('THIRD_AC',    1.40,  200.00, 30.00, 5.00),
    ('SLEEPER',     0.65,   90.00, 25.00, 5.00),
    ('CHAIR_CAR',   0.90,  120.00, 20.00, 5.00),
    ('SECOND_SEATING', 0.45, 40.00, 15.00, 5.00)
ON CONFLICT (seat_class) DO NOTHING;

-- Only the two quotas that get a real concession in this simulation. Everything
-- else stays at 0%, which is a legitimate configuration and not a missing one.
INSERT INTO quota_fares (seat_class, quota_type, discount_percent, note)
VALUES
    ('FIRST_AC',  'SENIOR',  40, 'Senior citizen concession (simulated)'),
    ('SECOND_AC', 'SENIOR',  40, 'Senior citizen concession (simulated)'),
    ('THIRD_AC',  'SENIOR',  40, 'Senior citizen concession (simulated)'),
    ('SLEEPER',   'SENIOR',  40, 'Senior citizen concession (simulated)'),
    ('CHAIR_CAR', 'SENIOR',  40, 'Senior citizen concession (simulated)'),
    ('SECOND_SEATING', 'SENIOR', 40, 'Senior citizen concession (simulated)'),
    ('FIRST_AC',  'DIVYANG', 50, 'Divyang concession (simulated)'),
    ('SECOND_AC', 'DIVYANG', 50, 'Divyang concession (simulated)'),
    ('THIRD_AC',  'DIVYANG', 50, 'Divyang concession (simulated)'),
    ('SLEEPER',   'DIVYANG', 50, 'Divyang concession (simulated)'),
    ('CHAIR_CAR', 'DIVYANG', 50, 'Divyang concession (simulated)'),
    ('SECOND_SEATING', 'DIVYANG', 50, 'Divyang concession (simulated)')
ON CONFLICT (seat_class, quota_type) DO NOTHING;

-- ---------------------------------------------------------------------
-- 7. Default quota splits
-- ---------------------------------------------------------------------
-- Every reserved slice starts at ZERO, which leaves the whole class in the
-- general quota. This is a deliberate default, not a stub: quotas are enabled
-- per train and class from the admin UI, and seeding a split here would
-- silently shrink the general quota of every existing train and change the
-- fares of bookings that have already been made.
INSERT INTO quota_configs (train_id, seat_class, tatkal_seats, senior_seats, ladies_seats,
                           divyang_seats, staff_seats, tatkal_window_days, active)
SELECT c.train_id, c.seat_class, 0, 0, 0, 0, 0, 1, true
  FROM (SELECT DISTINCT train_id, seat_class FROM coaches WHERE active = true) c
ON CONFLICT (train_id, seat_class) DO NOTHING;

-- ---------------------------------------------------------------------
-- 8. Defensive consistency
-- ---------------------------------------------------------------------
-- The application treats a seat_reservations.quota_type that disagrees with its
-- booking_passenger's quota as a corrupt ledger, because quota accounting is
-- driven entirely by the reservation row. Catch it here rather than letting a
-- class quietly oversell its senior-citizen slice at runtime.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM seat_reservations r
          JOIN booking_passengers p ON p.id = r.booking_passenger_id
         WHERE r.quota_type <> p.quota_type
    ) THEN
        RAISE EXCEPTION 'seat_reservations.quota_type must match its booking_passenger quota';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM quota_configs q
          JOIN (
                SELECT train_id, seat_class, sum(total_seats) AS capacity
                  FROM coaches
                 WHERE active = true
                 GROUP BY train_id, seat_class
               ) c ON c.train_id = q.train_id AND c.seat_class = q.seat_class
         WHERE (q.tatkal_seats + q.senior_seats + q.ladies_seats
              + q.divyang_seats + q.staff_seats) > c.capacity
    ) THEN
        RAISE EXCEPTION 'a quota split reserves more seats than the class actually owns';
    END IF;
END $$;

COMMIT;
