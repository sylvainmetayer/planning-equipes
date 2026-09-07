-- Historique des actions (issue #406) : ce qui a été fait dans l'application,
-- et par qui. Une ligne par action aboutie ou refusée.
--
-- Rien de nominatif n'y entre, comme dans `notification_planifiee` : un
-- identifiant d'animateur au plus, jamais un nom, une adresse ni une date de
-- naissance — l'identité est jointe à la lecture depuis le référentiel, si
-- bien qu'une fiche supprimée laisse une ligne qui ne nomme plus personne
-- (docs/rgpd.md). `champs` porte les noms des champs modifiés, jamais leurs
-- valeurs : « nom, email » dit ce qui a bougé sans dire ce que c'est devenu.
--
-- Cloisonné par édition et en cascade sur elle : l'historique d'une édition
-- supprimée disparaît avec ce qu'il décrit. Une purge glissante
-- (JOURNAL_RETENTION) le borne dans le temps entre deux suppressions.
--
-- Table en ajout seul : pas de `modifie_le`, pas de précondition d'écriture —
-- une ligne d'historique n'est jamais rééditée.
--
-- Deux conséquences assumées, comme pour `notification_planifiee` : dupliquer
-- une édition ne recopie PAS cette table — une édition neuve naît sans passé,
-- et hériter de l'historique d'une autre serait un contresens ; et le dump de
-- la base ne l'emporte pas — c'est une trace d'exploitation, pas une donnée de
-- référence à restaurer ailleurs.
CREATE TABLE IF NOT EXISTS journal_action (
    edition_id TEXT        NOT NULL REFERENCES edition (id) ON DELETE CASCADE,
    id         BIGSERIAL,
    survenu_le TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- ADMIN, ANIMATEUR, ASSISTANT (MCP) ou SYSTEME (tâches de nuit).
    acteur     TEXT        NOT NULL,
    -- « admin », un identifiant d'animateur, ou NULL pour le système.
    acteur_id  TEXT,
    -- Code de l'action, décrit en français par CatalogueActions.
    action     TEXT        NOT NULL,
    -- Type et identifiant de ce sur quoi l'action a porté ; NULL quand elle ne
    -- vise rien de nommable (une résolution, un export global).
    entite     TEXT,
    entite_id  TEXT,
    -- Noms des champs réellement modifiés, séparés par une virgule.
    champs     TEXT,
    -- SUCCES ou REFUS, et le code HTTP quand l'action venait d'une requête.
    resultat   TEXT        NOT NULL,
    statut     INTEGER,
    PRIMARY KEY (edition_id, id)
);

-- La seule lecture de la page : les dernières actions d'une édition, du plus
-- récent au plus ancien. Sert aussi la purge, qui balaie par date.
CREATE INDEX IF NOT EXISTS idx_journal_action_recent
    ON journal_action (edition_id, survenu_le DESC);
