-- Sauvegarde avant vidage (ADR 0084) : avant de vider une édition — la
-- réinitialisation, ou l'import d'un scénario qui la remplace —, l'application
-- y range l'export de scénario de cette édition, celui que l'import relit, et
-- son planning (`plan` : sièges et verrous, avec les identifiants de l'édition
-- à cet instant). Le geste se défait en réimportant le scénario puis en
-- restaurant le planning, sans toucher aux autres éditions comme le ferait la
-- restauration du dump nocturne.
--
-- Cinq sauvegardes au plus par édition, les plus anciennes purgées à chaque
-- insertion (la borne des instantanés automatiques). Le contenu porte les
-- données personnelles des animateurs : la table suit son édition en cascade,
-- donc la purge annuelle (rgpd.md §5), et ne part pas dans l'export SQL.

CREATE TABLE sauvegarde_avant_vidage (
    edition_id  VARCHAR(64) NOT NULL REFERENCES edition (id) ON DELETE CASCADE,
    id          BIGSERIAL,
    origine     VARCHAR(20) NOT NULL,
    cree_le     TIMESTAMPTZ NOT NULL DEFAULT now(),
    contenu     TEXT        NOT NULL,
    plan        JSONB       NOT NULL,
    PRIMARY KEY (edition_id, id),
    CONSTRAINT sauvegarde_avant_vidage_origine_connue CHECK (origine IN ('RESET', 'IMPORT'))
);

-- Sert la clé vers l'édition (ADR 0083) et la lecture « les plus récentes d'abord ».
CREATE INDEX idx_sauvegarde_avant_vidage_recente ON sauvegarde_avant_vidage (edition_id, cree_le DESC, id DESC);
