-- « Les hommes jeu ne peuvent pas faire plus de 4 créneaux sur l'ensemble »
-- (issue #594). Rien ne permettait d'exprimer un quota : la contrainte de
-- typologies distinctes borne le NOMBRE de typologies d'une personne, pas le
-- volume sur l'une d'elles, et les contraintes ad hoc n'ont pas de notion de
-- quota. Le contournement — poser des indisponibilités forcées à la main — ne
-- tient pas : le plafond porte sur l'édition entière, donc sur une combinaison
-- que personne ne peut énumérer à l'avance.
--
-- La colonne est NULLABLE, et l'absence de valeur signifie « pas de plafond ».
-- Le choix de porter le quota sur la typologie plutôt que sur un cinquième
-- type de contrainte ad hoc est consigné dans l'ADR 0042.

ALTER TABLE typologie
    ADD COLUMN IF NOT EXISTS max_creneaux_par_animateur INTEGER;
