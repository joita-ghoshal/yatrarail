-- =====================================================================
-- YatraRail -- Phase 7: admin console and operations
-- =====================================================================
-- Phase 7 makes the administrative side of the system accountable and
-- queryable. Most of it is new endpoints and UI over existing tables; the
-- only new table is the audit trail:
--   * audit_logs -- who changed what, when, and (denormalised) under which role
--
-- Same rules as Phases 2-6: a brand-new database created by
-- `ddl-auto=update` already has this table, so this script is only needed
-- when upgrading an EXISTING Phase 1-6 database. Every statement is guarded
-- so running it twice is harmless. Run it once, as postgres, against the
-- yatrarail database, BEFORE booting with `ddl-auto=validate`.
--
--   psql -U postgres -d yatrarail -f backend/src/main/resources/db/phase7-migration.sql
-- =====================================================================

BEGIN;

-- ---------------------------------------------------------------------
-- 1. audit_logs -- the administrative audit trail
-- ---------------------------------------------------------------------
-- Append-only by construction: the application layer has a write path and a
-- read path and NO update or delete path, so "append-only" is a property of
-- the system rather than a convention someone has to remember.
--
-- `actor_id` is nullable so an action taken by the system itself (a scheduled
-- job, say) is representable, and ON DELETE SET NULL so deleting an account
-- cannot cascade a hole through history. The actor's NAME and ROLE are copied
-- into the row at write time, so renaming or deleting an account later cannot
-- rewrite what the log says happened.
CREATE TABLE IF NOT EXISTS audit_logs (
    id          bigserial    PRIMARY KEY,
    action      varchar(60)  NOT NULL,
    entity_type varchar(40)  NOT NULL,
    entity_id   bigint,
    summary     varchar(500) NOT NULL,
    actor_id    bigint       REFERENCES users(id) ON DELETE SET NULL,
    actor_role  varchar(20),
    created_at  timestamp    NOT NULL DEFAULT now(),
    CONSTRAINT ck_audit_summary CHECK (length(trim(summary)) > 0)
);

CREATE INDEX IF NOT EXISTS idx_audit_created ON audit_logs(created_at);
CREATE INDEX IF NOT EXISTS idx_audit_action  ON audit_logs(action);
CREATE INDEX IF NOT EXISTS idx_audit_entity  ON audit_logs(entity_type, entity_id);

-- Same reasoning as Phase 6's boarding_status: on a database Hibernate created
-- from the entity, created_at is NOT NULL but carries no default (the entity
-- stamps it in Java via @PrePersist), so a row inserted by any other tool would
-- fail instead of landing in a legal state. Backfill, then tighten.
UPDATE audit_logs SET created_at = now() WHERE created_at IS NULL;
ALTER TABLE audit_logs ALTER COLUMN created_at SET DEFAULT now();
ALTER TABLE audit_logs ALTER COLUMN created_at SET NOT NULL;

-- A summary is the one thing an operator reads, so an empty one is never
-- useful; guard it in DDL as well as in the service.
UPDATE audit_logs SET summary = '(unrecorded)' WHERE summary IS NULL OR length(trim(summary)) = 0;
ALTER TABLE audit_logs ALTER COLUMN summary SET NOT NULL;

ALTER TABLE audit_logs DROP CONSTRAINT IF EXISTS ck_audit_summary;
ALTER TABLE audit_logs ADD CONSTRAINT ck_audit_summary
    CHECK (length(trim(summary)) > 0);

COMMIT;
