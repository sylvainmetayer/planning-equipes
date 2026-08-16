-- Bascule les clés primaires métier en (groupe_id, id) (docs/groupes.md §4.3).
--
-- `stand.id`, `animateur.id`, `typologie.id`, `emplacement.id`,
-- `groupe_creneau.id`, `contrainte_ad_hoc.id` et `constraint_toggle.nom` sont
-- des identifiants saisis par l'utilisateur (`tir-a-la-corde`, …). Le cas
-- d'usage visé — « les mêmes stands en 2025 et en 2026 » — les fait
-- collisionner immédiatement tant que la clé reste globale. `creneau.id` reste
-- un BIGINT identity global : il est déjà unique par construction.
--
-- Conséquence mécanique, qui affine le §4.2 : une clé étrangère vers une clé
-- primaire composite doit en porter les deux colonnes, donc les tables filles
-- reçoivent `groupe_id` elles aussi. Il n'y devient pas un périmètre
-- indépendant — c'est la colonne de tête de leur FK, contrainte à rester égale
-- à celle du parent.
--
-- Chaque table porte en plus une FK directe vers `groupe(id) ON DELETE
-- CASCADE` : la suppression d'un groupe devient alors une seule instruction
-- qui emporte tout son référentiel. Les FK métier restées en NO ACTION (par
-- exemple poste_affectation → creneau) sont vérifiées en fin d'instruction, et
-- non ligne à ligne, donc elles ne bloquent pas cette cascade.
--
-- Aucun changement de sémantique de suppression : chaque ON DELETE existant
-- est reconduit à l'identique.

/* --------- 1. groupe_id sur les tables filles, backfillé depuis le parent --------- */

ALTER TABLE creneau ADD COLUMN groupe_id VARCHAR(64);
UPDATE creneau c SET groupe_id = g.groupe_id FROM groupe_creneau g WHERE g.id = c.groupe_creneau_id;

ALTER TABLE stand_typologie ADD COLUMN groupe_id VARCHAR(64);
UPDATE stand_typologie st SET groupe_id = s.groupe_id FROM stand s WHERE s.id = st.stand_id;

ALTER TABLE stand_indisponibilite ADD COLUMN groupe_id VARCHAR(64);
UPDATE stand_indisponibilite si SET groupe_id = s.groupe_id FROM stand s WHERE s.id = si.stand_id;

ALTER TABLE stand_ouverture ADD COLUMN groupe_id VARCHAR(64);
UPDATE stand_ouverture so SET groupe_id = s.groupe_id FROM stand s WHERE s.id = so.stand_id;

ALTER TABLE animateur_competence ADD COLUMN groupe_id VARCHAR(64);
UPDATE animateur_competence ac SET groupe_id = a.groupe_id FROM animateur a WHERE a.id = ac.animateur_id;

ALTER TABLE animateur_jour_indispo ADD COLUMN groupe_id VARCHAR(64);
UPDATE animateur_jour_indispo aji SET groupe_id = a.groupe_id FROM animateur a WHERE a.id = aji.animateur_id;

ALTER TABLE animateur_souhait ADD COLUMN groupe_id VARCHAR(64);
UPDATE animateur_souhait asou SET groupe_id = a.groupe_id FROM animateur a WHERE a.id = asou.animateur_id;

ALTER TABLE contrainte_animateur ADD COLUMN groupe_id VARCHAR(64);
UPDATE contrainte_animateur ca SET groupe_id = cah.groupe_id
  FROM contrainte_ad_hoc cah WHERE cah.id = ca.contrainte_id;

ALTER TABLE poste_affectation ADD COLUMN groupe_id VARCHAR(64);
UPDATE poste_affectation pa SET groupe_id = s.groupe_id FROM stand s WHERE s.id = pa.stand_id;

-- Après creneau, dont il dépend.
ALTER TABLE creneau_stand_ouvert ADD COLUMN groupe_id VARCHAR(64);
UPDATE creneau_stand_ouvert cso SET groupe_id = c.groupe_id FROM creneau c WHERE c.id = cso.creneau_id;

