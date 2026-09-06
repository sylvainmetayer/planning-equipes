-- La famille de relais devient un attribut du stand (issue #390). Jusqu'ici
-- elle se recalculait à chaque construction du problème par le rang du stand
-- dans la liste triée des identifiants : un stand ajouté qui se classait avant
-- les autres faisait glisser tous les suivants d'une famille, et le plan
-- publié perdait toutes ses lignes. NULL = pas encore attribuée ; le prochain
-- calcul (ou la création du stand) la fixe dans la famille la moins peuplée.
ALTER TABLE stand ADD COLUMN famille INTEGER;

-- Figer la répartition en vigueur : le même rang par identifiant, dans le même
-- ordre que Java (comparaison binaire, d'où la collation C), modulo le nombre
-- de familles de la grille de l'édition. Aucun plan ne change au déploiement.
--
-- Seulement là où la grille est décalée : sur une édition à une seule famille
-- la valeur ne dirait rien, et figer tout le monde en famille 0 affamerait les
-- autres familles le jour où une grille décalée arrive sans remplacer
-- celle-ci. NULL veut dire « pas encore attribuée », ce que la répartition
-- suivante traite comme avant.
UPDATE stand s
SET famille = rangs.rang % (SELECT MAX(c.famille) + 1 FROM creneau c WHERE c.edition_id = s.edition_id)
FROM (SELECT edition_id, id, ROW_NUMBER() OVER (PARTITION BY edition_id ORDER BY id COLLATE "C") - 1 AS rang FROM stand) rangs
WHERE rangs.edition_id = s.edition_id AND rangs.id = s.id
  AND (SELECT COALESCE(MAX(c.famille), 0) FROM creneau c WHERE c.edition_id = s.edition_id) > 0;
