-- Planning publié (issue #245) : distinguer le plan communiqué du plan de
-- travail.
--
-- Le plan persisté bouge après son envoi — un remplacement appliqué depuis
-- « Pourquoi lui ? », un échange validé dans la foire, un solve incrémental —
-- et rien ne disait à l'administrateur qu'il devait recommuniquer, ni à qui.
-- « Ce que les gens ont reçu » est exactement un instantané (ADR 0007, contenu
-- dénormalisé en JSONB) : il ne manquait que la marque, d'où une colonne
-- plutôt qu'une table de plans parallèle.
--
-- `publie_le` NULL = instantané de travail, le seul cas avant cette migration.
-- Aucune donnée existante n'est marquée publiée : l'application ne peut pas
-- affirmer avoir communiqué un plan qu'elle n'a jamais envoyé. Sur une édition
-- en cours, l'espace animateur reste donc vide jusqu'à la première publication,
-- qui concerne tout le monde — c'est le comportement voulu, pas un effet de
-- bord.

ALTER TABLE plan_snapshot ADD COLUMN IF NOT EXISTS publie_le TIMESTAMP WITH TIME ZONE;

-- Le dernier publié d'une édition est lu à chaque ouverture de l'espace
-- animateur : l'index partiel garde cette lecture à un seul enregistrement,
-- quel que soit le nombre d'instantanés de travail accumulés à côté.
CREATE INDEX IF NOT EXISTS idx_plan_snapshot_publie
    ON plan_snapshot(edition_id, publie_le DESC)
    WHERE publie_le IS NOT NULL;

-- Trace nominative de ce que chacun a reçu, et quand.
--
-- Sans clé étrangère vers `animateur`, et avec le nom dénormalisé : la trace
-- doit survivre à la suppression de la fiche qu'elle décrit — c'est le propre
-- d'une preuve d'envoi. Elle cascade en revanche avec son instantané, donc
-- avec l'édition : rien ne subsiste au-delà des données qu'elle documente
-- (RGPD, limitation de conservation).
--
-- `changements` porte les lignes exactes lues par le destinataire, pas des
-- identifiants à re-résoudre : ce qui a été dit reste lisible même quand le
-- stand a été renommé depuis.
CREATE TABLE IF NOT EXISTS publication_destinataire (
    edition_id VARCHAR(64) NOT NULL DEFAULT 'DEFAUT' REFERENCES edition(id) ON DELETE CASCADE,
    id BIGSERIAL,
    snapshot_id BIGINT NOT NULL,
    animateur_id VARCHAR(64) NOT NULL,
    nom_affiche VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    -- ENVOYE, SANS_EMAIL ou ECHEC : un destinataire concerné qu'on n'a pas pu
    -- joindre reste dans la trace, sinon « prévenu » et « à prévenir » se
    -- confondent au prochain aperçu.
    statut VARCHAR(32) NOT NULL,
    envoye_le TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    changements JSONB,
    PRIMARY KEY (edition_id, id),
    CONSTRAINT publication_destinataire_snapshot_fkey
        FOREIGN KEY (edition_id, snapshot_id) REFERENCES plan_snapshot (edition_id, id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_publication_destinataire_snapshot
    ON publication_destinataire(edition_id, snapshot_id);

CREATE INDEX IF NOT EXISTS idx_publication_destinataire_animateur
    ON publication_destinataire(edition_id, animateur_id, envoye_le DESC);

-- Une décision d'échange n'est plus annoncée au moment où elle est prise mais
-- à la publication suivante : d'ici là le plan de travail a changé sans que
-- personne ne l'ait reçu, et prévenir tout de suite promettrait un planning
-- que l'espace ne montre pas encore. La colonne dit ce qui reste à annoncer ;
-- les demandes tranchées avant cette migration sont considérées communiquées,
-- leur mail étant déjà parti.
ALTER TABLE demande_echange ADD COLUMN IF NOT EXISTS communiquee_le TIMESTAMP WITH TIME ZONE;

UPDATE demande_echange
SET communiquee_le = decide_le
WHERE decide_le IS NOT NULL AND communiquee_le IS NULL;
