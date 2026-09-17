-- `coupureRepasAuPlusTot` devient `coupureRepasPlacementPrefere` : la règle ne
-- préfère plus la coupure la plus tôt dans les deux fenêtres, mais celle vers
-- laquelle la fenêtre penche — le midi la plus tard, le soir la plus tôt
-- (issue #596). L'ancien nom ne décrivait plus la règle.
--
-- Ce nom est une CLÉ : `constraint_toggle` et `ponderation_contrainte` le
-- portent. Une ligne restée sur l'ancien nom ne correspondrait plus à aucune
-- contrainte du catalogue : invisible dans l'IHM, et toujours en base. On la
-- reporte, comme V14 l'avait fait pour `pasDeChevauchementHoraire`, pour ne pas
-- réactiver en silence une règle qu'un administrateur avait désactivée, ni
-- perdre un poids qu'il avait réglé.

INSERT INTO constraint_toggle (edition_id, nom, actif)
SELECT edition_id, 'coupureRepasPlacementPrefere', actif
FROM constraint_toggle
WHERE nom = 'coupureRepasAuPlusTot'
ON CONFLICT (edition_id, nom) DO NOTHING;

DELETE FROM constraint_toggle WHERE nom = 'coupureRepasAuPlusTot';

INSERT INTO ponderation_contrainte (edition_id, nom, poids)
SELECT edition_id, 'coupureRepasPlacementPrefere', poids
FROM ponderation_contrainte
WHERE nom = 'coupureRepasAuPlusTot'
ON CONFLICT (edition_id, nom) DO NOTHING;

DELETE FROM ponderation_contrainte WHERE nom = 'coupureRepasAuPlusTot';
