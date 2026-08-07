-- Moves stand availability from the créneau level (creneau_stand_ouvert: a
-- whole-slot, per-timeslot open/closed toggle) to the stand level, and lets a
-- closure cover only part of a créneau (e.g. closed 14:00-16:00 inside a
-- 9:00-19:00 slot) instead of the whole thing. See docs/domaine.md and
-- docs/contraintes.md for how the solver now derives open sub-intervals of a
-- créneau from these rows (Creneau#segmentsOuvertsMinutes).
--
-- creneau_stand_ouvert is left in place, unused, rather than dropped here:
-- dropping a table is a destructive, hard-to-reverse change on a shared
-- database, better done as its own follow-up once nothing still reads it.

CREATE TABLE IF NOT EXISTS stand_indisponibilite (
    id BIGSERIAL PRIMARY KEY,
    stand_id VARCHAR(64) NOT NULL REFERENCES stand(id) ON DELETE CASCADE,
    date_indisponibilite DATE NOT NULL,
    heure_debut TIME NOT NULL,
    heure_fin TIME NOT NULL,
    motif VARCHAR(255),
    CHECK (heure_fin > heure_debut)
);

CREATE INDEX IF NOT EXISTS idx_stand_indisponibilite_stand_id ON stand_indisponibilite(stand_id);