-- Même filet que sur les tables racines (V33) : un appelant qui écrit sans
-- connaître les groupes atterrit dans le groupe par défaut au lieu de violer
-- la contrainte NOT NULL.
ALTER TABLE creneau ALTER COLUMN groupe_id SET NOT NULL, ALTER COLUMN groupe_id SET DEFAULT 'DEFAUT';
ALTER TABLE stand_typologie ALTER COLUMN groupe_id SET NOT NULL, ALTER COLUMN groupe_id SET DEFAULT 'DEFAUT';
ALTER TABLE stand_indisponibilite ALTER COLUMN groupe_id SET NOT NULL, ALTER COLUMN groupe_id SET DEFAULT 'DEFAUT';
ALTER TABLE stand_ouverture ALTER COLUMN groupe_id SET NOT NULL, ALTER COLUMN groupe_id SET DEFAULT 'DEFAUT';
ALTER TABLE animateur_competence ALTER COLUMN groupe_id SET NOT NULL, ALTER COLUMN groupe_id SET DEFAULT 'DEFAUT';
ALTER TABLE animateur_jour_indispo ALTER COLUMN groupe_id SET NOT NULL, ALTER COLUMN groupe_id SET DEFAULT 'DEFAUT';
ALTER TABLE animateur_souhait ALTER COLUMN groupe_id SET NOT NULL, ALTER COLUMN groupe_id SET DEFAULT 'DEFAUT';
ALTER TABLE contrainte_animateur ALTER COLUMN groupe_id SET NOT NULL, ALTER COLUMN groupe_id SET DEFAULT 'DEFAUT';
ALTER TABLE poste_affectation ALTER COLUMN groupe_id SET NOT NULL, ALTER COLUMN groupe_id SET DEFAULT 'DEFAUT';
ALTER TABLE creneau_stand_ouvert ALTER COLUMN groupe_id SET NOT NULL, ALTER COLUMN groupe_id SET DEFAULT 'DEFAUT';

ALTER TABLE creneau ADD CONSTRAINT creneau_groupe_id_fkey
    FOREIGN KEY (groupe_id) REFERENCES groupe (id) ON DELETE CASCADE;
ALTER TABLE stand_typologie ADD CONSTRAINT stand_typologie_groupe_id_fkey
    FOREIGN KEY (groupe_id) REFERENCES groupe (id) ON DELETE CASCADE;
ALTER TABLE stand_indisponibilite ADD CONSTRAINT stand_indisponibilite_groupe_id_fkey
    FOREIGN KEY (groupe_id) REFERENCES groupe (id) ON DELETE CASCADE;
ALTER TABLE stand_ouverture ADD CONSTRAINT stand_ouverture_groupe_id_fkey
    FOREIGN KEY (groupe_id) REFERENCES groupe (id) ON DELETE CASCADE;
ALTER TABLE animateur_competence ADD CONSTRAINT animateur_competence_groupe_id_fkey
    FOREIGN KEY (groupe_id) REFERENCES groupe (id) ON DELETE CASCADE;
ALTER TABLE animateur_jour_indispo ADD CONSTRAINT animateur_jour_indispo_groupe_id_fkey
    FOREIGN KEY (groupe_id) REFERENCES groupe (id) ON DELETE CASCADE;
ALTER TABLE animateur_souhait ADD CONSTRAINT animateur_souhait_groupe_id_fkey
    FOREIGN KEY (groupe_id) REFERENCES groupe (id) ON DELETE CASCADE;
ALTER TABLE contrainte_animateur ADD CONSTRAINT contrainte_animateur_groupe_id_fkey
    FOREIGN KEY (groupe_id) REFERENCES groupe (id) ON DELETE CASCADE;
ALTER TABLE poste_affectation ADD CONSTRAINT poste_affectation_groupe_id_fkey
    FOREIGN KEY (groupe_id) REFERENCES groupe (id) ON DELETE CASCADE;
ALTER TABLE creneau_stand_ouvert ADD CONSTRAINT creneau_stand_ouvert_groupe_id_fkey
    FOREIGN KEY (groupe_id) REFERENCES groupe (id) ON DELETE CASCADE;

/* --------- 2. Retrait des FK qui dépendent des clés primaires remplacées --------- */

ALTER TABLE stand DROP CONSTRAINT stand_emplacement_id_fkey;
ALTER TABLE stand_typologie DROP CONSTRAINT stand_typologie_stand_id_fkey;
ALTER TABLE stand_typologie DROP CONSTRAINT fk_stand_typologie_typologie;
ALTER TABLE stand_indisponibilite DROP CONSTRAINT stand_indisponibilite_stand_id_fkey;
ALTER TABLE stand_ouverture DROP CONSTRAINT stand_ouverture_stand_id_fkey;
ALTER TABLE creneau_stand_ouvert DROP CONSTRAINT creneau_stand_ouvert_stand_id_fkey;
ALTER TABLE contrainte_ad_hoc DROP CONSTRAINT contrainte_ad_hoc_stand_id_fkey;
ALTER TABLE poste_affectation DROP CONSTRAINT poste_affectation_stand_id_fkey;

