-- =====================================================================
-- YatraRail -- Phase 9: notification system and platform polish
-- =====================================================================
-- Phase 9 fixes the notification system rather than extending it. Two new
-- tables:
--   * notification_reads       -- per-person acknowledgement of a broadcast
--   * notification_preferences -- what a user has opted out of, and until when
--
-- WHY notification_reads exists: an announcement is ONE row with a NULL
-- user_id, which is the right model for authored content -- but it meant the
-- read flag was global. `notifications.is_read` was a single column on the
-- shared row, so the FIRST passenger to open a notice marked it read for
-- everybody, after which it looked read to people who had never seen it, and
-- the unread badge never counted announcements at all. A personal
-- notification is *delivered* to one person and keeps its read state on the
-- row; an announcement is *broadcast* and is acknowledged by a receipt. One
-- mechanism per meaning, so the two cannot disagree.
--
-- Why `notifications.type` stays VARCHAR even though the code now uses a
-- NotificationType enum: an @Enumerated(STRING) column makes Hibernate
-- generate a CHECK constraint listing every value, and `ddl-auto=update`
-- never widens a CHECK that already exists -- so the first new notification
-- type added after deployment would be rejected by the database and the
-- notifier would fail silently forever (exactly the trap B22 documents for
-- booking statuses). Nothing here constrains `type` for that reason.
--
-- Same rules as Phases 2-7: a brand-new database created by `ddl-auto=update`
-- already has these tables, so this script is only needed when upgrading an
-- EXISTING Phase 1-8 database. Every statement is guarded so running it
-- twice is harmless. Run it once, as postgres, against the yatrarail
-- database, BEFORE booting with `ddl-auto=validate`.
--
--   psql -U postgres -d yatrarail -f backend/src/main/resources/db/phase9-migration.sql
-- =====================================================================

BEGIN;

-- ---------------------------------------------------------------------
-- 1. notification_reads -- who has read which announcement
-- ---------------------------------------------------------------------
-- A receipt, not a copy: the notice itself stays a single authored row that
-- the audit trail points at, so "how many notices exist" is a row count
-- rather than a multiplication by the size of the user table.
--
-- The unique constraint is what makes "mark as read" safe to retry -- reading
-- the same notice twice records one receipt, not two.
--
-- Both FKs cascade. A receipt without its notice or without its reader is
-- meaningless, and neither a notice nor an account is ever deleted in normal
-- operation, so nothing that matters is lost by cleaning up after either.
CREATE TABLE IF NOT EXISTS notification_reads (
    id              bigserial    PRIMARY KEY,
    notification_id bigint       NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
    user_id         bigint       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    read_at         timestamp    NOT NULL DEFAULT now(),
    CONSTRAINT uk_notification_read UNIQUE (notification_id, user_id)
);

-- Serves the navbar bell, which resolves the reader's whole read state for
-- every announcement in one query on a 30-second timer for every signed-in
-- user. The leading column is the reader, because that is how it is queried.
CREATE INDEX IF NOT EXISTS idx_reads_user ON notification_reads(user_id, notification_id);

-- ---------------------------------------------------------------------
-- 2. notification_preferences -- what a user has opted out of
-- ---------------------------------------------------------------------
-- One row per user, created only when they actually change something. An
-- absent row means the defaults, so reading settings has no write side
-- effect and "delete my preferences" and "I never had any" are the same
-- state -- which is correct, because both mean the defaults apply.
--
-- There is deliberately no "mute everything" column. `muted_until` is a
-- deadline rather than a switch so a snooze expires on its own, and it
-- silences only the suppressible types: a passenger can decline an
-- announcement but cannot decline being told their own booking expired.
CREATE TABLE IF NOT EXISTS notification_preferences (
    id                   bigserial   PRIMARY KEY,
    user_id              bigint      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    announcements_enabled boolean    NOT NULL DEFAULT true,
    waitlist_alerts      varchar(20) NOT NULL DEFAULT 'ALL',
    muted_until          timestamp,
    updated_at           timestamp   NOT NULL DEFAULT now(),
    CONSTRAINT uk_notification_pref_user UNIQUE (user_id),
    CONSTRAINT ck_waitlist_alerts CHECK (waitlist_alerts IN ('ALL', 'PROMOTED_ONLY', 'OFF'))
);

-- Same reasoning as audit_logs.created_at in Phase 7: on a database Hibernate
-- created from the entity the column is NOT NULL but carries no default (the
-- entity stamps it in Java), so a row inserted by any other tool would fail
-- rather than land in a legal state. Give it the database default too, then
-- tighten it.
UPDATE notification_preferences SET updated_at = now() WHERE updated_at IS NULL;
ALTER TABLE notification_preferences ALTER COLUMN updated_at SET DEFAULT now();
ALTER TABLE notification_preferences ALTER COLUMN updated_at SET NOT NULL;

-- The CHECK above is created inside CREATE TABLE, so it is absent from a
-- table that already existed. Re-assert it name-agnostically, and drop a
-- stale narrower one first so widening the enum can never fail on a rerun.
ALTER TABLE notification_preferences DROP CONSTRAINT IF EXISTS ck_waitlist_alerts;
ALTER TABLE notification_preferences ADD CONSTRAINT ck_waitlist_alerts
    CHECK (waitlist_alerts IN ('ALL', 'PROMOTED_ONLY', 'OFF'));

-- ---------------------------------------------------------------------
-- 3. announcements are no longer "read" by a shared flag
-- ---------------------------------------------------------------------
-- Existing announcement rows were carrying is_read = false, and with the old
-- code that single column was everybody's read state at once. Two things are
-- true of them now:
--   * they are unread by EVERY reader, because no receipt exists for anybody.
--     Nothing is backfilled here on purpose -- inventing receipts for people
--     who never opened the notice would be a fabricated read count, which is
--     the one number an administrator would take at face value.
--   * the column on the row carries no information, so it is set to true to
--     mean "not applicable" rather than left reading as "nobody has read
--     this". The unread query tests for a receipt, never this column.
UPDATE notifications SET is_read = true WHERE user_id IS NULL;

COMMIT;