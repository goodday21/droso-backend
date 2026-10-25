-- Baseline schema of the educational project.
-- The health probe intentionally does not depend on any table: it runs "SELECT 1" only.

CREATE TABLE IF NOT EXISTS demo_item (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);