-- =====================================================================
-- YatraRail -- Phase 3: temporary one-time (guest) accounts
-- =====================================================================
-- Phase 3 lets a guest book without registering, using a short-lived
-- session that the backend itself expires and can revoke on demand.
--
-- Same rules as Phase 2: a brand-new database created by `ddl-auto=update`
-- (or from `schema.sql`) already has all of this, so this script is only
-- needed when upgrading an EXISTING Phase 1/2 database. Every statement is
-- guarded so running it twice is harmless. Run it once, as postgres, against
-- the yatrarail database, BEFORE booting with `ddl-auto=validate`.
--
--   psql -U postgres -d yatrarail -f backend/src/main/resources/db/phase3-migration.sql
-- =====================================================================

BEGIN;

-- ---------------------------------------------------------------------
-- 1. users -- temporary-account lifecycle columns
-- ---------------------------------------------------------------------
-- `temporary_account` is the flag the application trusts; the role alone is
-- never used to decide whether an account is a guest, so a mislabelled row
-- cannot quietly acquire permanent-account powers.
ALTER TABLE users ADD COLUMN IF NOT EXISTS temporary_account boolean;
ALTER TABLE users ADD COLUMN IF NOT EXISTS temp_expires_at timestamp;
ALTER TABLE users ADD COLUMN IF NOT EXISTS temp_revoked_at timestamp;

-- Every pre-Phase-3 account is a standard one. Written as an UPDATE rather
-- than a DEFAULT so the migration is explicit about the backfill.
UPDATE users SET temporary_account = false WHERE temporary_account IS NULL;
ALTER TABLE users ALTER COLUMN temporary_account SET DEFAULT false;
ALTER TABLE users ALTER COLUMN temporary_account SET NOT NULL;

-- A guest is not forced to hand over a phone number.
ALTER TABLE users ALTER COLUMN phone DROP NOT NULL;

-- The cleanup job scans by (flag, deadline); a guest session lookup is per-request.
CREATE INDEX IF NOT EXISTS idx_users_temp_expiry
    ON users (temporary_account, temp_expires_at);

-- ---------------------------------------------------------------------
-- 2. users.role -- add TEMP_PASSENGER
-- ---------------------------------------------------------------------
-- Same trap as Phase 2: Hibernate GENERATES this CHECK constraint for a new
-- column, but `ddl-auto=update` only ever ADDS columns, it never widens an
-- existing CHECK. Without dropping and re-creating it, creating a guest session
-- fails with a constraint violation the moment the migration is applied to a
-- database that Hibernate had already created.
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check
    CHECK (role IN ('PASSENGER', 'ADMIN', 'STAFF', 'TEMP_PASSENGER'));

-- ---------------------------------------------------------------------
-- 3. Defensive consistency
-- ---------------------------------------------------------------------
-- A guest account that is not a TEMP_PASSENGER (or vice versa) would make the
-- `temporary_account` flag and the role disagree, which is exactly the state
-- the application is written to distrust. Fail loudly at migration time
-- instead of at 3am.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM users
         WHERE temporary_account = true
           AND role <> 'TEMP_PASSENGER'
    ) THEN
        RAISE EXCEPTION 'temporary_account rows must have role TEMP_PASSENGER';
    END IF;
    IF EXISTS (
        SELECT 1 FROM users
         WHERE temporary_account = false
           AND role = 'TEMP_PASSENGER'
    ) THEN
        RAISE EXCEPTION 'role TEMP_PASSENGER rows must be flagged temporary_account';
    END IF;
END $$;

COMMIT;
