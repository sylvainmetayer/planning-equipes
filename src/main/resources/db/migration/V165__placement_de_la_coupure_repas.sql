-- Le placement préféré de la coupure repas devient un paramètre de l'édition
-- (section « Organisation »). Il réglait jusqu'ici une direction câblée par
-- fenêtre : tard le midi, tôt le soir. FIN_MIDI_DEBUT_SOIR est ce placement
-- livré : une édition existante le reçoit, et aucun score ne bouge. Les autres
-- valeurs sont DEBUT, FIN et AUCUN (terme de placement silencieux).
ALTER TABLE parametres_qualite
    ADD COLUMN IF NOT EXISTS placement_repas TEXT NOT NULL DEFAULT 'FIN_MIDI_DEBUT_SOIR'
        CHECK (placement_repas IN ('FIN_MIDI_DEBUT_SOIR', 'DEBUT', 'FIN', 'AUCUN'));
