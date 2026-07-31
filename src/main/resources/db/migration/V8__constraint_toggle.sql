-- Tracks which constraints are disabled for the next solve. A row's presence
-- means the constraint (identified by its asConstraint(...) name) is
-- disabled; absence means active, so every constraint is active by default
-- with no seeding required.

CREATE TABLE IF NOT EXISTS constraint_toggle (
    nom VARCHAR(100) PRIMARY KEY
);
