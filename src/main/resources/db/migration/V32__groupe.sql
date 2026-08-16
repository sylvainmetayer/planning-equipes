-- Le « groupe » (une édition : « Année 2025 », « Année 2026 ») est le périmètre
-- complet du référentiel : stands, animateurs, typologies, emplacements,
-- paramètres et résultat de solveur. Il englobe `groupe_creneau`, qui reste ce
-- qu'il a toujours été — une grille de créneaux alternative *à l'intérieur*
-- d'un groupe (V10). Voir docs/groupes.md §3 pour l'arbitrage entre les deux
-- niveaux et pourquoi `groupe_creneau` n'a pas simplement été promu.
--
-- Cette migration ne fait qu'introduire la table et sa ligne `DEFAUT` : rien
-- ne la référence encore, donc une base existante est strictement inchangée.

CREATE TABLE groupe (
    id      VARCHAR(64) PRIMARY KEY,
    nom     VARCHAR(255) NOT NULL,
    defaut  BOOLEAN NOT NULL DEFAULT FALSE,
    cree_le TIMESTAMP NOT NULL DEFAULT now()
);

-- Un seul groupe par défaut, garanti côté base : seules les lignes defaut =
-- true sont indexées, donc une deuxième entrerait en collision. Même motif que
-- groupe_creneau.actif (V10).
CREATE UNIQUE INDEX idx_groupe_defaut_unique ON groupe (defaut) WHERE defaut;

-- `defaut` n'est *pas* « le groupe courant » : celui-là est désigné par le
-- client à chaque requête (en-tête X-Groupe-Id, cf. docs/groupes.md §5). C'est
-- le repli pour tout appelant qui n'en désigne aucun — export CLI, tâche
-- planifiée, appel d'API direct.
INSERT INTO groupe (id, nom, defaut) VALUES ('DEFAUT', 'Groupe par défaut', TRUE);
