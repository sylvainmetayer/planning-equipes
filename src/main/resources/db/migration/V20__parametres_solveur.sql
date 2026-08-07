-- Single-row table for the admin-configurable solver termination duration
-- (Débogage tab). Was client-side only (localStorage), which made the value
-- look inconsistent across browsers; now persisted so every client reads and
-- writes the same setting. Default matches `planning.solver.seconds-limit`
-- in application.properties.

CREATE TABLE IF NOT EXISTS parametres_solveur (
    id INTEGER PRIMARY KEY,
    duree_resolution_secondes INTEGER NOT NULL DEFAULT 180,
    CONSTRAINT parametres_solveur_singleton CHECK (id = 1)
);

INSERT INTO parametres_solveur (id, duree_resolution_secondes)
VALUES (1, 180)
ON CONFLICT (id) DO NOTHING;
