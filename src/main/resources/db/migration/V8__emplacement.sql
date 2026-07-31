-- Physical locations (kiosque, mairie, château, ...) a stand can be set up
-- at, geocoded so the solver can penalise moving an animateur between two
-- distant locations on consecutive slots (see QualiteConstraints).

CREATE TABLE IF NOT EXISTS emplacement (
    id VARCHAR(64) PRIMARY KEY,
    nom VARCHAR(255) NOT NULL,
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION
);

ALTER TABLE stand ADD COLUMN emplacement_id VARCHAR(64) REFERENCES emplacement(id);

-- Sample locations in a fictional town centre, so the feature has something
-- to look at out of the box. Stands are not linked automatically: existing
-- reference data is left untouched, the link is made from the Stands screen.
INSERT INTO emplacement (id, nom, latitude, longitude) VALUES
    ('PLACE-DRAPEAU', 'Place du Drapeau', 46.6513, 2.2492),
    ('MAIRIE', 'Mairie centrale', 46.6490, 2.2547),
    ('CHATEAU', 'Château', 46.6517, 2.2481),
    ('PLACE-11-NOVEMBRE', 'Place du 11 Novembre', 46.6480, 2.2555)
ON CONFLICT (id) DO NOTHING;
