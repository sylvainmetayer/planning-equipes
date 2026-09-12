-- Les familles de relais sont retirées (ADR 0029) : plus de variantes de
-- vacations décalées par famille, plus d'appariement stand ↔ famille.
-- Une grille décalée ne garde que sa première famille ; ce qui s'appuyait sur
-- les créneaux des autres familles part avec eux, comme quand une grille est
-- remplacée. Les plans de ces éditions sont à recalculer.
DELETE FROM poste_affectation WHERE creneau_id IN (SELECT id FROM creneau WHERE famille > 0);
DELETE FROM verrouillage_planning WHERE creneau_id IN (SELECT id FROM creneau WHERE famille > 0);
DELETE FROM demande_echange WHERE creneau_id IN (SELECT id FROM creneau WHERE famille > 0);
DELETE FROM creneau_stand_ouvert WHERE creneau_id IN (SELECT id FROM creneau WHERE famille > 0);
UPDATE contrainte_ad_hoc SET creneau_id = NULL WHERE creneau_id IN (SELECT id FROM creneau WHERE famille > 0);
DELETE FROM planning_resolution WHERE edition_id IN (SELECT DISTINCT edition_id FROM creneau WHERE famille > 0);
DELETE FROM creneau WHERE famille > 0;

ALTER TABLE creneau DROP COLUMN famille;
ALTER TABLE stand DROP COLUMN famille;
ALTER TABLE parametres_decoupage DROP COLUMN nombre_familles_decalage;
ALTER TABLE parametres_decoupage DROP COLUMN duree_decalage_max_minutes;
