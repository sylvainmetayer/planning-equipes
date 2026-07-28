-- Persist typologies and ad hoc constraints so the whole reference model is
-- edited directly in the database by the admin CRUD screens.

CREATE TABLE IF NOT EXISTS typologie (
    id VARCHAR(64) PRIMARY KEY,
    label VARCHAR(255) NOT NULL
);

-- Seed the canonical game typologies (mirrors the TypologieJeu enum) so the
-- stand / animator editors always offer them as options on a fresh database.
INSERT INTO typologie (id, label) VALUES
    ('STRATEGIE', 'STRATEGIE'),
    ('AMBIANCE', 'AMBIANCE'),
    ('ENFANT', 'ENFANT'),
    ('COOPERATIF', 'COOPERATIF'),
    ('ADRESSE', 'ADRESSE'),
    ('ROLE', 'ROLE'),
    ('ENIGME', 'ENIGME')
ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS contrainte_ad_hoc (
    id VARCHAR(64) PRIMARY KEY,
    type VARCHAR(32) NOT NULL,
    creneau_id VARCHAR(64) REFERENCES creneau(id) ON DELETE SET NULL,
    stand_id VARCHAR(64) REFERENCES stand(id) ON DELETE SET NULL,
    raison TEXT,
    cree_par VARCHAR(128),
    cree_le TIMESTAMP
);

CREATE TABLE IF NOT EXISTS contrainte_animateur (
    contrainte_id VARCHAR(64) NOT NULL REFERENCES contrainte_ad_hoc(id) ON DELETE CASCADE,
    animateur_id VARCHAR(64) NOT NULL REFERENCES animateur(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    PRIMARY KEY (contrainte_id, position)
);
