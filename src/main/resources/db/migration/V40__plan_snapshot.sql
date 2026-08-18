-- Instantanés de plan (issue #138) : préserver un plan résolu avant que le
-- solve suivant ne l'écrase.
--
-- Jusqu'ici il n'existait qu'un seul plan persisté par édition
-- (`poste_affectation` réécrite en entier à chaque solve, `planning_resolution`
-- en une ligne). Résoudre sur le groupe de créneaux « défaut », basculer sur
-- « continu », relancer : le plan du premier groupe était définitivement perdu,
-- et le bandeau de mismatch ne faisait que le constater.
--
-- Point de conception : `contenu` porte le plan **dénormalisé**, pas une copie
-- de lignes `poste_affectation`. Cette table référence `creneau (edition_id, id)`
-- en clé étrangère ON DELETE CASCADE : une copie de lignes mourrait avec les
-- créneaux du groupe abandonné, c'est-à-dire exactement dans le cas d'usage
-- visé. Même raison pour `groupe_creneau_id`, laissée sans clé étrangère, et
-- pour `groupe_nom`, dénormalisé : un instantané doit survivre à la suppression
-- du groupe pour lequel il a été calculé.
--
-- `automatique` distingue la capture faite d'office avant chaque solve (purgée
-- au-delà des N dernières) de celle demandée par l'utilisateur (jamais purgée).

CREATE TABLE IF NOT EXISTS plan_snapshot (
    edition_id VARCHAR(64) NOT NULL DEFAULT 'DEFAUT' REFERENCES edition(id) ON DELETE CASCADE,
    id BIGSERIAL,
    libelle VARCHAR(255) NOT NULL,
    automatique BOOLEAN NOT NULL DEFAULT FALSE,
    groupe_creneau_id VARCHAR(64),
    groupe_nom VARCHAR(255),
    score VARCHAR(64),
    nombre_affectations INTEGER NOT NULL DEFAULT 0,
    cree_le TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    contenu JSONB NOT NULL,
    PRIMARY KEY (edition_id, id)
);

CREATE INDEX IF NOT EXISTS idx_plan_snapshot_edition_date
    ON plan_snapshot(edition_id, cree_le DESC);
