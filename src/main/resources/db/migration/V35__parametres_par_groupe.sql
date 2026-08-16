-- Les quatre tables « singleton » deviennent « une ligne par groupe »
-- (docs/groupes.md §4.3) : leur colonne `id`, qui ne valait jamais que 1, est
-- remplacée par `groupe_id` comme clé primaire. Sans ça, l'édition 2026
-- partagerait ses paramètres légaux, ses paramètres de découpage, sa durée de
-- résolution et sa fraîcheur de planning avec l'édition 2025.
--
-- Supprimer la colonne `id` emporte avec elle la clé primaire et la contrainte
-- CHECK (id = 1) qui la référençaient : rien d'autre à démonter.

ALTER TABLE parametres_legaux DROP COLUMN id;
ALTER TABLE parametres_legaux ADD PRIMARY KEY (groupe_id);

ALTER TABLE parametres_decoupage DROP COLUMN id;
ALTER TABLE parametres_decoupage ADD PRIMARY KEY (groupe_id);

ALTER TABLE parametres_solveur DROP COLUMN id;
ALTER TABLE parametres_solveur ADD PRIMARY KEY (groupe_id);

ALTER TABLE planning_resolution DROP COLUMN id;
ALTER TABLE planning_resolution ADD PRIMARY KEY (groupe_id);

-- Une ligne de paramètres pour chaque groupe déjà connu (à ce stade, le seul
-- 'DEFAUT', qui a déjà la sienne). Les groupes créés ensuite reçoivent la leur
-- à la création ; et une ligne absente reste sans conséquence, le repository
-- retombant sur les valeurs par défaut du code.
INSERT INTO parametres_legaux (groupe_id) SELECT id FROM groupe ON CONFLICT (groupe_id) DO NOTHING;
INSERT INTO parametres_decoupage (groupe_id) SELECT id FROM groupe ON CONFLICT (groupe_id) DO NOTHING;
INSERT INTO parametres_solveur (groupe_id) SELECT id FROM groupe ON CONFLICT (groupe_id) DO NOTHING;
