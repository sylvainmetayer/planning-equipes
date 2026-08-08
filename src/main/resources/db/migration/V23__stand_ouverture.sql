-- The inverse of stand_indisponibilite (V21): an explicit opening window for
-- a stand normally closed and only staffed during specific windows, so the
-- organiser doesn't have to enter every other closure by hand. See
-- OuvertureStand's javadoc and docs/domaine.md for the three-state rule this
-- implies (open all day / open-by-default with closures / closed-by-default
-- with openings) and Creneau#segmentsOuvertsMinutes for how the solver picks
-- between them per calendar day.
--
-- No CHECK across stand_indisponibilite and stand_ouverture for "not both on
-- the same day": cross-table constraints aren't expressible as a plain CHECK
-- in Postgres, so that rule is enforced application-side, in
-- ReferenceDataService, at write time.

CREATE TABLE IF NOT EXISTS stand_ouverture (
    id BIGSERIAL PRIMARY KEY,
    stand_id VARCHAR(64) NOT NULL REFERENCES stand(id) ON DELETE CASCADE,
    date_ouverture DATE NOT NULL,
    heure_debut TIME NOT NULL,
    heure_fin TIME NOT NULL,
    motif VARCHAR(255),
    CHECK (heure_fin > heure_debut)
);

CREATE INDEX IF NOT EXISTS idx_stand_ouverture_stand_id ON stand_ouverture(stand_id);
