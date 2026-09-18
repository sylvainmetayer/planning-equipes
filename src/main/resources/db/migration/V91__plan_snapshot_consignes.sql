-- Un instantané porte les consignes en vigueur au moment de sa capture
-- (issue #4) : par date, la bande fermée et le motif.
--
-- Le contenu d'un instantané est dénormalisé (décision 0007) précisément pour
-- qu'il se lise sans le référentiel du moment. Les sièges d'un jour sous
-- consigne y sont déjà aux heures effectives ; ce que rien ne disait, c'est
-- POURQUOI ce jour-là n'avait pas ses heures habituelles. Sans cette colonne,
-- le comparateur mettrait face à face un plan nominal et un plan sous arrêté
-- comme deux réglages du solveur, et une publication relue six mois plus tard
-- ne saurait plus qu'un après-midi fermé l'a été par décision préfectorale.
--
-- NULL sur les instantanés capturés avant cette colonne : « inconnu », jamais
-- « aucune consigne ». Un tableau JSON vide dit, lui, qu'il n'y en avait pas.

ALTER TABLE plan_snapshot ADD COLUMN consignes JSONB;

COMMENT ON COLUMN plan_snapshot.consignes IS
    'Consignes governing the edition at capture time: [{date, fermetureDebut, fermetureFin, motif}]; NULL = unknown';
