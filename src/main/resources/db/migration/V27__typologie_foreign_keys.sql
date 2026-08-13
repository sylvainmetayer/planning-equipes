-- stand_typologie/animateur_competence.typologie used to be free-form VARCHAR
-- values validated only by the (now removed) TypologieJeu Java enum. The
-- typologie table is the single source of truth going forward, so enforce it
-- with a real foreign key, matching the emplacement pattern (V9).
ALTER TABLE stand_typologie
    ADD CONSTRAINT fk_stand_typologie_typologie FOREIGN KEY (typologie) REFERENCES typologie (id);

ALTER TABLE animateur_competence
    ADD CONSTRAINT fk_animateur_competence_typologie FOREIGN KEY (typologie) REFERENCES typologie (id);
