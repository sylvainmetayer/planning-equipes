-- Le responsable de stand : un compte portant un droit « lecture de mon stand ».
--
-- Il lit, en lecture seule et sur l'édition ACTIVE seulement (ADR 0072), les
-- horaires et les trous du plan publié de ses stands. Les noms des animateurs
-- ne lui sont montrés que si un administrateur l'a décidé.
--
-- 1. habilitation : un rôle de plus. Le droit porte toujours une édition (un
--    responsable l'est d'un événement, jamais de toutes les éditions) ;
-- 2. habilitation_stand : les stands que couvre ce droit. Comme
--    habilitation.edition_id, sans clé étrangère vers `stand` : l'import d'un
--    dump supprime puis recrée les stands, et une cascade effacerait au
--    passage le périmètre des responsables de l'instance. Un stand disparu
--    n'ouvre rien ;
-- 3. reglage_noms_responsable : le réglage par défaut de l'édition. Absent
--    tant que rien n'a été réglé, ce qui vaut « horaires seuls ». La date
--    d'ouverture, facultative, est l'instant à partir duquel les noms
--    s'affichent (avant : horaires seuls) ;
-- 4. reglage_noms_stand : l'exception d'un stand, dans les deux sens, avec sa
--    propre date d'ouverture. Elle suit l'édition (export, duplication).
--
-- Nominatif veut dire prénom et nom, jamais de coordonnées ni d'âge.

ALTER TABLE habilitation DROP CONSTRAINT habilitation_role_check;
ALTER TABLE habilitation ADD CONSTRAINT habilitation_role_check
    CHECK (role IN ('ADMIN', 'ORGANISATEUR', 'RESPONSABLE_STAND'));
ALTER TABLE habilitation ADD CONSTRAINT habilitation_responsable_edition
    CHECK (role <> 'RESPONSABLE_STAND' OR edition_id IS NOT NULL);

CREATE TABLE habilitation_stand (
    habilitation_id VARCHAR(64) NOT NULL REFERENCES habilitation (id),
    stand_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (habilitation_id, stand_id)
);

CREATE TABLE reglage_noms_responsable (
    edition_id VARCHAR(64) PRIMARY KEY REFERENCES edition (id) ON DELETE CASCADE,
    noms_visibles BOOLEAN NOT NULL DEFAULT FALSE,
    ouverture_le TIMESTAMP WITH TIME ZONE
);

CREATE TABLE reglage_noms_stand (
    edition_id VARCHAR(64) NOT NULL,
    stand_id VARCHAR(64) NOT NULL,
    noms_visibles BOOLEAN NOT NULL,
    ouverture_le TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (edition_id, stand_id),
    FOREIGN KEY (edition_id, stand_id) REFERENCES stand (edition_id, id) ON DELETE CASCADE
);
