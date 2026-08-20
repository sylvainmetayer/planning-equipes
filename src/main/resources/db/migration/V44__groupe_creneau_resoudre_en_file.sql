-- Whether this group takes part in the "résoudre tous les groupes" queue
-- (issue #167): solving an "amplitudes" group that was never sliced into
-- vacations only produces a garbage plan (14h-long seats), so those groups
-- must be skippable — and the distinction amplitudes/vacations is not
-- derivable reliably, hence an explicit, user-editable flag rather than an
-- inference (see the issue's analysis comment).

ALTER TABLE groupe_creneau
    ADD COLUMN resoudre_en_file BOOLEAN NOT NULL DEFAULT TRUE;

-- Backfill heuristic, best-effort only (the flag stays editable in the UI):
-- a group referenced as another group's découpage source holds amplitudes.
UPDATE groupe_creneau g
SET resoudre_en_file = FALSE
WHERE EXISTS (
    SELECT 1 FROM groupe_creneau cible
    WHERE cible.edition_id = g.edition_id
      AND cible.groupe_source_id = g.id
);

-- ... and so does, almost surely, a group holding a créneau longer than the
-- art. L3121-16 threshold (360 min, VacationGeneratorService.SEUIL_PAUSE_LEGALE_MINUTES):
-- generated vacations never exceed it. The duration math mirrors
-- Creneau.getDureeMinutes(): heure_fin at or before heure_debut wraps past
-- midnight (equal bounds read as a full 24 h).
UPDATE groupe_creneau g
SET resoudre_en_file = FALSE
WHERE EXISTS (
    SELECT 1 FROM creneau c
    WHERE c.edition_id = g.edition_id
      AND c.groupe_creneau_id = g.id
      AND (CASE WHEN c.heure_fin > c.heure_debut
                THEN EXTRACT(EPOCH FROM (c.heure_fin - c.heure_debut)) / 60
                ELSE EXTRACT(EPOCH FROM (c.heure_fin - c.heure_debut)) / 60 + 1440
           END) > 360
);
