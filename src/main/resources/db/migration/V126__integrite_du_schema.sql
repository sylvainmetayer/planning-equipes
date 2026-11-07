-- Intégrité du schéma : ce que le modèle interdit, la base le refuse aussi.
--
-- 1. Les clés vers `creneau` portent l'édition. `creneau` garde sa clé
--    primaire (id) — les identifiants publiés ne bougent pas — et gagne une
--    clé unique (edition_id, id) pour que les sept tables qui le référencent
--    puissent l'être par (edition_id, creneau_id) : un siège ne peut plus
--    viser le créneau d'une autre édition.
-- 2. Six références sans clé étrangère en reçoivent une.
-- 3. Cinq horodatages sans fuseau passent en timestamptz.

-- 1. Créneaux ---------------------------------------------------------------

-- Précontrôle : une ligne dont l'édition diffère de celle de son créneau ferait
-- échouer les clés ci-dessous. Aucune réparation automatique : c'est un
-- incident de données à examiner avant de migrer.
DO $$
DECLARE
    t RECORD;
    n BIGINT;
BEGIN
    FOR t IN SELECT * FROM (VALUES
        ('poste_affectation'), ('creneau_stand_ouvert'), ('demande_echange'),
        ('signalement_absence'), ('consigne_edition_creneau'),
        ('verrouillage_planning'), ('contrainte_ad_hoc')) AS v(nom)
    LOOP
        EXECUTE format(
            'SELECT count(*) FROM %I x JOIN creneau c ON c.id = x.creneau_id WHERE x.edition_id <> c.edition_id',
            t.nom) INTO n;
        IF n > 0 THEN
            RAISE EXCEPTION 'Migration V126 impossible : % ligne(s) de la table % visent un créneau d''une autre édition. Incident de données à examiner et corriger à la main (aucune réparation automatique) avant de relancer la migration.', n, t.nom;
        END IF;
    END LOOP;
END $$;

ALTER TABLE creneau ADD CONSTRAINT creneau_edition_id_id_key UNIQUE (edition_id, id);

ALTER TABLE poste_affectation DROP CONSTRAINT poste_affectation_creneau_id_fkey;
ALTER TABLE poste_affectation ADD CONSTRAINT poste_affectation_creneau_fkey
    FOREIGN KEY (edition_id, creneau_id) REFERENCES creneau (edition_id, id);

ALTER TABLE creneau_stand_ouvert DROP CONSTRAINT creneau_stand_ouvert_creneau_id_fkey;
ALTER TABLE creneau_stand_ouvert ADD CONSTRAINT creneau_stand_ouvert_creneau_fkey
    FOREIGN KEY (edition_id, creneau_id) REFERENCES creneau (edition_id, id) ON DELETE CASCADE;

ALTER TABLE demande_echange DROP CONSTRAINT demande_echange_creneau_fkey;
ALTER TABLE demande_echange ADD CONSTRAINT demande_echange_creneau_fkey
    FOREIGN KEY (edition_id, creneau_id) REFERENCES creneau (edition_id, id) ON DELETE CASCADE;

ALTER TABLE signalement_absence DROP CONSTRAINT signalement_absence_creneau_id_fkey;
ALTER TABLE signalement_absence ADD CONSTRAINT signalement_absence_creneau_fkey
    FOREIGN KEY (edition_id, creneau_id) REFERENCES creneau (edition_id, id) ON DELETE CASCADE;

ALTER TABLE consigne_edition_creneau DROP CONSTRAINT consigne_edition_creneau_creneau_id_fkey;
ALTER TABLE consigne_edition_creneau ADD CONSTRAINT consigne_edition_creneau_creneau_fkey
    FOREIGN KEY (edition_id, creneau_id) REFERENCES creneau (edition_id, id) ON DELETE CASCADE;

-- Sans liste de colonnes, SET NULL viderait aussi edition_id, qui est NOT NULL (précédent : V121).
ALTER TABLE verrouillage_planning DROP CONSTRAINT verrouillage_planning_creneau_fkey;
ALTER TABLE verrouillage_planning ADD CONSTRAINT verrouillage_planning_creneau_fkey
    FOREIGN KEY (edition_id, creneau_id) REFERENCES creneau (edition_id, id) ON DELETE SET NULL (creneau_id);

ALTER TABLE contrainte_ad_hoc DROP CONSTRAINT contrainte_ad_hoc_creneau_id_fkey;
ALTER TABLE contrainte_ad_hoc ADD CONSTRAINT contrainte_ad_hoc_creneau_fkey
    FOREIGN KEY (edition_id, creneau_id) REFERENCES creneau (edition_id, id) ON DELETE SET NULL (creneau_id);

