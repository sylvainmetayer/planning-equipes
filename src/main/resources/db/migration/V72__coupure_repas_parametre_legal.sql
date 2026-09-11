-- The meal break — its length and its midday and evening windows — leaves the
-- découpage parameters for the legal ones: it is a rule of the event the solver
-- judges on every grid (coupureRepasObligatoire, issue #438), not a slicing
-- hint, and the organiser looks for it next to the other rules. Values are
-- carried over edition by edition; the découpage keeps reading them from their
-- new home to place the reliefs.
ALTER TABLE parametres_legaux
    ADD COLUMN coupure_repas_minutes INTEGER NOT NULL DEFAULT 60,
    ADD COLUMN coupure_repas_midi_debut TIME NOT NULL DEFAULT '12:00',
    ADD COLUMN coupure_repas_midi_fin TIME NOT NULL DEFAULT '14:00',
    ADD COLUMN coupure_repas_soir_debut TIME NOT NULL DEFAULT '19:00',
    ADD COLUMN coupure_repas_soir_fin TIME NOT NULL DEFAULT '21:00';

-- An edition that set its découpage but never its legal parameters has no row
-- here yet: give it one, so its meal windows survive the move.
INSERT INTO parametres_legaux (edition_id)
SELECT d.edition_id
FROM parametres_decoupage d
WHERE NOT EXISTS (SELECT 1 FROM parametres_legaux l WHERE l.edition_id = d.edition_id);

UPDATE parametres_legaux l
SET coupure_repas_minutes = d.duree_pause_repas_minutes,
    coupure_repas_midi_debut = d.fenetre_repas_midi_debut,
    coupure_repas_midi_fin = d.fenetre_repas_midi_fin,
    coupure_repas_soir_debut = d.fenetre_repas_soir_debut,
    coupure_repas_soir_fin = d.fenetre_repas_soir_fin
FROM parametres_decoupage d
WHERE d.edition_id = l.edition_id;

ALTER TABLE parametres_decoupage
    DROP COLUMN duree_pause_repas_minutes,
    DROP COLUMN fenetre_repas_midi_debut,
    DROP COLUMN fenetre_repas_midi_fin,
    DROP COLUMN fenetre_repas_soir_debut,
    DROP COLUMN fenetre_repas_soir_fin;
