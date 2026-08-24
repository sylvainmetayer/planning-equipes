-- Poids d'une contrainte, au niveau de l'édition.
--
-- Jusqu'ici les poids ne vivaient que dans `application.properties`
-- (`planning.constraint-weights.<nom>`, lus une fois au démarrage) : un
-- réglage par déploiement, donc partagé par toutes les éditions, et
-- inaccessible à l'organisateur. Or ce qui se dose — l'importance relative des
-- règles de « Qualité d'organisation » — varie d'un organisateur à l'autre et
-- d'une année à l'autre, exactement comme les paramètres légaux.
--
-- Même convention que `constraint_toggle` (V8) : l'absence de ligne signifie
-- « valeur par défaut », c'est-à-dire celle de la configuration. Rien à
-- initialiser, et un déploiement qui retouche ses valeurs par défaut continue
-- de s'appliquer à toutes les éditions qui n'ont rien surchargé.
--
-- `poids` est borné à 1 : un poids nul reviendrait à désactiver la règle sans
-- passer par `constraint_toggle`, donc sans que l'écran Contraintes ne la
-- montre comme désactivée — et, pour une règle légale, sans la confirmation
-- qui la protège.

CREATE TABLE IF NOT EXISTS ponderation_contrainte (
    edition_id VARCHAR(64) NOT NULL DEFAULT 'DEFAUT' REFERENCES edition (id) ON DELETE CASCADE,
    nom VARCHAR(100) NOT NULL,
    poids INTEGER NOT NULL CHECK (poids >= 1),
    PRIMARY KEY (edition_id, nom)
);
