-- Sauvegarde avant vidage (ADR 0084) : avant de vider une édition — la
-- réinitialisation, l'import d'un scénario qui la remplace, ou la restauration
-- d'une sauvegarde —, l'application y range l'export de scénario de cette
-- édition, celui que l'import relit, et son planning (`plan` : sièges et
-- verrous, avec les identifiants de l'édition à cet instant). Une restauration
-- remet l'un et l'autre en un geste, sans toucher aux autres éditions comme le
-- ferait la restauration du dump nocturne.
--
-- Aucune purge automatique : une sauvegarde reste jusqu'à ce qu'un
-- administrateur la supprime ou que son édition disparaisse (cascade, donc la
-- purge annuelle, rgpd.md §5). Une ligne prise par une restauration nomme la
-- sauvegarde restaurée et dit si la restauration a abouti, pour la liste et
-- le message d'erreur seulement : une restauration sauvegarde toujours l'état
-- qu'elle remplace. Supprimer la sauvegarde visée met cette référence à nul
-- (ON DELETE SET NULL), sans cascade. Le contenu porte les données
-- personnelles des animateurs et ne part pas dans l'export SQL.

CREATE TABLE sauvegarde_avant_vidage (
    edition_id  VARCHAR(64) NOT NULL REFERENCES edition (id) ON DELETE CASCADE,
    id          BIGSERIAL,
    origine     VARCHAR(20) NOT NULL,
    cree_le     TIMESTAMPTZ NOT NULL DEFAULT now(),
    contenu     TEXT        NOT NULL,
    plan        JSONB       NOT NULL,
    -- Une restauration : la sauvegarde restaurée, et si elle a abouti.
    restaure_id          BIGINT,
    restauration_aboutie BOOLEAN,
    PRIMARY KEY (edition_id, id),
    CONSTRAINT sauvegarde_avant_vidage_restauree
        FOREIGN KEY (edition_id, restaure_id) REFERENCES sauvegarde_avant_vidage (edition_id, id)
        ON DELETE SET NULL (restaure_id),
    CONSTRAINT sauvegarde_avant_vidage_restauration_decrite
        CHECK ((origine = 'RESTORE') = (restauration_aboutie IS NOT NULL)),
    CONSTRAINT sauvegarde_avant_vidage_origine_connue CHECK (origine IN ('RESET', 'IMPORT', 'RESTORE'))
);

-- Sert la clé vers l'édition (ADR 0083) et la lecture « les plus récentes d'abord ».
CREATE INDEX idx_sauvegarde_avant_vidage_recente ON sauvegarde_avant_vidage (edition_id, cree_le DESC, id DESC);
CREATE INDEX idx_sauvegarde_avant_vidage_restauree
    ON sauvegarde_avant_vidage (edition_id, restaure_id) WHERE restaure_id IS NOT NULL;
