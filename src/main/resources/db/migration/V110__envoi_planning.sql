-- L'état de l'envoi du planning, par personne : ce qui est parti, ce qui a
-- échoué, ce qui n'a pas pu partir faute d'adresse et ce qui a été différé,
-- pour chaque publication et pour chaque renvoi individuel.
--
-- `publication_destinataire` garde ce qu'une publication a dit (les phrases) ;
-- cette table ne garde que des dates et des états, jamais de contenu : un échec
-- d'envoi, jusqu'ici seulement écrit dans le journal du serveur, devient une
-- donnée que l'écran Diffuser et l'accueil lisent. La cause d'un échec est un
-- code court (adresse refusée, boîte pleine, serveur injoignable, autre),
-- jamais le message du serveur de messagerie, qui peut citer l'adresse.
--
-- Pas de clé étrangère vers la fiche : une preuve d'envoi survit à la fiche
-- qu'elle concerne, comme `publication_destinataire`. Elle cascade en revanche
-- avec son instantané et avec l'édition.
CREATE TABLE envoi_planning (
    edition_id   VARCHAR(64) NOT NULL REFERENCES edition (id) ON DELETE CASCADE,
    id           BIGSERIAL,
    animateur_id VARCHAR(64) NOT NULL,
    -- La version publiée envoyée ; NULL pour un envoi sans publication derrière.
    snapshot_id  BIGINT,
    -- PUBLICATION ou RENVOI.
    nature       VARCHAR(16) NOT NULL,
    -- ENVOYE, ECHEC, SANS_EMAIL ou EXCLU (différé).
    statut       VARCHAR(16) NOT NULL,
    -- ADRESSE_REFUSEE, BOITE_PLEINE, SERVEUR_INJOIGNABLE ou AUTRE, sur un échec seulement.
    cause        VARCHAR(32),
    envoye_le    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (edition_id, id),
    FOREIGN KEY (edition_id, snapshot_id) REFERENCES plan_snapshot (edition_id, id) ON DELETE CASCADE
);

CREATE INDEX idx_envoi_planning_animateur ON envoi_planning (edition_id, animateur_id, envoye_le DESC);
