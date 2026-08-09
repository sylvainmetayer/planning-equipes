-- New TypologieJeu literal for the "Homme-jeu" mobile street-animation stand
-- (issue #93): an animation-de-rue competency, not a game genre like the
-- other six, but reusing the same enum/table plugs it into the existing
-- eligibility mechanism (Animateur.estEligiblePour) for free.

INSERT INTO typologie (id, label) VALUES
    ('HOMME_JEU', 'HOMME_JEU')
ON CONFLICT (id) DO NOTHING;
