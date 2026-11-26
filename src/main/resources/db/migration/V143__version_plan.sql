-- Un seul type « Version » (4.0) : `plan_snapshot` devient `version_plan`, et
-- l'origine de chaque ligne se lit dans une colonne au lieu de se deviner de
-- deux drapeaux.
--
-- Les trois origines vivaient déjà dans la même table : la capture manuelle,
-- la capture d'office avant un calcul (`automatique`) et la publication
-- (`publie_le` non nul). Rien ne se copie donc, et rien ne peut se perdre :
-- la table est renommée en place, avec ses lignes, ses index et les clés
-- étrangères qui la visent (`publication_destinataire`, `envoi_planning`),
-- que PostgreSQL suit toutes seules.
--
-- Une publication reste une publication même si `automatique` était vrai (cas
-- qu'aucun chemin du code ne produit) : `publie_le` l'emporte.

ALTER TABLE plan_snapshot RENAME TO version_plan;
ALTER INDEX plan_snapshot_pkey RENAME TO version_plan_pkey;
ALTER INDEX idx_plan_snapshot_edition_date RENAME TO idx_version_plan_edition_date;
ALTER INDEX idx_plan_snapshot_publie RENAME TO idx_version_plan_publie;
ALTER SEQUENCE plan_snapshot_id_seq RENAME TO version_plan_id_seq;

ALTER TABLE version_plan ADD COLUMN origine VARCHAR(16) NOT NULL DEFAULT 'MANUELLE';

UPDATE version_plan
SET origine = CASE
    WHEN publie_le IS NOT NULL THEN 'PUBLIEE'
    WHEN automatique THEN 'AVANT_CALCUL'
    ELSE 'MANUELLE'
END;

ALTER TABLE version_plan DROP COLUMN automatique;

-- `publie_le` et `origine` disent la même chose : la contrainte les tient d'accord.
ALTER TABLE version_plan ADD CONSTRAINT version_plan_origine_connue
    CHECK (origine IN ('MANUELLE', 'AVANT_CALCUL', 'PUBLIEE')
           AND (origine = 'PUBLIEE') = (publie_le IS NOT NULL));

COMMENT ON COLUMN version_plan.origine IS
    'MANUELLE (asked by a person), AVANT_CALCUL (taken before a solve), PUBLIEE (what the animateurs were sent)';
