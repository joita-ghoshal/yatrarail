-- =====================================================================
-- YatraRail -- Phase 5: ticket, payment ledger and richer refunds
-- =====================================================================
-- Phase 5 turns a snapshot of "the current state" into a ledger:
--   * payment_attempts  - append-only history of every payment attempt
--   * cancellations     - may now cover ONE passenger instead of a whole
--                         booking, and carries the itemised refund
--
-- Same rules as Phases 2-4: a brand-new database created by `ddl-auto=update`
-- already has all of this, so this script is only needed when upgrading an
-- EXISTING Phase 1-4 database. Every statement is guarded so running it twice
-- is harmless. Run it once, as postgres, against the yatrarail database,
-- BEFORE booting with `ddl-auto=validate`.
--
--   psql -U postgres -d yatrarail -f backend/src/main/resources/db/phase5-migration.sql
-- =====================================================================

BEGIN;

-- ---------------------------------------------------------------------
-- 1. payment_attempts -- the payment ledger
-- ---------------------------------------------------------------------
-- Append-only: a new attempt inserts a row and never updates an earlier one, so
-- the `payments` table stays the settled answer while this table can explain how
-- it got there. `amount` is copied from the booking rather than derived, so a
-- later re-pricing cannot retroactively change what an attempt was worth.
--
-- `idempotency_key` is deliberately NOT unique: only `payments.idempotency_key`
-- is a replay guard. Two genuinely different attempts can share a client key
-- across sessions, and making this column unique would reject a legitimate
-- second attempt on a booking whose first one failed.
CREATE TABLE IF NOT EXISTS payment_attempts (
    id                    bigserial PRIMARY KEY,
    booking_id            bigint       NOT NULL REFERENCES bookings(id),
    user_id               bigint       NOT NULL REFERENCES users(id),
    method                varchar(20)  NOT NULL,
    amount                numeric(10, 2) NOT NULL,
    status                varchar(20)  NOT NULL,
    transaction_reference varchar(40)  NOT NULL,
    idempotency_key       varchar(80),
    client_initiated      boolean      NOT NULL DEFAULT true,
    created_at            timestamp    NOT NULL DEFAULT now(),
    CONSTRAINT uk_attempt_reference UNIQUE (transaction_reference),
    CONSTRAINT ck_attempt_status CHECK (status IN ('INITIATED', 'SUCCESS', 'FAILED', 'PENDING')),
    CONSTRAINT ck_attempt_method CHECK (method <> '')
);

