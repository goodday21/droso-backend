-- Adds defects.created_by, the user who reported a defect, and aligns the descriptions of the roles
-- with the permissions of org.example.user.Role.
--
-- The existing defects were created before the users existed and keep NULL: their author is
-- unknown. Every new defect gets the user of the access token. The users are never deleted for
-- real (deleted_at), so the reference cannot dangle; ON DELETE stays the default (NO ACTION) all
-- the same, so that a hard delete by hand fails instead of losing the authors.
--
-- Adding a nullable column without a default only changes the catalog: the table is not rewritten.
-- The index serves the lookups by author (the defects of a user) and the check of the foreign key
-- when a user row changes its key, which PostgreSQL does not index on its own.

ALTER TABLE defects ADD COLUMN created_by BIGINT REFERENCES users (id);

CREATE INDEX defects_created_by_idx ON defects (created_by);

-- What a role may do is decided by the application (Role.permissions()); the descriptions only
-- document it. Since TASK-008 every user may report a defect.
UPDATE roles SET description = 'Reads and creates the defects' WHERE code = 'USER';
UPDATE roles SET description = 'Reads, creates and edits the defects' WHERE code = 'MANAGER';
UPDATE roles SET description = 'Reads, creates and edits the defects, manages the users' WHERE code = 'ADMIN';
