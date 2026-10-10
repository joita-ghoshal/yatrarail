-- =====================================================================
-- YatraRail -- Phase 10: indexes and query readiness for production scale
-- =====================================================================
-- Phase 10 adds no tables. It adds the indexes the read paths introduced or
-- exposed across Phases 5-9 actually need, so that the admin ledgers, the
-- reports and the notification feed stay fast as the data grows.
--
-- WHY THIS IS A SEPARATE SCRIPT FROM THE ENTITY ANNOTATIONS
-- A brand-new database created by `ddl-auto=update`/`validate` gets every
-- index declared in an @Table annotation automatically. An EXISTING database
-- that was created before Phase 10 does not -- `ddl-auto=update` adds the
-- indexes whose names it does not already see, but the point of this script
-- is to make that upgrade explicit, reviewable and safe to run against a
-- live database. Every statement here matches an @Index annotation exactly
-- (same name, same columns), so running it against a database Hibernate has
-- already indexed is a no-op.
--
-- EVERY STATEMENT IS GUARDED with IF NOT EXISTS, so running this twice -- or
-- running it against a database that already has the index -- is harmless.
-- Run it once, as the database owner, against the yatrarail database:
--
--   psql -U postgres -d yatrarail -f backend/src/main/resources/db/phase10-migration.sql
--
-- WHY THESE AND NOT OTHERS
-- An index is not free: it slows every write and costs storage. Each index
-- below answers a query the platform actually runs, and the comment names it.
-- Nothing speculative is added.
-- =====================================================================

-- Plain CREATE INDEX (not CONCURRENTLY) so the whole script fits in one
-- transaction and is therefore all-or-nothing. On a very large live table a
-- DBA may prefer to run the statements individually as CREATE INDEX
-- CONCURRENTLY, outside a transaction, to avoid holding a write lock; the
-- statements below are written to be lifted out and run that way unchanged.
BEGIN;

-- ---------------------------------------------------------------------
-- bookings
-- ---------------------------------------------------------------------

-- Admin booking / payment ledgers: ORDER BY created_at DESC, paged.
CREATE INDEX IF NOT EXISTS idx_booking_created
    ON bookings (created_at);

-- A passenger's own booking list: WHERE user_id = ? ORDER BY created_at DESC.
CREATE INDEX IF NOT EXISTS idx_booking_user_created
    ON bookings (user_id, created_at);

-- The operations report: WHERE journey_date BETWEEN ? AND ?, grouped by date
-- and by train. idx_booking_train_date leads with train_id, so it cannot
-- serve the date-only range the report uses across all trains.
CREATE INDEX IF NOT EXISTS idx_booking_journey_date
    ON bookings (journey_date);

-- ---------------------------------------------------------------------
-- payments
-- ---------------------------------------------------------------------

-- The admin payment ledger: ORDER BY created_at DESC, paged and searched.
-- booking_id is already indexed by its unique constraint on the settled row.
CREATE INDEX IF NOT EXISTS idx_payment_created
    ON payments (created_at);

-- ---------------------------------------------------------------------
-- cancellations
-- ---------------------------------------------------------------------

-- The admin refund ledger: ORDER BY cancelled_at DESC, paged.
CREATE INDEX IF NOT EXISTS idx_cancel_cancelled_at
    ON cancellations (cancelled_at);

-- A refund reference quoted in a support conversation, searched verbatim.
CREATE INDEX IF NOT EXISTS idx_cancel_refund_ref
    ON cancellations (refund_reference);

-- ---------------------------------------------------------------------
-- notifications
-- ---------------------------------------------------------------------

-- The notification feed, per user and platform-wide: ORDER BY created_at DESC.
CREATE INDEX IF NOT EXISTS idx_notif_created
    ON notifications (created_at);

-- ---------------------------------------------------------------------
-- audit_logs
-- ---------------------------------------------------------------------

-- The audit console filtered by actor: WHERE actor_id = ? ORDER BY created_at
-- DESC. The Phase 7 idx_audit_created serves the unfiltered page but not this
-- combination.
CREATE INDEX IF NOT EXISTS idx_audit_actor
    ON audit_logs (actor_id, created_at);

-- ---------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------

-- The admin user list, filtered by role and active state.
CREATE INDEX IF NOT EXISTS idx_user_role_active
    ON users (role, active);

-- The temporary-account sweep and the per-request session check both resolve
-- temporary accounts by their expiry deadline. This index was created by
-- phase3-migration.sql under the same name, so on any migrated database this
-- statement is a deliberate no-op; it is restated here so the script matches
-- the @Index annotations in the entities exactly (the stated contract above).
CREATE INDEX IF NOT EXISTS idx_users_temp_expiry
    ON users (temporary_account, temp_expires_at);

COMMIT;

-- =====================================================================
-- Verification (read-only, informational -- not part of the migration):
--
--   SELECT indexname FROM pg_indexes
--    WHERE schemaname = 'public'
--      AND indexname IN ('idx_booking_created','idx_booking_user_created',
--                        'idx_booking_journey_date','idx_payment_created',
--                        'idx_cancel_cancelled_at','idx_cancel_refund_ref',
--                        'idx_notif_created','idx_audit_actor',
--                        'idx_user_role_active','idx_users_temp_expiry')
--    ORDER BY indexname;
-- =====================================================================
