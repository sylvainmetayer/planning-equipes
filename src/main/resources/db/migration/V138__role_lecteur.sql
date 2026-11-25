-- Le droit « Lecteur » : voir ce que voit un organisateur dans une édition, sans
-- rien écrire (ADR 0099). Un rôle de plus dans `habilitation`, toujours porté
-- par une édition : on lit un événement, jamais tous.

ALTER TABLE habilitation DROP CONSTRAINT habilitation_role_check;
ALTER TABLE habilitation ADD CONSTRAINT habilitation_role_check
    CHECK (role IN ('ADMIN', 'ORGANISATEUR', 'RESPONSABLE_STAND', 'RH', 'LECTEUR'));
ALTER TABLE habilitation ADD CONSTRAINT habilitation_lecteur_edition
    CHECK (role <> 'LECTEUR' OR edition_id IS NOT NULL);