-- 2. Six références sans clé -------------------------------------------------
-- Les lignes orphelines sont réglées comme la clé le fera désormais : supprimées
-- quand la clé cascade, vidées quand elle met à NULL.

DELETE FROM demande_echange d
 WHERE d.creneau_cible_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM creneau c WHERE c.edition_id = d.edition_id AND c.id = d.creneau_cible_id);
ALTER TABLE demande_echange ADD CONSTRAINT demande_echange_creneau_cible_fkey
    FOREIGN KEY (edition_id, creneau_cible_id) REFERENCES creneau (edition_id, id) ON DELETE CASCADE;

ALTER TABLE demande_echange ALTER COLUMN stand_cible_id TYPE VARCHAR(64);
DELETE FROM demande_echange d
 WHERE d.stand_cible_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM stand s WHERE s.edition_id = d.edition_id AND s.id = d.stand_cible_id);
ALTER TABLE demande_echange ADD CONSTRAINT demande_echange_stand_cible_fkey
    FOREIGN KEY (edition_id, stand_cible_id) REFERENCES stand (edition_id, id) ON DELETE CASCADE;

-- Différée : un plan s'écrit d'un bloc (résolution, restauration, rejeu d'un
-- dump) et rien n'ordonne la continuation après l'original dans le lot.
UPDATE poste_affectation p SET suite_de = NULL
 WHERE p.suite_de IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM poste_affectation o WHERE o.edition_id = p.edition_id AND o.id = p.suite_de);
ALTER TABLE poste_affectation ADD CONSTRAINT poste_affectation_suite_de_fkey
    FOREIGN KEY (edition_id, suite_de) REFERENCES poste_affectation (edition_id, id) ON DELETE SET NULL (suite_de)
    DEFERRABLE INITIALLY DEFERRED;

UPDATE declaration_coequipier d SET contrainte_id = NULL
 WHERE d.contrainte_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM contrainte_ad_hoc c WHERE c.edition_id = d.edition_id AND c.id = d.contrainte_id);
ALTER TABLE declaration_coequipier ADD CONSTRAINT declaration_coequipier_contrainte_fkey
    FOREIGN KEY (edition_id, contrainte_id) REFERENCES contrainte_ad_hoc (edition_id, id)
    ON DELETE SET NULL (contrainte_id);

-- Différées : l'instantané est écrit après ces tables dans un rejeu de dump.
UPDATE animateur a SET plan_notifie_id = NULL
 WHERE a.plan_notifie_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM plan_snapshot s WHERE s.edition_id = a.edition_id AND s.id = a.plan_notifie_id);
ALTER TABLE animateur ADD CONSTRAINT animateur_plan_notifie_fkey
    FOREIGN KEY (edition_id, plan_notifie_id) REFERENCES plan_snapshot (edition_id, id)
    ON DELETE SET NULL (plan_notifie_id) DEFERRABLE INITIALLY DEFERRED;

UPDATE planning_resolution r SET snapshot_avant_solve_id = NULL
 WHERE r.snapshot_avant_solve_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM plan_snapshot s WHERE s.edition_id = r.edition_id AND s.id = r.snapshot_avant_solve_id);
ALTER TABLE planning_resolution ADD CONSTRAINT planning_resolution_snapshot_avant_solve_fkey
    FOREIGN KEY (edition_id, snapshot_avant_solve_id) REFERENCES plan_snapshot (edition_id, id)
    ON DELETE SET NULL (snapshot_avant_solve_id) DEFERRABLE INITIALLY DEFERRED;

-- 3. Horodatages -------------------------------------------------------------
-- Écrits à l'heure murale du serveur ; la session Flyway est dans le fuseau de
-- la JVM (pgjdbc), celui qui les a écrits.
-- Précondition : le fuseau de l'instance au moment de la migration doit être
-- celui sous lequel les lignes ont été écrites ; l'heure répétée au passage à
-- l'heure d'hiver reste ambiguë dans tous les cas.

ALTER TABLE edition ALTER COLUMN cree_le TYPE timestamptz
    USING cree_le AT TIME ZONE current_setting('TimeZone');
ALTER TABLE contrainte_ad_hoc ALTER COLUMN cree_le TYPE timestamptz
    USING cree_le AT TIME ZONE current_setting('TimeZone');
ALTER TABLE verrouillage_planning ALTER COLUMN cree_le TYPE timestamptz
    USING cree_le AT TIME ZONE current_setting('TimeZone');
ALTER TABLE planning_resolution ALTER COLUMN resolu_le TYPE timestamptz
    USING resolu_le AT TIME ZONE current_setting('TimeZone');
ALTER TABLE demande_echange ALTER COLUMN cible_decide_le TYPE timestamptz
    USING cible_decide_le AT TIME ZONE current_setting('TimeZone');
