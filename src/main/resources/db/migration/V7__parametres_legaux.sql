-- Single-row table for admin-configurable legal parameters. Default is 48h
-- (2880 min), the weekly working-time ceiling set by the Code du travail
-- (art. L3121-20) and the Convention collective nationale de l'Animation
-- (ÉCLAT, IDCC 1518, art. 5.2).

CREATE TABLE IF NOT EXISTS parametres_legaux (
    id INTEGER PRIMARY KEY,
    duree_hebdomadaire_max_minutes INTEGER NOT NULL DEFAULT 2880,
    CONSTRAINT parametres_legaux_singleton CHECK (id = 1)
);

INSERT INTO parametres_legaux (id, duree_hebdomadaire_max_minutes)
VALUES (1, 2880)
ON CONFLICT (id) DO NOTHING;
