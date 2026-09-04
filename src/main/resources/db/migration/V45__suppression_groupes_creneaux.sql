-- Issue #172: the edition becomes the ONLY variant carrier. Timeslot groups
-- only ever scoped the créneaux — every other referential (stands, hours,
-- parameters, animateurs) stayed edition-wide, so a "variant" group silently
-- mixed referentials (the 1708 / canicule incident). Editions already
-- scope everything, persisted plan included: the half-measure goes away, the
-- découpage now replaces the edition's créneaux in place.
--
-- Data policy: each edition keeps the créneaux (and locks) of its ACTIVE
-- group — the one every solve and screen was actually using; the other
-- groups' grids belonged to superseded scenarios. Snapshots survive untouched
-- (denormalised JSONB), they only lose their group tag columns.

DELETE FROM creneau c
WHERE NOT EXISTS (
    SELECT 1 FROM groupe_creneau g
    WHERE g.edition_id = c.edition_id AND g.id = c.groupe_creneau_id AND g.actif
);

DELETE FROM verrouillage_planning v
WHERE NOT EXISTS (
    SELECT 1 FROM groupe_creneau g
    WHERE g.edition_id = v.edition_id AND g.id = v.groupe_creneau_id AND g.actif
);

-- Dropping the columns drops every index/constraint that references them,
-- including the "one target locked once per group" unique index — recreated
-- below at the edition level, its new natural scope.
ALTER TABLE creneau DROP COLUMN groupe_creneau_id;
ALTER TABLE verrouillage_planning DROP COLUMN groupe_creneau_id;

CREATE UNIQUE INDEX idx_verrouillage_cible_edition ON verrouillage_planning (
    edition_id, type,
    COALESCE(animateur_id, ''), COALESCE(stand_id, ''),
    COALESCE(creneau_id, -1), COALESCE(jour, DATE '0001-01-01')
);

ALTER TABLE planning_resolution DROP COLUMN groupe_creneau_id;
ALTER TABLE plan_snapshot DROP COLUMN groupe_creneau_id;
ALTER TABLE plan_snapshot DROP COLUMN groupe_nom;
ALTER TABLE demande_echange DROP COLUMN groupe_creneau_id;

DROP TABLE groupe_creneau;
