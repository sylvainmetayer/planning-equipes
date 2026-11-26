-- Une seule règle de jours d'affilée, à niveau réglable (ADR 0118) : la forme
-- dure `maxJoursConsecutifsTravaillesDur`, livrée éteinte, disparaît ;
-- `maxJoursConsecutifsTravailles` prend le niveau que l'édition choisit
-- (préférence par défaut, ou obligatoire : ADR 0106, 0111).
--
-- Lecture la plus protectrice, comme pour V152 :
--   - une édition qui avait ALLUMÉ la forme dure garde un plafond bloquant : la
--     règle fusionnée devient obligatoire (`obligatoire` = vrai), et elle est
--     rallumée si la forme dosée avait été éteinte ;
--   - sinon, rien ne change : la règle fusionnée garde le niveau et le poids de
--     l'ancienne forme dosée, dont elle porte le nom.
-- Le poids stocké de la forme dosée est gardé tel quel : c'est celui que la
-- règle reprend si l'édition la repasse en préférence.
--
-- Les lignes de l'ancienne forme dure sont ensuite supprimées. L'historique des
-- réglages et le dosage gardé avec un plan restent sous le nom d'alors.

-- 1. Forme dure allumée ⇒ règle fusionnée obligatoire, poids de la forme dosée gardé.
INSERT INTO ponderation_contrainte (edition_id, nom, poids, obligatoire)
SELECT edition_id, 'maxJoursConsecutifsTravailles', NULL, TRUE
FROM constraint_toggle
WHERE nom = 'maxJoursConsecutifsTravaillesDur' AND actif
ON CONFLICT (edition_id, nom) DO UPDATE SET obligatoire = TRUE;

-- 2. …et active, même si la forme dosée avait été éteinte.
DELETE FROM constraint_toggle t
WHERE t.nom = 'maxJoursConsecutifsTravailles'
  AND NOT t.actif
  AND EXISTS (
      SELECT 1 FROM constraint_toggle d
      WHERE d.edition_id = t.edition_id
        AND d.nom = 'maxJoursConsecutifsTravaillesDur'
        AND d.actif);

-- 3. Plus de forme dure.
DELETE FROM constraint_toggle WHERE nom = 'maxJoursConsecutifsTravaillesDur';
DELETE FROM ponderation_contrainte WHERE nom = 'maxJoursConsecutifsTravaillesDur';
