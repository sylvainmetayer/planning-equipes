-- Budget de calcul par édition : la durée devient facultative, l'arrêt sur
-- plateau devient réglable.
--
-- duree_resolution_secondes NULL = suivre le défaut du déploiement
-- (planning.solver.seconds-limit) ; c'est ce qu'écrit « Revenir au défaut ».
-- Les lignes restées à 900, le défaut livré que l'écran recopiait tel quel à
-- chaque enregistrement, repassent à NULL : sur un déploiement qui n'a pas
-- changé ce défaut, rien ne bouge ; sur un déploiement qui l'a changé, ces
-- éditions suivent enfin la valeur de l'exploitant au lieu d'une copie figée.
ALTER TABLE parametres_solveur
    ALTER COLUMN duree_resolution_secondes DROP NOT NULL,
    ALTER COLUMN duree_resolution_secondes DROP DEFAULT;

UPDATE parametres_solveur
SET duree_resolution_secondes = NULL
WHERE duree_resolution_secondes = 900;

-- Arrêt si le planning, déjà faisable, ne s'améliore plus depuis N secondes.
-- 0 = jamais ; NULL = défaut du déploiement
-- (planning.solver.unimproved-seconds-limit).
ALTER TABLE parametres_solveur ADD COLUMN plateau_secondes INTEGER;

-- La file persistée garde le budget entier d'un job, pas seulement sa durée :
-- un job repris après redémarrage tourne avec ce qu'on lui avait promis.
-- capped_from_* disent pourquoi ce budget diffère du réglage de l'édition :
-- la valeur enregistrée qu'un plafond a coupée (plafond abaissé depuis, ou
-- scénario importé au-dessus), NULL quand elle a tourné telle quelle. Des
-- valeurs, pas une phrase : chaque lecteur la dit dans sa langue.
ALTER TABLE solver_job
    ADD COLUMN plateau_seconds BIGINT,
    ADD COLUMN capped_from_seconds_limit BIGINT,
    ADD COLUMN capped_from_plateau_seconds BIGINT;
