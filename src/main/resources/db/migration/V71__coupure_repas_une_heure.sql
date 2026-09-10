-- La coupure repas passe à une heure (issue #438).
--
-- Depuis que `coupureRepasObligatoire` lit ces fenêtres, la durée n'est plus
-- un paramètre de découpage : c'est la coupure qu'une journée à cheval sur une
-- fenêtre doit trouver, entièrement à l'intérieur. Une fenêtre de deux heures
-- offre alors exactement deux créneaux — 12-13 ou 13-14 le midi, 19-20 ou
-- 20-21 le soir — ce qui est l'organisation réelle de l'événement. Avec 45
-- minutes, la coupure ne tombait sur aucun créneau entier.
--
-- Le défaut de la colonne suit celui de ParametresDecoupage : une édition
-- créée après cette migration démarre à une heure.
ALTER TABLE parametres_decoupage
    ALTER COLUMN duree_pause_repas_minutes SET DEFAULT 60;

-- Les éditions existantes qui n'ont jamais touché au paramètre portent encore
-- l'ancien défaut. Elles sont remontées : personne ne l'a choisi, et le laisser
-- à 45 leur donnerait une règle dure qui ne découpe aucune de leurs fenêtres.
-- Une édition qui a saisi une autre valeur — quelle qu'elle soit — n'est pas
-- touchée.
UPDATE parametres_decoupage
SET duree_pause_repas_minutes = 60
WHERE duree_pause_repas_minutes = 45;
