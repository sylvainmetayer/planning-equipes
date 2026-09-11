-- When the evening starts, for the equity read-out (Équité screen): the
-- minutes an animateur works past this hour count as evening hours, and the
-- organiser compares them across the roster before publishing. A rule of the
-- organisation, not of the law — a minor's legal night stays hard-coded in
-- Creneau — so it sits with the other parameters the organiser sets per
-- edition. Default 20:00, the earliest legal night, so an existing edition
-- reads something sensible before anyone touches it.
ALTER TABLE parametres_legaux
    ADD COLUMN heure_debut_soiree TIME NOT NULL DEFAULT '20:00';

COMMENT ON COLUMN parametres_legaux.heure_debut_soiree IS
    'Start of the evening for the equity read-out: minutes worked past it count as evening hours';
