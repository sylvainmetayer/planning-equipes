-- Persist the full reference model so animateur skills / off-days and stand
-- typologies survive a restart and can be reloaded into the admin screens.

CREATE TABLE IF NOT EXISTS stand_typologie (
    stand_id VARCHAR(64) NOT NULL REFERENCES stand(id) ON DELETE CASCADE,
    typologie VARCHAR(64) NOT NULL,
    PRIMARY KEY (stand_id, typologie)
);

CREATE TABLE IF NOT EXISTS animateur_competence (
    animateur_id VARCHAR(64) NOT NULL REFERENCES animateur(id) ON DELETE CASCADE,
    typologie VARCHAR(64) NOT NULL,
    niveau VARCHAR(32) NOT NULL,
    PRIMARY KEY (animateur_id, typologie)
);

CREATE TABLE IF NOT EXISTS animateur_jour_indispo (
    animateur_id VARCHAR(64) NOT NULL REFERENCES animateur(id) ON DELETE CASCADE,
    jour DATE NOT NULL,
    PRIMARY KEY (animateur_id, jour)
);
