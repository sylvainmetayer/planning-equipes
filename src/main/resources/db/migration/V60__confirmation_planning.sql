-- Accusé de réception du planning publié : ce que l'animateur répond au plan
-- qu'on lui a communiqué.
--
-- Par édition, comme tout le reste : une confirmation porte sur le planning
-- d'un événement, pas sur une personne en général.
--
-- L'ABSENCE DE LIGNE VAUT `NON_VU`. C'est le statut de départ de tout le monde,
-- et celui où une republication renvoie ceux dont le planning a bougé : la
-- remise à zéro est alors un DELETE, pas un UPDATE, et il n'y a jamais deux
-- façons d'écrire « cette personne n'a pas confirmé ». La contrepartie assumée
-- est qu'on ne garde pas l'historique des confirmations périmées : ce qui
-- compte est l'état vis-à-vis du plan actuellement publié, et la trace de ce
-- qui a été envoyé vit déjà dans `publication_destinataire`.
--
-- `relance_le` est écrit par la relance automatique (#299) et n'efface jamais
-- `confirme_le` : les deux dates coexistent pour quelqu'un qui a confirmé après
-- avoir été relancé.

CREATE TABLE IF NOT EXISTS confirmation_planning (
    edition_id   TEXT        NOT NULL REFERENCES edition (id) ON DELETE CASCADE,
    animateur_id TEXT        NOT NULL,
    statut       TEXT        NOT NULL CHECK (statut IN ('CONFIRME', 'RELANCE')),
    confirme_le  TIMESTAMPTZ,
    relance_le   TIMESTAMPTZ,
    PRIMARY KEY (edition_id, animateur_id),
    FOREIGN KEY (edition_id, animateur_id) REFERENCES animateur (edition_id, id) ON DELETE CASCADE
);
