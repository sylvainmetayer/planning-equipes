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
-- DEFAULT 8, et non le six que portait la constante : mesuré sur
-- `festival-hivernal`, forme dure allumée, la règle laisse 22 sièges vides à
-- six jours, sept à sept jours, et atteint zéro écart dur à huit. Les éditions
-- existantes reçoivent donc le nouveau défaut, pas l'ancien seuil — un plafond
-- que rien ne peut satisfaire vaut moins qu'un plafond plus large qui tient, et
-- la règle étant dosée par défaut, ce que ce backfill change est le nombre de
-- pénalités medium, jamais la validité d'un plan. Une édition qui veut six
-- jours les règle sur la page Paramètres.

ALTER TABLE parametres_qualite
    ADD COLUMN IF NOT EXISTS jours_consecutifs_max INTEGER NOT NULL DEFAULT 8;
