-- La relecture ne porte plus que sur la journée entière.
--
-- V80 permettait de valider un stand d'une journée, pour déléguer la relecture
-- aux responsables de stand. À l'usage, les deux grains se sont mal entendus :
-- la case « Verrouiller aussi » figeait toute la journée même filtrée sur un
-- stand, le compteur ignorait les stands validés, et une résolution retirait la
-- validation d'un stand sur lequel rien n'avait bougé. Une seule unité reste
-- donc, celle que le compteur, le verrou et le retrait comptaient déjà : la
-- journée.
--
-- Les validations par stand déjà écrites sont supprimées plutôt que promues :
-- avoir relu un stand ne dit pas qu'on a relu la journée.

DELETE FROM validation_journee WHERE stand_id IS NOT NULL;

DROP INDEX IF EXISTS uq_validation_journee_cible;
DROP INDEX IF EXISTS idx_validation_journee_jour;

ALTER TABLE validation_journee DROP COLUMN IF EXISTS stand_id;

-- Une journée n'est validée qu'une fois : revalider remplace la ligne.
CREATE UNIQUE INDEX IF NOT EXISTS uq_validation_journee_jour
    ON validation_journee (edition_id, jour);

COMMENT ON TABLE validation_journee IS
    'A day marked reviewed and accepted; distinct from a lock';
