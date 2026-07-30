-- Every animateur is paid: the bénévole/salarié distinction is gone. Replaced
-- by a manager flag (animateur who manages other animateurs).

ALTER TABLE animateur DROP COLUMN statut;
ALTER TABLE animateur ADD COLUMN manager BOOLEAN NOT NULL DEFAULT FALSE;
