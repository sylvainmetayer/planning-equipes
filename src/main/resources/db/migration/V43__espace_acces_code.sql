-- Authentification passwordless de l'espace animateur (suite de l'issue
-- #165) : le lien (jeton) ne suffit plus, car l'espace permet désormais de
-- télécharger le planning — un minimum d'authentification s'impose. Le
-- deuxième facteur est la boîte mail de l'animateur : un code à 6 chiffres
-- envoyé à l'adresse de sa fiche, échangé contre une session durable.
--
-- Deux tables :
--   1. espace_acces : le code en attente (haché, jamais en clair), une ligne
--      au plus par animateur — redemander un code remplace le précédent ;
--   2. espace_session : les sessions ouvertes (jeton de session opaque,
--      stocké haché), portées par un cookie HttpOnly limité au chemin de
--      l'espace. Résolution globale comme le jeton d'accès : le cookie seul
--      identifie (édition, animateur).

CREATE TABLE espace_acces (
    edition_id VARCHAR(64) NOT NULL DEFAULT 'DEFAUT',
    animateur_id VARCHAR(64) NOT NULL,
    code_hash VARCHAR(64) NOT NULL,
    expire_le TIMESTAMP WITH TIME ZONE NOT NULL,
    tentatives_restantes INTEGER NOT NULL,
    PRIMARY KEY (edition_id, animateur_id),
    CONSTRAINT espace_acces_animateur_fkey
        FOREIGN KEY (edition_id, animateur_id) REFERENCES animateur (edition_id, id) ON DELETE CASCADE
);

CREATE TABLE espace_session (
    session_hash VARCHAR(64) NOT NULL,
    edition_id VARCHAR(64) NOT NULL DEFAULT 'DEFAUT',
    animateur_id VARCHAR(64) NOT NULL,
    expire_le TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (session_hash),
    CONSTRAINT espace_session_animateur_fkey
        FOREIGN KEY (edition_id, animateur_id) REFERENCES animateur (edition_id, id) ON DELETE CASCADE
);

CREATE INDEX idx_espace_session_animateur ON espace_session (edition_id, animateur_id);
