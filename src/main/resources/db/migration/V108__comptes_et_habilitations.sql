-- Comptes nominatifs et habilitations (issues #294 et #295, ADR 0054).
--
-- Keycloak dit QUI appelle — une adresse vérifiée, un second facteur pour les
-- administrateurs — et porte les rôles globaux du realm (admin, mcp,
-- animateur). Ce que cette personne a le droit de faire, et OÙ, vit ici : une
-- habilitation porte un rôle, une édition (NULL = toutes) et une date
-- d'expiration. Le rôle est du code, le périmètre est de la donnée ; aucune
-- matrice de permissions configurable.
--
-- Trois tables :
--   1. compte : l'identité, globale donc hors édition. Créée à la première
--      connexion Keycloak (ou à l'avance par un administrateur, pour poser une
--      habilitation avant la venue de la personne). Jamais supprimée :
--      desactive_le coupe l'accès sans effacer l'historique ;
--   2. habilitation : ce que ce compte a le droit de faire. Retirée par
--      retiree_le, jamais supprimée, pour que le journal (#296) puisse encore
--      dire qui avait quel droit à quelle date ;
--   3. habilitation_stand : le périmètre d'un responsable de stand (#295),
--      posé ici et exploité par le lot qui ouvrira ce rôle. Les stands sont
--      scopés par édition, d'où le couple (edition_id, stand_id).
--
-- Et deux tables retirées : le code à six chiffres de l'espace animateur et
-- ses sessions, que la session Keycloak remplace.

DROP TABLE IF EXISTS espace_session;
DROP TABLE IF EXISTS espace_acces;

CREATE TABLE compte (
    id VARCHAR(64) PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    nom VARCHAR(255),
    -- Le `sub` du jeton : stable même si la personne change d'adresse dans le
    -- realm. NULL tant que le compte, créé à l'avance, n'a jamais servi.
    sujet VARCHAR(255) UNIQUE,
    cree_le TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    derniere_connexion_le TIMESTAMP WITH TIME ZONE,
    desactive_le TIMESTAMP WITH TIME ZONE
);

-- Une personne, une adresse : la casse ne distingue personne.
CREATE UNIQUE INDEX compte_email_unique ON compte (lower(email));

CREATE TABLE habilitation (
    id VARCHAR(64) PRIMARY KEY,
    compte_id VARCHAR(64) NOT NULL REFERENCES compte (id),
    role VARCHAR(32) NOT NULL CHECK (role IN ('RH', 'RESPONSABLE_STAND')),
    -- NULL = toutes les éditions. Sans clé étrangère, comme kpi_historique :
    -- l'import d'un dump supprime puis recrée les éditions, et une cascade
    -- effacerait au passage les droits de l'instance qui reçoit — que le dump
    -- ne transporte pas (DatabaseDumpService). Une habilitation qui désigne
    -- une édition disparue n'ouvre rien.
    edition_id VARCHAR(64),
    expire_le TIMESTAMP WITH TIME ZONE,
    cree_par VARCHAR(320) NOT NULL,
    cree_le TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    retiree_le TIMESTAMP WITH TIME ZONE,
    -- Un responsable de stand n'a de sens que dans une édition : les ids de
    -- stand y sont scopés.
    CONSTRAINT habilitation_stand_scopee CHECK (role <> 'RESPONSABLE_STAND' OR edition_id IS NOT NULL)
);

CREATE INDEX idx_habilitation_compte ON habilitation (compte_id);

-- Sans clé étrangère vers stand, pour la raison d'habilitation.edition_id :
-- l'import d'un dump supprime puis recrée les stands, et une cascade viderait
-- en silence le périmètre de chaque responsable. Le stand est vérifié à
-- l'octroi (CompteService.grant) ; un stand disparu n'ouvre plus rien.
CREATE TABLE habilitation_stand (
    habilitation_id VARCHAR(64) NOT NULL REFERENCES habilitation (id) ON DELETE CASCADE,
    edition_id VARCHAR(64) NOT NULL,
    stand_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (habilitation_id, stand_id)
);
