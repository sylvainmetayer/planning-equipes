-- Single-row table for admin-configurable découpage-automatique parameters
-- (VacationGeneratorService): target/min/max vacation length, relay overlap,
-- meal-break windows and duration, and the coverage strategy used when an
-- internal pause cannot be avoided. Defaults keep every generated vacation
-- strictly under 6h (art. L3121-16), so no vacation ever needs an internal
-- legal break.

CREATE TABLE IF NOT EXISTS parametres_decoupage (
    id INTEGER PRIMARY KEY,
    duree_vacation_cible_minutes INTEGER NOT NULL DEFAULT 300,
    duree_vacation_min_minutes INTEGER NOT NULL DEFAULT 180,
    duree_vacation_max_minutes INTEGER NOT NULL DEFAULT 360,
    duree_chevauchement_minutes INTEGER NOT NULL DEFAULT 30,
    duree_pause_repas_minutes INTEGER NOT NULL DEFAULT 45,
    fenetre_repas_midi_debut TIME NOT NULL DEFAULT '12:00',
    fenetre_repas_midi_fin TIME NOT NULL DEFAULT '14:00',
    fenetre_repas_soir_debut TIME NOT NULL DEFAULT '19:00',
    fenetre_repas_soir_fin TIME NOT NULL DEFAULT '21:00',
    strategie_couverture_pendant_pause VARCHAR(16) NOT NULL DEFAULT 'FERMETURE',
    CONSTRAINT parametres_decoupage_singleton CHECK (id = 1),
    CONSTRAINT parametres_decoupage_strategie_valide
        CHECK (strategie_couverture_pendant_pause IN ('FERMETURE', 'RELEVE'))
);

INSERT INTO parametres_decoupage (id) VALUES (1)
ON CONFLICT (id) DO NOTHING;
