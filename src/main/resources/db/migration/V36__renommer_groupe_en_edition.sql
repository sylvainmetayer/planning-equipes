-- Renomme le concept « groupe » en « édition » — plus parlant d'un point de
-- vue métier — sans toucher à `groupe_creneau` (grille de créneaux
-- *à l'intérieur* d'une édition, V10), qui reste ce qu'il a toujours été :
-- table, colonnes (`groupe_source_id`, `groupe_creneau_id` ailleurs) et index
-- inchangés.
--
-- RENAME COLUMN et RENAME TO préservent les données, les index et les clés
-- (primaires ou étrangères) telles quelles : seuls le nom de la table, celui
-- des colonnes `groupe_id` et les noms de contraintes/index qui les
-- mentionnent littéralement changent ici.

/* --------- 1. La table elle-même --------- */

ALTER TABLE groupe RENAME TO edition;
ALTER TABLE edition RENAME CONSTRAINT groupe_pkey TO edition_pkey;
ALTER INDEX idx_groupe_defaut_unique RENAME TO idx_edition_defaut_unique;

/* --------- 2. groupe_id -> edition_id sur les 12 tables racines (V33) --------- */

ALTER TABLE animateur RENAME COLUMN groupe_id TO edition_id;
ALTER TABLE animateur RENAME CONSTRAINT animateur_groupe_id_fkey TO animateur_edition_id_fkey;

ALTER TABLE stand RENAME COLUMN groupe_id TO edition_id;
ALTER TABLE stand RENAME CONSTRAINT stand_groupe_id_fkey TO stand_edition_id_fkey;

ALTER TABLE emplacement RENAME COLUMN groupe_id TO edition_id;
ALTER TABLE emplacement RENAME CONSTRAINT emplacement_groupe_id_fkey TO emplacement_edition_id_fkey;

ALTER TABLE typologie RENAME COLUMN groupe_id TO edition_id;
ALTER TABLE typologie RENAME CONSTRAINT typologie_groupe_id_fkey TO typologie_edition_id_fkey;

ALTER TABLE groupe_creneau RENAME COLUMN groupe_id TO edition_id;
ALTER TABLE groupe_creneau RENAME CONSTRAINT groupe_creneau_groupe_id_fkey TO groupe_creneau_edition_id_fkey;

ALTER TABLE contrainte_ad_hoc RENAME COLUMN groupe_id TO edition_id;
ALTER TABLE contrainte_ad_hoc RENAME CONSTRAINT contrainte_ad_hoc_groupe_id_fkey TO contrainte_ad_hoc_edition_id_fkey;

ALTER TABLE constraint_toggle RENAME COLUMN groupe_id TO edition_id;
ALTER TABLE constraint_toggle RENAME CONSTRAINT constraint_toggle_groupe_id_fkey TO constraint_toggle_edition_id_fkey;

ALTER TABLE verrouillage_planning RENAME COLUMN groupe_id TO edition_id;
ALTER TABLE verrouillage_planning RENAME CONSTRAINT verrouillage_planning_groupe_id_fkey TO verrouillage_planning_edition_id_fkey;
ALTER INDEX idx_verrouillage_planning_groupe_id RENAME TO idx_verrouillage_planning_edition_id;

ALTER TABLE parametres_legaux RENAME COLUMN groupe_id TO edition_id;
ALTER TABLE parametres_legaux RENAME CONSTRAINT parametres_legaux_groupe_id_fkey TO parametres_legaux_edition_id_fkey;

ALTER TABLE parametres_solveur RENAME COLUMN groupe_id TO edition_id;
ALTER TABLE parametres_solveur RENAME CONSTRAINT parametres_solveur_groupe_id_fkey TO parametres_solveur_edition_id_fkey;

ALTER TABLE parametres_decoupage RENAME COLUMN groupe_id TO edition_id;
ALTER TABLE parametres_decoupage RENAME CONSTRAINT parametres_decoupage_groupe_id_fkey TO parametres_decoupage_edition_id_fkey;

ALTER TABLE planning_resolution RENAME COLUMN groupe_id TO edition_id;
ALTER TABLE planning_resolution RENAME CONSTRAINT planning_resolution_groupe_id_fkey TO planning_resolution_edition_id_fkey;

/* --------- 3. groupe_id -> edition_id sur les 10 tables filles (V34) --------- */

ALTER TABLE creneau RENAME COLUMN groupe_id TO edition_id;
ALTER TABLE creneau RENAME CONSTRAINT creneau_groupe_id_fkey TO creneau_edition_id_fkey;

ALTER TABLE stand_typologie RENAME COLUMN groupe_id TO edition_id;
ALTER TABLE stand_typologie RENAME CONSTRAINT stand_typologie_groupe_id_fkey TO stand_typologie_edition_id_fkey;

ALTER TABLE stand_indisponibilite RENAME COLUMN groupe_id TO edition_id;
ALTER TABLE stand_indisponibilite RENAME CONSTRAINT stand_indisponibilite_groupe_id_fkey TO stand_indisponibilite_edition_id_fkey;

ALTER TABLE stand_ouverture RENAME COLUMN groupe_id TO edition_id;
ALTER TABLE stand_ouverture RENAME CONSTRAINT stand_ouverture_groupe_id_fkey TO stand_ouverture_edition_id_fkey;

ALTER TABLE animateur_competence RENAME COLUMN groupe_id TO edition_id;
ALTER TABLE animateur_competence RENAME CONSTRAINT animateur_competence_groupe_id_fkey TO animateur_competence_edition_id_fkey;

ALTER TABLE animateur_jour_indispo RENAME COLUMN groupe_id TO edition_id;
ALTER TABLE animateur_jour_indispo RENAME CONSTRAINT animateur_jour_indispo_groupe_id_fkey TO animateur_jour_indispo_edition_id_fkey;

ALTER TABLE animateur_souhait RENAME COLUMN groupe_id TO edition_id;
ALTER TABLE animateur_souhait RENAME CONSTRAINT animateur_souhait_groupe_id_fkey TO animateur_souhait_edition_id_fkey;

ALTER TABLE contrainte_animateur RENAME COLUMN groupe_id TO edition_id;
ALTER TABLE contrainte_animateur RENAME CONSTRAINT contrainte_animateur_groupe_id_fkey TO contrainte_animateur_edition_id_fkey;

ALTER TABLE poste_affectation RENAME COLUMN groupe_id TO edition_id;
ALTER TABLE poste_affectation RENAME CONSTRAINT poste_affectation_groupe_id_fkey TO poste_affectation_edition_id_fkey;

ALTER TABLE creneau_stand_ouvert RENAME COLUMN groupe_id TO edition_id;
ALTER TABLE creneau_stand_ouvert RENAME CONSTRAINT creneau_stand_ouvert_groupe_id_fkey TO creneau_stand_ouvert_edition_id_fkey;

/* --------- 4. Le seed lui-même --------- */

UPDATE edition SET nom = '2026' WHERE id = 'DEFAUT' AND nom = 'Groupe par défaut';
