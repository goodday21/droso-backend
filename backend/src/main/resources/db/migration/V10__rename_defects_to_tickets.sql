-- Renames the defects to tickets: a ticket is a feature or a bug (its type, V9), not only a defect.
--
-- Only the names change: the rows, their ids and the next id of the identity are kept. A rename only
-- changes the catalog, the table is not rewritten. Every object named after the table is renamed
-- too, so that the names stay predictable (the application recognizes a violation by the name of
-- its constraint, see tickets_created_by_fkey) and no "defects_" is left in the schema.
-- The earlier migrations (V2, V6, V9) keep the old names: they are history and are never edited.

ALTER TABLE defects RENAME TO tickets;

-- The sequence behind the identity column id; it stays owned by tickets.id.
ALTER SEQUENCE defects_id_seq RENAME TO tickets_id_seq;

-- Renaming the primary key constraint renames its index as well.
ALTER TABLE tickets RENAME CONSTRAINT defects_pkey TO tickets_pkey;
ALTER TABLE tickets RENAME CONSTRAINT defects_priority_check TO tickets_priority_check;
ALTER TABLE tickets RENAME CONSTRAINT defects_status_check TO tickets_status_check;
ALTER TABLE tickets RENAME CONSTRAINT defects_type_check TO tickets_type_check;
ALTER TABLE tickets RENAME CONSTRAINT defects_created_by_fkey TO tickets_created_by_fkey;

ALTER INDEX defects_created_by_idx RENAME TO tickets_created_by_idx;

-- What a role may do is decided by the application (Role.permissions()); the descriptions only
-- document it.
UPDATE roles SET description = 'Reads and creates the tickets' WHERE code = 'USER';
UPDATE roles SET description = 'Reads, creates and edits the tickets' WHERE code = 'MANAGER';
UPDATE roles SET description = 'Reads, creates and edits the tickets, manages the users' WHERE code = 'ADMIN';
