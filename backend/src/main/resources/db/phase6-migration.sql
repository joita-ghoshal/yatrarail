-- =====================================================================
-- YatraRail -- Phase 6: railway staff portal
-- =====================================================================
-- Phase 6 gives staff somewhere to actually *do* something. Up to Phase 5 the
-- staff console was read-only: a train list and a passenger manifest, and
-- nothing to record. This adds the operational record of a journey day:
--   * boarding_status on booking_passengers -- gate check-in
--   * train_incidents       -- delays, diversions, breakdowns
--   * assistance_requests   -- help a passenger asked for
--
-- Same rules as Phases 2-5: a brand-new database created by
-- `ddl-auto=update` already has all of this, so this script is only needed
-- when upgrading an EXISTING Phase 1-5 database. Every statement is guarded
-- so running it twice is harmless. Run it once, as postgres, against the
-- yatrarail database, BEFORE booting with `ddl-auto=validate`.
--
--   psql -U postgres -d yatrarail -f backend/src/main/resources/db/phase6-migration.sql
-- =====================================================================

BEGIN;

-- ---------------------------------------------------------------------
-- 1. Gate check-in on booking_passengers
-- ---------------------------------------------------------------------
-- NOT_BOARDED is the default so a pre-Phase-6 passenger row means "not yet
-- scanned" rather than NULL, which would make every tally query special-case
-- it. This is an operational fact recorded at the platform and nothing else:
-- it is deliberately NOT wired to the refund machinery, so marking a
-- passenger NO_SHOW cannot move money. Refunds stay an explicit, audited
-- action through the cancellation flow.
--
-- `boarding_marked_by_id` records which staff member scanned, so a mis-scan
-- can be traced and corrected rather than silently overwritten.
ALTER TABLE booking_passengers ADD COLUMN IF NOT EXISTS boarding_status varchar(20) NOT NULL DEFAULT 'NOT_BOARDED';
ALTER TABLE booking_passengers ADD COLUMN IF NOT EXISTS boarding_marked_by_id bigint REFERENCES users(id);
ALTER TABLE booking_passengers ADD COLUMN IF NOT EXISTS boarding_marked_at timestamp;

-- The column above only gets NOT NULL and a default when it did not exist yet.
-- On a database that `ddl-auto=update` already created from the entity, it exists but is NULLABLE
-- with no default, because a Java field initialiser is not DDL. Without this repair such a database
-- would accept a NULL boarding_status, and that passenger would silently vanish from every tally
-- below. Backfill first, then tighten.
UPDATE booking_passengers SET boarding_status = 'NOT_BOARDED' WHERE boarding_status IS NULL;
ALTER TABLE booking_passengers ALTER COLUMN boarding_status SET DEFAULT 'NOT_BOARDED';
ALTER TABLE booking_passengers ALTER COLUMN boarding_status SET NOT NULL;

-- The generated enum CHECK is dropped by name-agnostic lookup rather than by name, because
-- `ddl-auto=update` names it after the column on a fresh database and after a hash on some others.
DO $$
DECLARE cname text;
BEGIN
    SELECT conname INTO cname FROM pg_constraint
    WHERE conrelid = 'booking_passengers'::regclass
      AND contype = 'c'
      AND pg_get_constraintdef(oid) LIKE '%boarding_status%';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE booking_passengers DROP CONSTRAINT %I', cname);
    END IF;
END $$;

ALTER TABLE booking_passengers DROP CONSTRAINT IF EXISTS ck_bp_boarding_status;
ALTER TABLE booking_passengers ADD CONSTRAINT ck_bp_boarding_status
    CHECK (boarding_status IN ('NOT_BOARDED', 'BOARDED', 'NO_SHOW'));

CREATE INDEX IF NOT EXISTS idx_bp_boarding ON booking_passengers(boarding_status);

