-- Une règle par plafond, le plafond étant lu selon l'âge (ADR 0116) : la durée
-- hebdomadaire des mineurs rejoint `dureeHebdomadaireMax` et les deux jours de
-- repos consécutifs des mineurs rejoignent `reposHebdomadaireMinimal`. Le plafond
-- de chaque âge reste celui du Code du travail, lu à la date du créneau : aucun
-- score ne change pour un réglage inchangé.
--
-- Ici la règle fusionnée GARDE le nom d'une des deux (celle des majeurs, ou de
-- tous pour le repos) : l'ancienne ligne de ce nom ne disait un réglage que pour
-- elle seule, et compte donc dans la fusion comme celle de l'autre nom. Même
-- procédé que V152 : la règle fusionnée prend le plus PROTECTEUR des deux, de
-- sorte qu'aucune édition ne devient moins protégée qu'elle ne l'était pour un
-- âge :
--   - activée si l'une des deux l'était (une règle sans ligne vaut « active »,
--     l'état du catalogue) : la ligne n'est gardée que si les DEUX étaient
--     désactivées ;
--   - le niveau le plus dur : obligatoire si l'une des deux l'était (ou suivait
--     le niveau du catalogue, dur pour une règle légale) ; préférence seulement
--     si les deux l'étaient ;
--   - le poids le plus fort ; une ligne « préférence » sans poids vaut le poids
--     normal (5), pas 1.
-- La ligne de l'ancien nom est ensuite supprimée.
--
-- L'historique des réglages (`ponderation_contrainte_historique`) et le dosage
-- gardé avec un plan (`planning_resolution.dosage`) sont des archives de ce qui
-- a été fait sous le nom d'alors : ils ne sont pas réécrits.

-- 1. Interrupteurs : la règle fusionnée n'est éteinte que si les deux l'étaient.
--    Toute autre ligne de la paire (éteinte d'un seul côté, ou explicitement
--    allumée) disparaît : sans ligne, la règle est active.
WITH fusion(garde, retire) AS (
    VALUES ('dureeHebdomadaireMax', 'dureeHebdomadaireMaxMineur'),
           ('reposHebdomadaireMinimal', 'reposHebdomadaireMineur')
),
tout_eteint AS (
    SELECT t.edition_id, f.garde
    FROM constraint_toggle t
    JOIN fusion f ON t.nom IN (f.garde, f.retire)
    GROUP BY t.edition_id, f.garde
    HAVING COUNT(*) = 2 AND NOT BOOL_OR(t.actif)
)
DELETE FROM constraint_toggle t
USING fusion f
WHERE t.nom IN (f.garde, f.retire)
  AND NOT (t.nom = f.garde
           AND EXISTS (SELECT 1 FROM tout_eteint e
                       WHERE e.edition_id = t.edition_id AND e.garde = f.garde));

-- 2. Poids et niveau : le plus fort poids, le niveau le plus dur. Le niveau
--    (`obligatoire`) vaut VRAI si l'une des deux lignes le disait, FAUX si les
--    deux disaient « préférence », NULL (le niveau du catalogue, dur) sinon —
--    y compris quand une seule des deux lignes existe.
CREATE TEMPORARY TABLE fusion_ponderation ON COMMIT DROP AS
SELECT p.edition_id, f.garde AS nom,
       CASE WHEN COUNT(*) = 2 AND BOOL_AND(p.obligatoire IS NOT DISTINCT FROM FALSE)
            THEN MAX(COALESCE(p.poids, 5)) ELSE MAX(p.poids) END AS poids,
       CASE WHEN BOOL_OR(p.obligatoire) THEN TRUE
            WHEN COUNT(*) = 2 AND BOOL_AND(p.obligatoire IS NOT DISTINCT FROM FALSE) THEN FALSE
       END AS obligatoire
FROM ponderation_contrainte p
JOIN (VALUES ('dureeHebdomadaireMax', 'dureeHebdomadaireMaxMineur'),
             ('reposHebdomadaireMinimal', 'reposHebdomadaireMineur')) AS f(garde, retire)
  ON p.nom IN (f.garde, f.retire)
GROUP BY p.edition_id, f.garde
HAVING MAX(p.poids) IS NOT NULL
    OR BOOL_OR(p.obligatoire)
    OR (COUNT(*) = 2 AND BOOL_AND(p.obligatoire IS NOT DISTINCT FROM FALSE));

DELETE FROM ponderation_contrainte
WHERE nom IN ('dureeHebdomadaireMax', 'dureeHebdomadaireMaxMineur',
              'reposHebdomadaireMinimal', 'reposHebdomadaireMineur');

INSERT INTO ponderation_contrainte (edition_id, nom, poids, obligatoire)
SELECT edition_id, nom, poids, obligatoire FROM fusion_ponderation;
