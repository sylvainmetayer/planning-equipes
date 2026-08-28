-- Durée de résolution par défaut : 180 → 900 s.
--
-- Mesuré en Timefold 2.5 sur le profil de production (3 499 postes, 153
-- animateurs) : la faisabilité arrive à 333 s là où la 1.34 y parvenait en
-- 137 s. À 180 s le solveur rendait donc un plan à -10 hard — des places que
-- personne ne tient — sans lever la moindre erreur, l'arrêt sur plateau étant
-- conditionné à la faisabilité et donc incapable de le signaler.
--
-- L'UPDATE ne vise que les éditions restées sur l'ancien défaut : une durée
-- choisie explicitement par un administrateur reste la sienne.

ALTER TABLE parametres_solveur
    ALTER COLUMN duree_resolution_secondes SET DEFAULT 900;

UPDATE parametres_solveur
SET duree_resolution_secondes = 900
WHERE duree_resolution_secondes = 180;
