-- Tracks which groupe de créneaux the last persisted solve was computed for,
-- so the UI can warn when the active group has since changed (the persisted
-- planning shown by the calendars would then be stale for it). Single-row
-- table: only the most recent solve is kept.
CREATE TABLE planning_resolution (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    groupe_creneau_id VARCHAR(64) REFERENCES groupe_creneau(id) ON DELETE SET NULL,
    resolu_le TIMESTAMP NOT NULL
);