-- The reconciliation report and the passenger's payment history both read
-- "this user's attempts, newest first" and "this booking's attempts, oldest
-- first", so both access paths are indexed.
CREATE INDEX IF NOT EXISTS idx_attempt_booking ON payment_attempts (booking_id, created_at);
CREATE INDEX IF NOT EXISTS idx_attempt_user    ON payment_attempts (user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_attempt_status  ON payment_attempts (status);

-- ---------------------------------------------------------------------
-- 2. cancellations -- per-passenger refunds
-- ---------------------------------------------------------------------
-- The Phase 2 schema made this one-to-one with the booking:
--
--   booking_id bigint NOT NULL UNIQUE
--
-- that single unique constraint is what made a partial cancellation
-- unrepresentable. It has to go, and it is dropped by name-agnostic lookup
-- because `ddl-auto=update` generated a hashed name (UK_<hash>) rather than a
-- readable one, so hard-coding a name here would fail on a real database.
DO $$
DECLARE
    constraint_row record;
BEGIN
    FOR constraint_row IN
        SELECT con.conname
          FROM pg_constraint con
          JOIN pg_class rel ON rel.oid = con.conrelid
          JOIN pg_attribute att
            ON att.attrelid = rel.oid AND att.attnum = ANY (con.conkey)
         WHERE rel.relname = 'cancellations'
           AND con.contype = 'u'
           AND con.conkey = ARRAY[
                 (SELECT attnum FROM pg_attribute
                   WHERE attrelid = rel.oid AND attname = 'booking_id')
               ]::smallint[]
    LOOP
        EXECUTE format('ALTER TABLE cancellations DROP CONSTRAINT %I', constraint_row.conname);
    END LOOP;
END $$;

-- A row may now name the single passenger it cancels. NULL keeps its original
-- meaning -- "this row covers the whole booking" -- which is exactly what every
-- pre-Phase-5 row means, so existing data needs no rewrite.
ALTER TABLE cancellations ADD COLUMN IF NOT EXISTS booking_passenger_id bigint;

-- Dropped name-agnostically for the same reason as the unique constraint above:
-- `ddl-auto=update` names its constraints UK_<hash>/FK_<hash>, so a
-- `DROP CONSTRAINT IF EXISTS fk_cancellation_passenger` would match nothing and the
-- ADD below would then fail with "already exists" on the second run.
DO $$
DECLARE
    constraint_row record;
BEGIN
    FOR constraint_row IN
        SELECT con.conname
          FROM pg_constraint con
          JOIN pg_class rel ON rel.oid = con.conrelid
         WHERE rel.relname = 'cancellations'
           AND con.contype = 'f'
           AND con.conkey = ARRAY[
                 (SELECT attnum FROM pg_attribute
                   WHERE attrelid = rel.oid AND attname = 'booking_passenger_id')
               ]::smallint[]
    LOOP
        EXECUTE format('ALTER TABLE cancellations DROP CONSTRAINT %I', constraint_row.conname);
    END LOOP;
END $$;

ALTER TABLE cancellations
    ADD CONSTRAINT fk_cancellation_passenger
    FOREIGN KEY (booking_passenger_id) REFERENCES booking_passengers(id);

-- One cancellation per passenger. Postgres UNIQUE permits many NULLs, so the
-- whole-booking rows are unaffected by this constraint.
--
-- Both the constraint AND any unique index on the column are dropped, because
-- Postgres will not add a unique constraint to a column that already has a unique
-- index -- and a database that has been through `ddl-auto=update` with the
-- annotation in place will have built exactly that index.
DO $$
DECLARE
    constraint_row record;
    index_row record;
BEGIN
    FOR constraint_row IN
        SELECT con.conname
          FROM pg_constraint con
          JOIN pg_class rel ON rel.oid = con.conrelid
         WHERE rel.relname = 'cancellations'
           AND con.contype = 'u'
           AND con.conkey = ARRAY[
                 (SELECT attnum FROM pg_attribute
                   WHERE attrelid = rel.oid AND attname = 'booking_passenger_id')
               ]::smallint[]
    LOOP
        EXECUTE format('ALTER TABLE cancellations DROP CONSTRAINT %I', constraint_row.conname);
    END LOOP;

    FOR index_row IN
        SELECT idx.relname
          FROM pg_index ix
          JOIN pg_class rel ON rel.oid = ix.indrelid
          JOIN pg_class idx ON idx.oid = ix.indexrelid
         WHERE rel.relname = 'cancellations'
           AND ix.indisunique
           AND ix.indnatts = 1
           AND ix.indkey[0] = (
                 SELECT attnum FROM pg_attribute
                   WHERE attrelid = rel.oid AND attname = 'booking_passenger_id')
    LOOP
        EXECUTE format('DROP INDEX IF EXISTS %I', index_row.relname);
    END LOOP;
END $$;

ALTER TABLE cancellations ADD CONSTRAINT uk_cancellation_passenger
    UNIQUE (booking_passenger_id);

-- The itemised refund. A single refund_amount cannot be audited later, because
-- nobody can tell how much of it was fare, how much GST, and how much was the
-- non-refundable platform fee. These are stored, alongside the tier inputs, so
-- the row still explains itself after the policy or the tariff has changed.
ALTER TABLE cancellations ADD COLUMN IF NOT EXISTS scope varchar(20);
ALTER TABLE cancellations ADD COLUMN IF NOT EXISTS fare_refunded numeric(10, 2);
ALTER TABLE cancellations ADD COLUMN IF NOT EXISTS gst_refunded numeric(10, 2);
ALTER TABLE cancellations ADD COLUMN IF NOT EXISTS platform_fee_refunded numeric(10, 2);
ALTER TABLE cancellations ADD COLUMN IF NOT EXISTS cancellation_charge numeric(10, 2);
ALTER TABLE cancellations ADD COLUMN IF NOT EXISTS refund_percent numeric(5, 2);
ALTER TABLE cancellations ADD COLUMN IF NOT EXISTS days_before_journey bigint;
ALTER TABLE cancellations ADD COLUMN IF NOT EXISTS refund_reference varchar(40);
ALTER TABLE cancellations ADD COLUMN IF NOT EXISTS processed_at timestamp;

-- A pre-Phase-5 row cancelled the entire booking, and was settled on the spot.
UPDATE cancellations SET scope = 'FULL' WHERE scope IS NULL;
UPDATE cancellations SET refund_status = 'PROCESSED' WHERE refund_status IS NULL;
UPDATE cancellations SET fare_refunded = 0 WHERE fare_refunded IS NULL;
UPDATE cancellations SET gst_refunded = 0 WHERE gst_refunded IS NULL;
UPDATE cancellations SET platform_fee_refunded = 0 WHERE platform_fee_refunded IS NULL;
UPDATE cancellations SET cancellation_charge = 0 WHERE cancellation_charge IS NULL;
-- The historic rows are the whole refund written into fare_refunded: their
-- refund_amount is the authoritative figure and the split was never recorded.
--
-- `refund_reference IS NULL` is what marks a row as pre-Phase-5, and it is the only safe
-- marker to key off. Every Phase 5 write generates a refund reference, whereas a fare split of
-- zero is legitimate (a wholly GST or wholly platform-fee refund), so matching on
-- `fare_refunded = 0` would also catch new rows on a second run and inflate their fare share
-- until ck_cancellation_breakdown rejected the whole script.
UPDATE cancellations
   SET fare_refunded = refund_amount
 WHERE scope = 'FULL'
   AND booking_passenger_id IS NULL
   AND refund_reference IS NULL
   AND fare_refunded = 0
   AND refund_amount <> 0;

ALTER TABLE cancellations ALTER COLUMN scope SET DEFAULT 'FULL';
ALTER TABLE cancellations ALTER COLUMN fare_refunded SET DEFAULT 0;
ALTER TABLE cancellations ALTER COLUMN gst_refunded SET DEFAULT 0;
ALTER TABLE cancellations ALTER COLUMN platform_fee_refunded SET DEFAULT 0;
ALTER TABLE cancellations ALTER COLUMN cancellation_charge SET DEFAULT 0;
ALTER TABLE cancellations ALTER COLUMN refund_status SET DEFAULT 'PENDING';

ALTER TABLE cancellations DROP CONSTRAINT IF EXISTS ck_cancellation_scope;
ALTER TABLE cancellations ADD CONSTRAINT ck_cancellation_scope
    CHECK (scope IS NULL OR scope IN ('FULL', 'PARTIAL'));
ALTER TABLE cancellations DROP CONSTRAINT IF EXISTS ck_cancellation_refund_status;
ALTER TABLE cancellations ADD CONSTRAINT ck_cancellation_refund_status
    CHECK (refund_status IN ('PENDING', 'PROCESSED', 'FAILED'));
ALTER TABLE cancellations DROP CONSTRAINT IF EXISTS ck_cancellation_refund_percent;
ALTER TABLE cancellations ADD CONSTRAINT ck_cancellation_refund_percent
    CHECK (refund_percent IS NULL OR refund_percent BETWEEN 0 AND 100);
-- The itemised lines must add up to the total. Enforced here rather than only
-- in the service, so a future writer cannot quietly produce an unauditable row.
ALTER TABLE cancellations DROP CONSTRAINT IF EXISTS ck_cancellation_breakdown;
ALTER TABLE cancellations ADD CONSTRAINT ck_cancellation_breakdown
    CHECK (refund_amount = fare_refunded + gst_refunded + platform_fee_refunded);

CREATE INDEX IF NOT EXISTS idx_cancellation_booking   ON cancellations (booking_id, cancelled_at);
CREATE INDEX IF NOT EXISTS idx_cancellation_passenger ON cancellations (booking_passenger_id);
CREATE INDEX IF NOT EXISTS idx_cancellation_status    ON cancellations (refund_status);

-- The attempt checks are declared inside the CREATE TABLE above, so on a database where
-- `payment_attempts` already existed that whole statement was skipped and they were never
-- asserted. A schema built by `ddl-auto=update` lands exactly there: Hibernate creates the table
-- and the unique index but not the blank-method check, which would leave a Phase 5 ledger with a
-- weaker contract than a migrated one. Re-stating both here leaves the table identical whichever
-- route created it, and stays a no-op on a second run.
ALTER TABLE payment_attempts DROP CONSTRAINT IF EXISTS ck_attempt_status;
ALTER TABLE payment_attempts ADD CONSTRAINT ck_attempt_status
    CHECK (status IN ('INITIATED', 'SUCCESS', 'FAILED', 'PENDING'));
ALTER TABLE payment_attempts DROP CONSTRAINT IF EXISTS ck_attempt_method;
ALTER TABLE payment_attempts ADD CONSTRAINT ck_attempt_method CHECK (method <> '');

-- ---------------------------------------------------------------------
-- 3. Backfill the ledger from the existing `payments` table
-- ---------------------------------------------------------------------
-- A Phase 1-4 database has no attempt history, but it does have one settled
-- `payments` row per booking. Seeding one attempt row from it makes the payment
-- history page and the reconciliation report show the real bookings that
-- already exist, instead of an empty ledger that looks like data loss. The
-- reference is suffixed so the seeded attempt is never character-identical to the
-- settled `payments` row it was derived from: the two tables have separate
-- uniqueness, but a reference that appears twice across them is a support
-- question nobody should have to answer.
--
-- The `WHERE NOT EXISTS` guard makes the whole insert a no-op on a second run, so
-- the migration stays idempotent even against an already-migrated database.
INSERT INTO payment_attempts (booking_id, user_id, method, amount, status,
                              transaction_reference, idempotency_key, client_initiated, created_at)
SELECT p.booking_id,
       b.user_id,
       p.method,
       p.amount,
       p.status,
       p.transaction_reference || '-P4',
       p.idempotency_key,
       true,
       p.created_at
  FROM payments p
  JOIN bookings b ON b.id = p.booking_id
 WHERE NOT EXISTS (
       SELECT 1 FROM payment_attempts a WHERE a.booking_id = p.booking_id
   )
ON CONFLICT (transaction_reference) DO NOTHING;

-- ---------------------------------------------------------------------
-- 4. Defensive consistency
-- ---------------------------------------------------------------------
-- A row that names a passenger of a DIFFERENT booking would let a cancellation
-- release a seat from one coach while refunding money against another. Catch it
-- here instead of letting the two halves quietly disagree at runtime.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM cancellations c
          JOIN booking_passengers p ON p.id = c.booking_passenger_id
         WHERE p.booking_id <> c.booking_id
    ) THEN
        RAISE EXCEPTION 'cancellations.booking_passenger_id must belong to the same booking';
    END IF;

    IF EXISTS (
        SELECT 1 FROM payment_attempts a WHERE a.booking_id IS NULL
    ) THEN
        RAISE EXCEPTION 'a payment attempt must always reference its booking';
    END IF;
END $$;

COMMIT;
