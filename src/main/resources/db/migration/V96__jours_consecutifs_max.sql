-- Le plafond de jours travaillés d'affilée vivait comme constante privée de
-- `QualiteConstraints`, à six, partagée par la règle dosée
-- `maxJoursConsecutifsTravailles` et par sa forme dure
-- `maxJoursConsecutifsTravaillesDur` (livrée éteinte, ADR 0045). Aucun article
-- du Code du travail ne fonde un décompte glissant — le repos hebdomadaire est
-- tenu en dur par `maxJoursTravaillesParSemaine` et `reposHebdomadaireMinimal`
-- — c'est donc une politique d'organisateur, et elle rejoint les autres seuils
-- de « Qualité d'organisation » : une colonne de `parametres_qualite`, réglable
-- par édition, comme `typologies_distinctes_max` l'a fait avant elle.
--
-- Pourquoi ce seuil mérite d'être réglé et pas seulement dosé : sur une grille
-- qui réclame presque tout le monde presque tous les jours, il ne décide pas du
-- confort mais de la faisabilité. La règle exige un jour de repos dans CHAQUE
-- fenêtre de (seuil + 1) jours ; un jour de plus ou de moins change le nombre
-- de jours-repos à trouver, et donc si un plan sans écart dur existe.
--
-- DEFAULT 6 sur les lignes existantes : une édition qui avait déjà réglé ses
-- autres seuils garde exactement le comportement qu'elle avait, le six étant
-- la valeur que la constante portait.

ALTER TABLE parametres_qualite
    ADD COLUMN IF NOT EXISTS jours_consecutifs_max INTEGER NOT NULL DEFAULT 6;
