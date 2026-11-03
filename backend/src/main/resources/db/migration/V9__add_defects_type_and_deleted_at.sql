-- Adds the type of a defect and its soft deletion, and the statuses of its life cycle.
--
-- type: FEATURE or BUG, stored by name like the other enums. The existing defects get FEATURE, the
-- default. Since PostgreSQL 11 adding a column with a constant default only changes the catalog:
-- the table is not rewritten.
--
-- deleted_at: a defect is soft-deleted the way a user is (V3); NULL means active. A deleted defect
-- is absent for the API. The existing defects stay active.
--
-- status: the CHECK constraint lists every value of org.example.defect.DefectStatus. The longest
-- name (READY_TO_RELEASE) fills the VARCHAR(16) column. The transitions between the statuses are
-- decided by the application, not by this table.

ALTER TABLE defects ADD COLUMN type VARCHAR(16) NOT NULL DEFAULT 'FEATURE';
ALTER TABLE defects ADD CONSTRAINT defects_type_check CHECK (type IN ('FEATURE', 'BUG'));

ALTER TABLE defects ADD COLUMN deleted_at TIMESTAMPTZ;

ALTER TABLE defects DROP CONSTRAINT defects_status_check;
ALTER TABLE defects ADD CONSTRAINT defects_status_check CHECK (status IN ('NEW', 'IN_DEVELOPMENT',
    'IN_REVIEW', 'READY_FOR_QA', 'IN_QA', 'WAITING_FOR_FIX', 'READY_TO_RELEASE', 'RELEASED', 'DECLINED'));
