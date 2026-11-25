-- Le droit « RH » : lire le rapport de conformité d'une édition (et, plus tard,
-- les contacts des animateurs). Un rôle de plus dans `habilitation`, toujours
-- porté par une édition : on est RH d'un événement, jamais de tous.

ALTER TABLE habilitation DROP CONSTRAINT habilitation_role_check;
ALTER TABLE habilitation ADD CONSTRAINT habilitation_role_check
    CHECK (role IN ('ADMIN', 'ORGANISATEUR', 'RESPONSABLE_STAND', 'RH'));
ALTER TABLE habilitation ADD CONSTRAINT habilitation_rh_edition
    CHECK (role <> 'RH' OR edition_id IS NOT NULL);
