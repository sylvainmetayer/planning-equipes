-- One typologie of the referential can be flagged "ninja": the animateurs who
-- hold it are versatile enough to be dispatched on any stand, whatever the
-- typologies it proposes. There is at most one ninja typologie for the whole
-- referential — enforced by a partial unique index rather than only in the
-- service layer, so a scenario import or a manual SQL edit can't create a
-- second one behind the API's back.
ALTER TABLE typologie
    ADD COLUMN ninja BOOLEAN NOT NULL DEFAULT FALSE;

CREATE UNIQUE INDEX ux_typologie_ninja ON typologie (ninja) WHERE ninja;