ALTER TABLE animateur_competence DROP CONSTRAINT animateur_competence_animateur_id_fkey;
ALTER TABLE animateur_competence DROP CONSTRAINT fk_animateur_competence_typologie;
ALTER TABLE animateur_jour_indispo DROP CONSTRAINT animateur_jour_indispo_animateur_id_fkey;
ALTER TABLE animateur_souhait DROP CONSTRAINT animateur_souhait_animateur_id_fkey;
ALTER TABLE animateur_souhait DROP CONSTRAINT animateur_souhait_typologie_fkey;
ALTER TABLE contrainte_animateur DROP CONSTRAINT contrainte_animateur_animateur_id_fkey;
ALTER TABLE contrainte_animateur DROP CONSTRAINT contrainte_animateur_contrainte_id_fkey;
ALTER TABLE poste_affectation DROP CONSTRAINT poste_affectation_animateur_id_fkey;

ALTER TABLE creneau DROP CONSTRAINT creneau_groupe_creneau_id_fkey;
ALTER TABLE groupe_creneau DROP CONSTRAINT groupe_creneau_groupe_source_id_fkey;
ALTER TABLE planning_resolution DROP CONSTRAINT planning_resolution_groupe_creneau_id_fkey;

ALTER TABLE verrouillage_planning DROP CONSTRAINT verrouillage_planning_groupe_creneau_id_fkey;
ALTER TABLE verrouillage_planning DROP CONSTRAINT verrouillage_planning_animateur_id_fkey;
ALTER TABLE verrouillage_planning DROP CONSTRAINT verrouillage_planning_stand_id_fkey;

/* --------- 3. Clés primaires composites --------- */

ALTER TABLE emplacement DROP CONSTRAINT emplacement_pkey, ADD PRIMARY KEY (groupe_id, id);
ALTER TABLE typologie DROP CONSTRAINT typologie_pkey, ADD PRIMARY KEY (groupe_id, id);
ALTER TABLE animateur DROP CONSTRAINT animateur_pkey, ADD PRIMARY KEY (groupe_id, id);
ALTER TABLE stand DROP CONSTRAINT stand_pkey, ADD PRIMARY KEY (groupe_id, id);
ALTER TABLE groupe_creneau DROP CONSTRAINT groupe_creneau_pkey, ADD PRIMARY KEY (groupe_id, id);
ALTER TABLE contrainte_ad_hoc DROP CONSTRAINT contrainte_ad_hoc_pkey, ADD PRIMARY KEY (groupe_id, id);
ALTER TABLE constraint_toggle DROP CONSTRAINT constraint_toggle_pkey, ADD PRIMARY KEY (groupe_id, nom);
ALTER TABLE poste_affectation DROP CONSTRAINT poste_affectation_pkey, ADD PRIMARY KEY (groupe_id, id);

ALTER TABLE stand_typologie DROP CONSTRAINT stand_typologie_pkey,
    ADD PRIMARY KEY (groupe_id, stand_id, typologie);
ALTER TABLE animateur_competence DROP CONSTRAINT animateur_competence_pkey,
    ADD PRIMARY KEY (groupe_id, animateur_id, typologie);
ALTER TABLE animateur_jour_indispo DROP CONSTRAINT animateur_jour_indispo_pkey,
    ADD PRIMARY KEY (groupe_id, animateur_id, jour);
ALTER TABLE animateur_souhait DROP CONSTRAINT animateur_souhait_pkey,
    ADD PRIMARY KEY (groupe_id, animateur_id, typologie);
ALTER TABLE contrainte_animateur DROP CONSTRAINT contrainte_animateur_pkey,
    ADD PRIMARY KEY (groupe_id, contrainte_id, position);
ALTER TABLE creneau_stand_ouvert DROP CONSTRAINT creneau_stand_ouvert_pkey,
    ADD PRIMARY KEY (groupe_id, creneau_id, stand_id);

/* --------- 4. FK composites --------- */

-- ON DELETE SET NULL (colonne) — PostgreSQL 15+ : sans la liste de colonnes,
-- la cascade mettrait aussi groupe_id à NULL, ce que sa contrainte NOT NULL
-- refuse.
ALTER TABLE stand ADD CONSTRAINT stand_emplacement_fkey
    FOREIGN KEY (groupe_id, emplacement_id) REFERENCES emplacement (groupe_id, id);

ALTER TABLE stand_typologie
    ADD CONSTRAINT stand_typologie_stand_fkey
        FOREIGN KEY (groupe_id, stand_id) REFERENCES stand (groupe_id, id) ON DELETE CASCADE,
    ADD CONSTRAINT stand_typologie_typologie_fkey
        FOREIGN KEY (groupe_id, typologie) REFERENCES typologie (groupe_id, id);

