-- Premium (editor/publisher) stands: high-visibility stands where the solver
-- should avoid rotating staff and prefer experienced animateurs.

ALTER TABLE stand ADD COLUMN premium BOOLEAN NOT NULL DEFAULT FALSE;
