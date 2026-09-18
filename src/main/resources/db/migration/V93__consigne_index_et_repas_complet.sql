-- Consigne d'édition (issue #4), suite de la relecture de V90 et V92.
--
-- Deux index que les clés étrangères de V90 appelaient : la suppression d'un
-- stand parcourt ses ouvertures de consigne, celle d'un créneau la marque
-- « ajouté par consigne » — et le décompte des usages avant suppression pose
-- désormais la même question, sur toute une sélection à la fois.
CREATE INDEX idx_consigne_edition_ouverture_stand ON consigne_edition_ouverture (edition_id, stand_id);
CREATE INDEX idx_consigne_edition_creneau_creneau ON consigne_edition_creneau (creneau_id);

-- Une fenêtre repas surchargée se donne avec ses deux bornes ou aucune. Le
-- service le refusait déjà ; l'import de scénario passait à côté et V92 ne
-- vérifiait que l'ordre des bornes, si bien qu'un fichier pouvait laisser
-- une demi-fenêtre en base — que rien en aval ne sait lire.
ALTER TABLE consigne_edition
    ADD CONSTRAINT consigne_edition_repas_complet CHECK (
        (repas_midi_debut IS NULL) = (repas_midi_fin IS NULL)
        AND (repas_soir_debut IS NULL) = (repas_soir_fin IS NULL));

ALTER TABLE prereglage_consigne
    ADD CONSTRAINT prereglage_consigne_repas_complet CHECK (
        (repas_midi_debut IS NULL) = (repas_midi_fin IS NULL)
        AND (repas_soir_debut IS NULL) = (repas_soir_fin IS NULL)),
    -- La date de création, comme sur consigne_edition : un préréglage aussi a
    -- une histoire. Les lignes existantes prennent celle de leur dernière
    -- modification, la seule connue.
    ADD COLUMN cree_le TIMESTAMPTZ;

UPDATE prereglage_consigne SET cree_le = modifie_le WHERE cree_le IS NULL;

ALTER TABLE prereglage_consigne
    ALTER COLUMN cree_le SET NOT NULL,
    ALTER COLUMN cree_le SET DEFAULT now();
