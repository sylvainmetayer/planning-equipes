-- Cloisonne les 12 tables racines du référentiel par groupe (docs/groupes.md
-- §4.2). Les tables filles n'en reçoivent pas ici : elles héritent du périmètre
-- par leur clé étrangère vers leur parent — jusqu'à V34, où le passage des clés
-- primaires métier en (groupe_id, id) fait entrer `groupe_id` dans ces clés
-- étrangères et donc, mécaniquement, dans les tables filles elles-mêmes.
--
-- Sans perte de donnée : tout l'existant est backfillé sur le groupe 'DEFAUT',
-- une base existante devient donc un mono-groupe strictement identique à ce
-- qu'elle était.
--
-- La valeur par défaut de colonne est conservée après le backfill, comme
-- `creneau.groupe_creneau_id` le fait depuis V10 : c'est le filet des appelants
-- qui écrivent sans connaître les groupes (import CSV, dumps SQL antérieurs à
-- cette migration), qui atterrissent alors dans le groupe par défaut plutôt que
-- de violer la contrainte NOT NULL.

ALTER TABLE animateur
    ADD COLUMN groupe_id VARCHAR(64) NOT NULL DEFAULT 'DEFAUT' REFERENCES groupe(id) ON DELETE CASCADE;
ALTER TABLE stand
    ADD COLUMN groupe_id VARCHAR(64) NOT NULL DEFAULT 'DEFAUT' REFERENCES groupe(id) ON DELETE CASCADE;
ALTER TABLE emplacement
    ADD COLUMN groupe_id VARCHAR(64) NOT NULL DEFAULT 'DEFAUT' REFERENCES groupe(id) ON DELETE CASCADE;
ALTER TABLE typologie
    ADD COLUMN groupe_id VARCHAR(64) NOT NULL DEFAULT 'DEFAUT' REFERENCES groupe(id) ON DELETE CASCADE;
ALTER TABLE groupe_creneau
    ADD COLUMN groupe_id VARCHAR(64) NOT NULL DEFAULT 'DEFAUT' REFERENCES groupe(id) ON DELETE CASCADE;
ALTER TABLE contrainte_ad_hoc
    ADD COLUMN groupe_id VARCHAR(64) NOT NULL DEFAULT 'DEFAUT' REFERENCES groupe(id) ON DELETE CASCADE;
ALTER TABLE constraint_toggle
    ADD COLUMN groupe_id VARCHAR(64) NOT NULL DEFAULT 'DEFAUT' REFERENCES groupe(id) ON DELETE CASCADE;
ALTER TABLE verrouillage_planning
    ADD COLUMN groupe_id VARCHAR(64) NOT NULL DEFAULT 'DEFAUT' REFERENCES groupe(id) ON DELETE CASCADE;
ALTER TABLE parametres_legaux
    ADD COLUMN groupe_id VARCHAR(64) NOT NULL DEFAULT 'DEFAUT' REFERENCES groupe(id) ON DELETE CASCADE;
ALTER TABLE parametres_solveur
    ADD COLUMN groupe_id VARCHAR(64) NOT NULL DEFAULT 'DEFAUT' REFERENCES groupe(id) ON DELETE CASCADE;
ALTER TABLE parametres_decoupage
    ADD COLUMN groupe_id VARCHAR(64) NOT NULL DEFAULT 'DEFAUT' REFERENCES groupe(id) ON DELETE CASCADE;
ALTER TABLE planning_resolution
    ADD COLUMN groupe_id VARCHAR(64) NOT NULL DEFAULT 'DEFAUT' REFERENCES groupe(id) ON DELETE CASCADE;

-- Aucun index sur groupe_id ici, alors que toute lecture du référentiel va
-- désormais filtrer dessus : V34 fait de (groupe_id, id) la clé primaire de
-- ces mêmes tables, ce qui fournit exactement cet index, groupe_id en tête.
-- Entre les deux migrations il n'existe qu'un seul groupe, donc rien à
-- discriminer. `verrouillage_planning`, dont la clé primaire reste `id`, garde
-- en revanche son propre index.
CREATE INDEX idx_verrouillage_planning_groupe_id ON verrouillage_planning (groupe_id);

-- « Un seul groupe de créneaux actif » devient « un seul par groupe » : sinon
-- activer une grille dans l'édition 2026 désactiverait celle de 2025.
DROP INDEX idx_groupe_creneau_actif_unique;
CREATE UNIQUE INDEX idx_groupe_creneau_actif_unique
    ON groupe_creneau (groupe_id, actif) WHERE actif;

-- Même raisonnement pour « une seule typologie ninja » (V31) : c'est une
-- propriété du référentiel, et chaque groupe a le sien.
DROP INDEX ux_typologie_ninja;
CREATE UNIQUE INDEX ux_typologie_ninja ON typologie (groupe_id, ninja) WHERE ninja;

-- La cible d'un verrouillage est unique par grille de créneaux (V30) — donc,
-- désormais, par groupe *et* par grille.
DROP INDEX idx_verrouillage_planning_cible;
CREATE UNIQUE INDEX idx_verrouillage_planning_cible ON verrouillage_planning (
    groupe_id, groupe_creneau_id, type,
    COALESCE(animateur_id, ''), COALESCE(stand_id, ''),
    COALESCE(creneau_id, -1), COALESCE(jour, DATE '0001-01-01')
);
