-- Une règle par plafond, le plafond étant lu selon l'âge (ADR 0114) : les deux
-- règles de durée quotidienne (majeur, mineur) deviennent `dureeQuotidienneMax`,
-- les deux règles de travail continu `travailContinuMax`. Le plafond de chaque
-- âge reste celui du Code du travail, lu à la date du créneau : aucun score ne
-- change pour un réglage inchangé.
--
-- Les anciens noms sont des CLÉS : `constraint_toggle` et `ponderation_contrainte`
-- les portent, édition par édition. Deux réglages d'une paire peuvent différer ;
-- la règle fusionnée prend le plus PROTECTEUR des deux, de sorte qu'aucune
-- édition ne devient moins protégée qu'elle ne l'était pour un âge :
--   - activée si l'une des deux l'était (une règle sans ligne vaut « active »,
--     l'état du catalogue) : la ligne n'est écrite que si les DEUX étaient
--     désactivées ;
--   - le niveau le plus dur : obligatoire si l'une des deux l'était (ou suivait
--     le niveau du catalogue, dur pour une règle légale) ; préférence seulement
--     si les deux l'étaient ;
--   - le poids le plus fort ; une ligne « préférence » sans poids vaut le poids
--     normal (5), pas 1.
-- Les lignes des anciens noms sont ensuite supprimées.
--
-- L'historique des réglages (`ponderation_contrainte_historique`) et le dosage
-- gardé avec un plan (`planning_resolution.dosage`) sont des archives de ce qui
-- a été fait sous le nom d'alors : ils ne sont pas réécrits.

-- 1. Interrupteurs : la règle fusionnée n'est éteinte que si les deux l'étaient.
INSERT INTO constraint_toggle (edition_id, nom, actif)
SELECT edition_id, 'dureeQuotidienneMax', FALSE
FROM constraint_toggle
WHERE nom IN ('dureeQuotidienneMaxMajeur', 'dureeQuotidienneMaxMineur')
GROUP BY edition_id
HAVING COUNT(*) = 2 AND NOT BOOL_OR(actif)
ON CONFLICT (edition_id, nom) DO NOTHING;

INSERT INTO constraint_toggle (edition_id, nom, actif)
SELECT edition_id, 'travailContinuMax', FALSE
FROM constraint_toggle
WHERE nom IN ('travailContinuMaxMajeur', 'travailContinuMaxMineur')
GROUP BY edition_id
HAVING COUNT(*) = 2 AND NOT BOOL_OR(actif)
ON CONFLICT (edition_id, nom) DO NOTHING;

DELETE FROM constraint_toggle
WHERE nom IN (
    'dureeQuotidienneMaxMajeur', 'dureeQuotidienneMaxMineur',
    'travailContinuMaxMajeur', 'travailContinuMaxMineur');

-- 2. Poids et niveau : le plus fort poids, le niveau le plus dur. Le niveau
--    (`obligatoire`) vaut VRAI si l'une des deux lignes le disait, FAUX si les
--    deux disaient « préférence », NULL (le niveau du catalogue, dur) sinon —
--    y compris quand une seule des deux lignes existe.
INSERT INTO ponderation_contrainte (edition_id, nom, poids, obligatoire)
SELECT edition_id, 'dureeQuotidienneMax',
       CASE WHEN COUNT(*) = 2 AND BOOL_AND(obligatoire IS NOT DISTINCT FROM FALSE)
            THEN MAX(COALESCE(poids, 5)) ELSE MAX(poids) END,
       CASE WHEN BOOL_OR(obligatoire) THEN TRUE
            WHEN COUNT(*) = 2 AND BOOL_AND(obligatoire IS NOT DISTINCT FROM FALSE) THEN FALSE
       END
FROM ponderation_contrainte
WHERE nom IN ('dureeQuotidienneMaxMajeur', 'dureeQuotidienneMaxMineur')
GROUP BY edition_id
HAVING MAX(poids) IS NOT NULL
    OR BOOL_OR(obligatoire)
    OR (COUNT(*) = 2 AND BOOL_AND(obligatoire IS NOT DISTINCT FROM FALSE))
ON CONFLICT (edition_id, nom) DO NOTHING;

INSERT INTO ponderation_contrainte (edition_id, nom, poids, obligatoire)
SELECT edition_id, 'travailContinuMax',
       CASE WHEN COUNT(*) = 2 AND BOOL_AND(obligatoire IS NOT DISTINCT FROM FALSE)
            THEN MAX(COALESCE(poids, 5)) ELSE MAX(poids) END,
       CASE WHEN BOOL_OR(obligatoire) THEN TRUE
            WHEN COUNT(*) = 2 AND BOOL_AND(obligatoire IS NOT DISTINCT FROM FALSE) THEN FALSE
       END
FROM ponderation_contrainte
WHERE nom IN ('travailContinuMaxMajeur', 'travailContinuMaxMineur')
GROUP BY edition_id
HAVING MAX(poids) IS NOT NULL
    OR BOOL_OR(obligatoire)
    OR (COUNT(*) = 2 AND BOOL_AND(obligatoire IS NOT DISTINCT FROM FALSE))
ON CONFLICT (edition_id, nom) DO NOTHING;

DELETE FROM ponderation_contrainte
WHERE nom IN (
    'dureeQuotidienneMaxMajeur', 'dureeQuotidienneMaxMineur',
    'travailContinuMaxMajeur', 'travailContinuMaxMineur');
