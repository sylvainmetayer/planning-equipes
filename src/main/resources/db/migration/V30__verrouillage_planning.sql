-- Partial planning lock (issue #87): the parts of the schedule the user has
-- validated and does not want the solver to touch again. A row's presence
-- means "frozen"; deleting it unlocks. Persistent state, not a journal — the
-- same shape as constraint_toggle and contrainte_ad_hoc.
--
-- A lock carries exactly one target, matching its type (check constraint
-- below), and belongs to one groupe de créneaux: switching the active group
-- is switching planning, so the seats validated for another group are not the
-- ones being solved.

CREATE TABLE IF NOT EXISTS verrouillage_planning (
    id VARCHAR(64) PRIMARY KEY,
    type VARCHAR(32) NOT NULL,
    groupe_creneau_id VARCHAR(64) NOT NULL REFERENCES groupe_creneau(id) ON DELETE CASCADE,
    animateur_id VARCHAR(64) REFERENCES animateur(id) ON DELETE CASCADE,
    stand_id VARCHAR(64) REFERENCES stand(id) ON DELETE CASCADE,
    creneau_id BIGINT REFERENCES creneau(id) ON DELETE CASCADE,
    jour DATE,
    raison TEXT,
    cree_le TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT verrouillage_planning_cible_coherente CHECK (
        (type = 'ANIMATEUR' AND animateur_id IS NOT NULL AND stand_id IS NULL
            AND creneau_id IS NULL AND jour IS NULL)
        OR (type = 'STAND' AND stand_id IS NOT NULL AND animateur_id IS NULL
            AND creneau_id IS NULL AND jour IS NULL)
        OR (type = 'CRENEAU' AND creneau_id IS NOT NULL AND animateur_id IS NULL
            AND stand_id IS NULL AND jour IS NULL)
        OR (type = 'JOUR' AND jour IS NOT NULL AND animateur_id IS NULL
            AND stand_id IS NULL AND creneau_id IS NULL)
    )
);

-- The same target can only be frozen once per group; re-locking is a no-op
-- upsert rather than a duplicate row.
CREATE UNIQUE INDEX idx_verrouillage_planning_cible ON verrouillage_planning (
    groupe_creneau_id, type,
    COALESCE(animateur_id, ''), COALESCE(stand_id, ''),
    COALESCE(creneau_id, -1), COALESCE(jour, DATE '0001-01-01')
);