ALTER TABLE stand_indisponibilite ADD CONSTRAINT stand_indisponibilite_stand_fkey
    FOREIGN KEY (groupe_id, stand_id) REFERENCES stand (groupe_id, id) ON DELETE CASCADE;

ALTER TABLE stand_ouverture ADD CONSTRAINT stand_ouverture_stand_fkey
    FOREIGN KEY (groupe_id, stand_id) REFERENCES stand (groupe_id, id) ON DELETE CASCADE;

ALTER TABLE creneau_stand_ouvert ADD CONSTRAINT creneau_stand_ouvert_stand_fkey
    FOREIGN KEY (groupe_id, stand_id) REFERENCES stand (groupe_id, id) ON DELETE CASCADE;

ALTER TABLE contrainte_ad_hoc ADD CONSTRAINT contrainte_ad_hoc_stand_fkey
    FOREIGN KEY (groupe_id, stand_id) REFERENCES stand (groupe_id, id) ON DELETE SET NULL (stand_id);

ALTER TABLE poste_affectation ADD CONSTRAINT poste_affectation_stand_fkey
    FOREIGN KEY (groupe_id, stand_id) REFERENCES stand (groupe_id, id);

ALTER TABLE animateur_competence
    ADD CONSTRAINT animateur_competence_animateur_fkey
        FOREIGN KEY (groupe_id, animateur_id) REFERENCES animateur (groupe_id, id) ON DELETE CASCADE,
    ADD CONSTRAINT animateur_competence_typologie_fkey
        FOREIGN KEY (groupe_id, typologie) REFERENCES typologie (groupe_id, id);

ALTER TABLE animateur_jour_indispo ADD CONSTRAINT animateur_jour_indispo_animateur_fkey
    FOREIGN KEY (groupe_id, animateur_id) REFERENCES animateur (groupe_id, id) ON DELETE CASCADE;

ALTER TABLE animateur_souhait
    ADD CONSTRAINT animateur_souhait_animateur_fkey
        FOREIGN KEY (groupe_id, animateur_id) REFERENCES animateur (groupe_id, id) ON DELETE CASCADE,
    ADD CONSTRAINT animateur_souhait_typologie_fkey
        FOREIGN KEY (groupe_id, typologie) REFERENCES typologie (groupe_id, id);

ALTER TABLE contrainte_animateur
    ADD CONSTRAINT contrainte_animateur_animateur_fkey
        FOREIGN KEY (groupe_id, animateur_id) REFERENCES animateur (groupe_id, id) ON DELETE CASCADE,
    ADD CONSTRAINT contrainte_animateur_contrainte_fkey
        FOREIGN KEY (groupe_id, contrainte_id) REFERENCES contrainte_ad_hoc (groupe_id, id) ON DELETE CASCADE;

ALTER TABLE poste_affectation ADD CONSTRAINT poste_affectation_animateur_fkey
    FOREIGN KEY (groupe_id, animateur_id) REFERENCES animateur (groupe_id, id);

ALTER TABLE creneau ADD CONSTRAINT creneau_groupe_creneau_fkey
    FOREIGN KEY (groupe_id, groupe_creneau_id) REFERENCES groupe_creneau (groupe_id, id);

ALTER TABLE groupe_creneau ADD CONSTRAINT groupe_creneau_source_fkey
    FOREIGN KEY (groupe_id, groupe_source_id) REFERENCES groupe_creneau (groupe_id, id)
    ON DELETE SET NULL (groupe_source_id);

ALTER TABLE planning_resolution ADD CONSTRAINT planning_resolution_groupe_creneau_fkey
    FOREIGN KEY (groupe_id, groupe_creneau_id) REFERENCES groupe_creneau (groupe_id, id)
    ON DELETE SET NULL (groupe_creneau_id);

-- Un verrouillage vise une grille de créneaux, et éventuellement un animateur
-- ou un stand : trois FK qui deviennent composites. Sa cible reste obligatoire
-- (CHECK verrouillage_planning_cible_coherente), d'où le CASCADE d'origine —
-- supprimer l'animateur visé supprime le verrouillage, il n'a plus d'objet.
ALTER TABLE verrouillage_planning
    ADD CONSTRAINT verrouillage_planning_groupe_creneau_fkey
        FOREIGN KEY (groupe_id, groupe_creneau_id) REFERENCES groupe_creneau (groupe_id, id) ON DELETE CASCADE,
    ADD CONSTRAINT verrouillage_planning_animateur_fkey
        FOREIGN KEY (groupe_id, animateur_id) REFERENCES animateur (groupe_id, id) ON DELETE CASCADE,
    ADD CONSTRAINT verrouillage_planning_stand_fkey
        FOREIGN KEY (groupe_id, stand_id) REFERENCES stand (groupe_id, id) ON DELETE CASCADE;
