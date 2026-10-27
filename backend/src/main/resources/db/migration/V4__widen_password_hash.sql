-- Widens users.password_hash from 100 to 255 characters.
--
-- The hash is stored with the id of its algorithm by the DelegatingPasswordEncoder, so that another
-- algorithm can be introduced without a reset. 100 fits BCrypt ("{bcrypt}" + 60 = 68 characters)
-- but not the Argon2id of Spring Security with its default parameters:
-- "{argon2}$argon2id$v=19$m=16384,t=2,p=1$" + 22 characters of salt + "$" + 43 of hash = 105.
-- 255 leaves room for higher parameters as well. A VARCHAR stores its actual length, so the larger
-- limit costs nothing.
--
-- Increasing the length of a VARCHAR only changes the catalog in PostgreSQL: the table is neither
-- rewritten nor scanned.

ALTER TABLE users ALTER COLUMN password_hash TYPE VARCHAR(255);