-- ---------------------------------------------------------------------
-- 2. train_incidents -- delay and disruption reporting
-- ---------------------------------------------------------------------
-- Scoped to train + journey_date on purpose: two days of the same service
-- must not contaminate each other's dashboard counts. `reported_by_id` is
-- NOT NULL so "who told us, and when" stays answerable; only staff can
-- report, enforced at the service layer.
CREATE TABLE IF NOT EXISTS train_incidents (
    id              bigserial PRIMARY KEY,
    train_id        bigint       NOT NULL REFERENCES trains(id),
    journey_date    date         NOT NULL,
    type            varchar(20)  NOT NULL,
    severity        varchar(20)  NOT NULL,
    status          varchar(20)  NOT NULL DEFAULT 'OPEN',
    -- NULL for incidents with no time cost (a diversion notice, say) rather
    -- than 0, which would read as "on time".
    delay_minutes   integer,
    description     varchar(300) NOT NULL,
    reported_by_id  bigint       NOT NULL REFERENCES users(id),
    resolved_by_id  bigint       REFERENCES users(id),
    resolved_at     timestamp,
    reported_at     timestamp    NOT NULL DEFAULT now(),
    CONSTRAINT ck_incident_type CHECK (type IN
        ('DELAY', 'CANCELLATION', 'DIVERSION', 'BREAKDOWN', 'RESCHEDULE', 'OTHER')),
    CONSTRAINT ck_incident_severity CHECK (severity IN ('INFO', 'MINOR', 'MAJOR', 'CRITICAL')),
    CONSTRAINT ck_incident_status CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')),
    CONSTRAINT ck_incident_delay_minutes CHECK (delay_minutes IS NULL OR delay_minutes >= 0),
    CONSTRAINT ck_incident_description CHECK (length(trim(description)) > 0)
);

CREATE INDEX IF NOT EXISTS idx_incident_train_date ON train_incidents(train_id, journey_date);
CREATE INDEX IF NOT EXISTS idx_incident_status     ON train_incidents(status);
CREATE INDEX IF NOT EXISTS idx_incident_reported   ON train_incidents(reported_at);

-- ---------------------------------------------------------------------
-- 3. assistance_requests -- help a passenger asked for
-- ---------------------------------------------------------------------
-- Pinned to a booking PASSENGER, not just a booking: three people travelling
-- together, one of whom needs a wheelchair, is one request, and staff need
-- to see which seat to walk to.
--
-- `booking_id` is denormalised from the passenger purely so the staff queue
-- can filter by journey date without a join through booking_passengers.
CREATE TABLE IF NOT EXISTS assistance_requests (
    id                  bigserial PRIMARY KEY,
    booking_passenger_id bigint      NOT NULL REFERENCES booking_passengers(id),
    booking_id          bigint       NOT NULL REFERENCES bookings(id),
    category            varchar(30)  NOT NULL,
    status              varchar(20)  NOT NULL DEFAULT 'OPEN',
    details             varchar(400),
    assigned_to_id      bigint       REFERENCES users(id),
    raised_at           timestamp    NOT NULL DEFAULT now(),
    resolved_at         timestamp,
    CONSTRAINT ck_assist_category CHECK (category IN
        ('WHEELCHAIR', 'LUGGAGE', 'MEDICAL', 'UNACCOMPANIED_MINOR', 'LANGUAGE', 'OTHER'))
);

CREATE INDEX IF NOT EXISTS idx_assist_status  ON assistance_requests(status);
CREATE INDEX IF NOT EXISTS idx_assist_booking ON assistance_requests(booking_id);
CREATE INDEX IF NOT EXISTS idx_assist_raised  ON assistance_requests(raised_at);

-- The default is applied in DDL as well as in the entity so a row inserted by
-- any other tool still lands in a legal state.
ALTER TABLE assistance_requests DROP CONSTRAINT IF EXISTS ck_assist_status;
ALTER TABLE assistance_requests ADD CONSTRAINT ck_assist_status
    CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED'));

-- Same reasoning as boarding_status: on a database Hibernate created from the
-- entity, `status` is NOT NULL but carries no default, so an insert from
-- outside the builder would fail instead of landing in a legal state.
ALTER TABLE train_incidents      ALTER COLUMN status SET DEFAULT 'OPEN';
ALTER TABLE train_incidents      ALTER COLUMN status SET NOT NULL;
ALTER TABLE assistance_requests ALTER COLUMN status SET DEFAULT 'OPEN';
ALTER TABLE assistance_requests ALTER COLUMN status SET NOT NULL;

COMMIT;