-- KPI historiques (issue #89) : une ligne par solve terminé.
--
-- Volontairement SANS clé étrangère vers `edition`, et avec le libellé de
-- l'édition dénormalisé : l'historique doit survivre à la suppression de
-- l'édition qu'il décrit (critère d'acceptation explicite de #89). C'est aussi
-- pourquoi la lecture n'est pas cloisonnée par édition, contrairement au reste
-- du référentiel — comparer 2025 à 2026 est précisément l'objet de la table.
--
-- Aucune donnée nominative : l'équité est stockée en dispersion agrégée
-- (total / moyenne / écart-type / min / max des heures), jamais par animateur.
-- Le JSONB évite une colonne par indicateur, dont la liste bougera.

CREATE TABLE IF NOT EXISTS kpi_historique (
    id BIGSERIAL PRIMARY KEY,
    edition_id VARCHAR(64) NOT NULL,
    edition_nom VARCHAR(255),
    kpi JSONB NOT NULL,
    cree_le TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_kpi_historique_edition_date
    ON kpi_historique(edition_id, cree_le DESC);
