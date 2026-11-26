-- Le budget de calcul (durée maximale d'une résolution, arrêt sur plateau)
-- devient un réglage d'instance : il se règle par SOLVER_SECONDS_LIMIT et
-- SOLVER_UNIMPROVED_SECONDS_LIMIT, plus par édition (ADR 0110). Les deux
-- colonnes de `parametres_solveur` quittent la table ; il n'y reste que le
-- mail de fin de résolution, qui est bien un réglage d'édition.
--
-- Rien n'est perdu pour autant : les valeurs qu'une édition avait réglées sont
-- d'abord recopiées dans `solve_budget_avant_4_0`, une archive que
-- l'application ne lit ni n'exporte et que la 5.0 supprimera (retour à la 3.x
-- ou relecture par l'exploitant d'ici là). Seules les éditions qui avaient
-- réglé quelque chose y figurent : une durée ou un plateau NULL suivait déjà
-- le défaut de l'instance.
CREATE TABLE solve_budget_avant_4_0 (
    edition_id VARCHAR(64) NOT NULL PRIMARY KEY REFERENCES edition(id) ON DELETE CASCADE,
    duree_resolution_secondes INTEGER,
    plateau_secondes INTEGER
);

INSERT INTO solve_budget_avant_4_0 (edition_id, duree_resolution_secondes, plateau_secondes)
SELECT edition_id, duree_resolution_secondes, plateau_secondes
FROM parametres_solveur
WHERE duree_resolution_secondes IS NOT NULL OR plateau_secondes IS NOT NULL;

ALTER TABLE parametres_solveur
    DROP COLUMN duree_resolution_secondes,
    DROP COLUMN plateau_secondes;

-- Une valeur enregistrée ne peut plus être coupée par un plafond : ce que
-- ces deux colonnes de l'historique des calculs disaient n'arrive plus.
ALTER TABLE solver_job
    DROP COLUMN capped_from_seconds_limit,
    DROP COLUMN capped_from_plateau_seconds;
