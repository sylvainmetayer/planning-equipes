-- Per-timeslot stand availability: a stand is open on every timeslot by
-- default. Rows here restrict a timeslot to only the listed stands; a
-- timeslot with no row stays fully open.

CREATE TABLE IF NOT EXISTS creneau_stand_ouvert (
    creneau_id VARCHAR(64) NOT NULL REFERENCES creneau(id) ON DELETE CASCADE,
    stand_id VARCHAR(64) NOT NULL REFERENCES stand(id) ON DELETE CASCADE,
    PRIMARY KEY (creneau_id, stand_id)
);
