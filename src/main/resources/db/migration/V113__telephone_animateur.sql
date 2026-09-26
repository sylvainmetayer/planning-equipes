-- Le téléphone de l'animateur : le remplaçant, le jour J, il faut l'appeler.
--
-- Une donnée de contact de plus sur la fiche, lue par l'administration (fiche,
-- formulaire, écran Aujourd'hui) et saisie ou importée comme l'adresse e-mail.
-- Elle ne sort jamais par l'assistant MCP, l'affichage mural, les PDF, le
-- calendrier ICS ni les exports CSV ; elle suit la fiche dans la sauvegarde et
-- dans le fichier de scénario (docs/rgpd.md, docs/securite.md).
ALTER TABLE animateur ADD COLUMN telephone VARCHAR(32);
