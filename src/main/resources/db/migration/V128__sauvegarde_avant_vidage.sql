-- Sauvegarde avant vidage (ADR 0084) : avant de vider une édition — la
-- réinitialisation, l'import d'un scénario qui la remplace, ou la restauration
-- d'une sauvegarde —, l'application y range l'export de scénario de cette
-- édition, celui que l'import relit, et son planning (`plan` : sièges et
-- verrous, avec les identifiants de l'édition à cet instant). Une restauration
-- remet l'un et l'autre en un geste, sans toucher aux autres éditions comme le
-- ferait la restauration du dump nocturne.
--
-- Cinq sauvegardes au plus par édition (la borne des instantanés
-- automatiques) : les plus anciennes sont purgées après chaque vidage abouti —
-- dans la transaction d'une réinitialisation, à la fin d'un import réussi, à la
-- fin d'une restauration, qui épargne la sauvegarde restaurée et celle de
-- l'état qu'elle a remplacé. Le contenu porte les données personnelles des
-- animateurs : la table suit son édition en cascade, donc la purge annuelle
-- (rgpd.md §5), et ne part pas dans l'export SQL.

CREATE TABLE sauvegarde_avant_vidage (
    edition_id  VARCHAR(64) NOT NULL REFERENCES edition (id) ON DELETE CASCADE,
    id          BIGSERIAL,
    origine     VARCHAR(20) NOT NULL,
    cree_le     TIMESTAMPTZ NOT NULL DEFAULT now(),
    contenu     TEXT        NOT NULL,
    plan        JSONB       NOT NULL,
    PRIMARY KEY (edition_id, id),
    CONSTRAINT sauvegarde_avant_vidage_origine_connue CHECK (origine IN ('RESET', 'IMPORT', 'RESTORE'))
);

-- Sert la clé vers l'édition (ADR 0083) et la lecture « les plus récentes d'abord ».
CREATE INDEX idx_sauvegarde_avant_vidage_recente ON sauvegarde_avant_vidage (edition_id, cree_le DESC, id DESC);
