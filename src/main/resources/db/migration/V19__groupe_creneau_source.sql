-- Traces which "amplitudes" group (see VacationGeneratorService) a group of
-- vacations was auto-generated from, so a "régénérer" action can find its
-- source again. Purely informational, never read by the solver.

ALTER TABLE groupe_creneau
    ADD COLUMN IF NOT EXISTS groupe_source_id VARCHAR(64) REFERENCES groupe_creneau(id) ON DELETE SET NULL;
