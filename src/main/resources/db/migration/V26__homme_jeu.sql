-- Data for issue #93: "Centre-ville" is the town itself, not a fixed point on
-- the map (no lat/long, unlike the specific spots seeded by V9) — the
-- animateur roams the town for this stand. "Homme-jeu" is the corresponding
-- mobile stand: single animateur, HOMME_JEU typology, physically exhausting
-- (niveau_effort EPUISANT) per the issue.

INSERT INTO emplacement (id, nom, latitude, longitude) VALUES
    ('CENTRE-VILLE', 'Centre-ville', NULL, NULL)
ON CONFLICT (id) DO NOTHING;

INSERT INTO stand (id, nom, effectif_min, effectif_max, reserve_majeurs, premium, emplacement_id, niveau_effort) VALUES
    ('HOMME-JEU', 'Homme-jeu', 1, 1, FALSE, FALSE, 'CENTRE-VILLE', 'EPUISANT')
ON CONFLICT (id) DO NOTHING;

INSERT INTO stand_typologie (stand_id, typologie) VALUES
    ('HOMME-JEU', 'HOMME_JEU')
ON CONFLICT (stand_id, typologie) DO NOTHING;
