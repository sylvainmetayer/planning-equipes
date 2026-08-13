-- typologie references the `typologie` referential table directly (not a fixed
-- enum, see V27__typologie_foreign_keys.sql), same convention as
-- stand_typologie/animateur_competence.
CREATE TABLE IF NOT EXISTS animateur_souhait (
    animateur_id VARCHAR(64) NOT NULL REFERENCES animateur(id) ON DELETE CASCADE,
    typologie VARCHAR(64) NOT NULL REFERENCES typologie(id),
    PRIMARY KEY (animateur_id, typologie)
);
