-- Le placement préféré de la coupure repas devient un paramètre de l'édition
-- (section « Organisation »). Il réglait jusqu'ici une direction câblée par
-- fenêtre : tard le midi, tôt le soir. FIN_MIDI_DEBUT_SOIR est ce placement
-- livré : une édition existante le reçoit, et aucun score ne bouge. Les autres
-- valeurs sont DEBUT, FIN et AUCUN (terme de placement silencieux).
ALTER TABLE parametres_qualite
    ADD COLUMN IF NOT EXISTS placement_repas TEXT NOT NULL DEFAULT 'FIN_MIDI_DEBUT_SOIR'
        CHECK (placement_repas IN ('FIN_MIDI_DEBUT_SOIR', 'DEBUT', 'FIN', 'AUCUN'));

-- Une édition qui avait désactivé le terme de placement le disait par un
-- interrupteur que l'écran des règles ne montre plus (le terme n'a plus de
-- ligne) : on le reprend en « aucun », qui est la même chose à l'écran, et on
-- retire l'interrupteur devenu muet. Seules les éditions qui ont déjà enregistré
-- leurs paramètres d'organisation sont reprises : sans ligne, les valeurs
-- viennent de la configuration de l'instance, qu'une migration ne lit pas, et
-- leur interrupteur reste tel quel. Aucun score ne bouge, désactivé ou muet.
UPDATE parametres_qualite
   SET placement_repas = 'AUCUN'
 WHERE edition_id IN (SELECT edition_id FROM constraint_toggle
                       WHERE nom = 'coupureRepasPlacementPrefere' AND actif = FALSE);

DELETE FROM constraint_toggle
 WHERE nom = 'coupureRepasPlacementPrefere' AND actif = FALSE
   AND edition_id IN (SELECT edition_id FROM parametres_qualite);
