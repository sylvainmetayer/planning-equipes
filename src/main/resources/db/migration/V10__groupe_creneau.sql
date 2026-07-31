-- Named groups of timeslots ("plannings"), so an alternate schedule can be
-- prepared ahead of time and swapped in on short notice. Exactly one group
-- is active at a time; the solver only builds its problem from the active
-- group's timeslots (see PlanningService.construireDepuisReferenceData).

CREATE TABLE IF NOT EXISTS groupe_creneau (
    id VARCHAR(64) PRIMARY KEY,
    nom VARCHAR(255) NOT NULL,
    actif BOOLEAN NOT NULL DEFAULT FALSE
);

-- Enforces "at most one active group" at the database level too: only rows
-- with actif = true are indexed, so a second such row would collide.
CREATE UNIQUE INDEX idx_groupe_creneau_actif_unique ON groupe_creneau (actif) WHERE actif;

INSERT INTO groupe_creneau (id, nom, actif) VALUES ('DEFAUT', 'Défaut', TRUE);

ALTER TABLE creneau ADD COLUMN groupe_creneau_id VARCHAR(64) NOT NULL
    REFERENCES groupe_creneau(id) DEFAULT 'DEFAUT';
