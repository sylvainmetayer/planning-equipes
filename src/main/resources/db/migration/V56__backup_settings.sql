-- Sauvegarde automatique de la base : l'interrupteur et le compte rendu de la
-- dernière exécution.
--
-- Table GLOBALE, sans `edition_id`, et c'est la seule chose qu'il faut retenir
-- de sa forme : une sauvegarde `pg_dump` prend l'instance entière, toutes
-- éditions confondues. La ranger dans `parametres_solveur` (par édition) aurait
-- donné autant d'interrupteurs que d'éditions pour une seule tâche de nuit,
-- dont on ne saurait plus lequel l'a déclenchée.
--
-- Une seule ligne, garantie par la contrainte sur `id` : le réglage n'a pas de
-- clé métier, et une deuxième ligne ne voudrait rien dire.
--
-- L'emplacement des fichiers et le nombre de dumps conservés ne sont PAS ici :
-- ce sont des variables d'environnement (BACKUP_DIR, BACKUP_RETENTION). Un
-- chemin de disque et une profondeur de rétention se règlent avec le volume
-- qui les porte, pas depuis un navigateur — l'écran Paramètres les affiche,
-- il ne les écrit pas.
--
-- `actif` est à TRUE par défaut : la fonctionnalité ne démarre de toute façon
-- que si BACKUP_DIR est renseignée, et l'opérateur qui monte un volume de
-- sauvegarde veut qu'il se remplisse sans avoir à cocher quoi que ce soit.
-- L'interrupteur sert à la SUSPENDRE, pas à la mettre en service.

CREATE TABLE IF NOT EXISTS backup_settings (
    id             BOOLEAN     NOT NULL PRIMARY KEY DEFAULT TRUE CHECK (id),
    actif          BOOLEAN     NOT NULL DEFAULT TRUE,
    -- Compte rendu de la dernière tentative, succès comme échec : une
    -- sauvegarde qui ne tourne plus doit se voir sur l'écran, pas seulement
    -- dans les journaux du conteneur.
    derniere_tentative  TIMESTAMPTZ,
    dernier_succes      BOOLEAN,
    dernier_fichier     TEXT,
    dernier_message     TEXT
);

INSERT INTO backup_settings (id) VALUES (TRUE) ON CONFLICT (id) DO NOTHING;
