-- Renomme `animateur.jeton_acces` en `animateur.access_token`.
--
-- Le code Java dit `token` partout depuis la remédiation franglais du backend
-- ; la colonne, elle, était restée `jeton_acces` parce qu'elle se lit hors du
-- dépôt. Le décalage était documenté à trois endroits, ce qui est le signe
-- qu'il coûtait quelque chose à chaque lecture. Cette migration le supprime.
--
-- L'opération est instantanée et ne perd aucune donnée : un RENAME COLUMN
-- PostgreSQL ne réécrit pas la table, et l'index unique est renommé plutôt que
-- reconstruit. Les jetons déjà distribués gardent donc leur valeur, et les
-- liens « espace animateur » imprimés sur les PDF continuent d'ouvrir l'espace
-- de leur propriétaire.
--
-- RUPTURE ASSUMÉE — les dumps SQL pris avant cette migration.
--
-- `DatabaseDumpService` écrit des INSERT qui nomment leurs colonnes une à une
-- (il les lit dans les métadonnées du ResultSet). Un dump produit avant cette
-- migration contient donc littéralement
--
--     INSERT INTO animateur (edition_id, id, ..., jeton_acces) VALUES (...)
--
-- et son réimport par l'écran Débogage échouera sur « column "jeton_acces" of
-- relation "animateur" does not exist ». C'est délibéré : aucun code de
-- compatibilité n'est ajouté pour le rattraper. Un dump est une sauvegarde
-- ponctuelle, pas un format d'échange, et faire vivre les deux noms en
-- parallèle rendrait permanent exactement le décalage qu'on supprime ici.
--
-- Pour réimporter un vieux dump malgré tout : y remplacer `jeton_acces` par
-- `access_token` avant de le charger. C'est la seule occurrence à toucher.

ALTER TABLE animateur RENAME COLUMN jeton_acces TO access_token;

ALTER INDEX idx_animateur_jeton_acces RENAME TO idx_animateur_access_token;
