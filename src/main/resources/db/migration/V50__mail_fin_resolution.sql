-- Notification par e-mail à la fin d'une résolution.
--
-- Le réglage vit avec la durée de résolution, dans `parametres_solveur` : même
-- portée (l'édition), même moment de lecture (le job de solve), même écran de
-- réglage. Une table de plus pour un booléen n'aurait rien apporté.
--
-- Par édition, et non global : on veut être prévenu de la résolution qu'on
-- attend, pas de chaque essai lancé sur une édition de test. FALSE par défaut,
-- parce qu'un envoi d'e-mail ne doit jamais s'activer tout seul — et qu'il
-- reste sans effet tant qu'aucune adresse administrateur n'est configurée
-- (MAIL_ADMIN).

ALTER TABLE parametres_solveur
    ADD COLUMN IF NOT EXISTS mail_fin_resolution BOOLEAN NOT NULL DEFAULT FALSE;
