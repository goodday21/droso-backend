-- Adds users.token_version, the version of the access tokens of a user.
--
-- Every access token carries the version of its user in the claim "ver". A request is accepted only
-- while the claim equals the column, so incrementing the column revokes every token issued before
-- (on a deletion, a change of the password), without a list of the revoked tokens.
--
-- The existing users start at 0, like the new ones: their tokens are issued from now on anyway.
-- Adding a column with a constant default only changes the catalog in PostgreSQL 11+: the table is
-- not rewritten.

ALTER TABLE users ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0;
