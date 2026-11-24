-- Comptes nominatifs et habilitations : le socle.
--
-- Un compte est une personne qui administre l'instance ; une habilitation dit
-- ce qu'elle a le droit de faire, et dans quelle édition. Le rôle est du code
-- (RoleHabilitation), le périmètre est de la donnée ; aucune matrice de
-- permissions configurable.
--
-- Deux tables, globales (hors édition : une personne sert toutes les éditions) :
--   1. compte : l'identité. Jamais supprimée, desactive_le coupe l'accès sans
--      effacer l'historique. Le journal nomme l'acteur par compte.id, jamais
--      par son adresse ;
--   2. habilitation : un rôle, une édition (NULL = toutes) et une expiration.
--      Retirée par retiree_le, jamais supprimée, pour que le journal puisse
--      encore dire qui avait quel droit à quelle date.
--
-- Rien ici ne porte de secret : la connexion (fournisseur OIDC, mot de passe
-- local) ajoute ses colonnes par ses propres migrations. Les rôles futurs
-- (lecture d'un stand, RH) élargiront le CHECK de role par une migration.

CREATE TABLE compte (
    id VARCHAR(64) PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    nom VARCHAR(255),
    cree_le TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    derniere_connexion_le TIMESTAMP WITH TIME ZONE,
    desactive_le TIMESTAMP WITH TIME ZONE,
    -- L'id du compte qui l'a désactivé (« admin » pour la configuration,
    -- « systeme » pour l'application) : le journal d'édition peut disparaître
    -- avec son édition, pas cette trace.
    desactive_par VARCHAR(64)
);

-- Une personne, une adresse : la casse ne distingue personne.
CREATE UNIQUE INDEX compte_email_unique ON compte (lower(email));

CREATE TABLE habilitation (
    id VARCHAR(64) PRIMARY KEY,
    compte_id VARCHAR(64) NOT NULL REFERENCES compte (id),
    role VARCHAR(32) NOT NULL CHECK (role IN ('ADMIN', 'ORGANISATEUR')),
    -- NULL = toutes les éditions. Sans clé étrangère, comme kpi_historique :
    -- l'import d'un dump supprime puis recrée les éditions, et une cascade
    -- effacerait au passage les droits de l'instance qui reçoit. Une
    -- habilitation qui désigne une édition disparue n'ouvre rien.
    edition_id VARCHAR(64),
    expire_le TIMESTAMP WITH TIME ZONE,
    -- L'id du compte qui a accordé le droit, ou « admin » pour le compte
    -- implicite de la configuration : jamais une adresse.
    cree_par VARCHAR(64) NOT NULL,
    cree_le TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    retiree_le TIMESTAMP WITH TIME ZONE,
    -- Qui l'a retirée, comme compte.desactive_par.
    retiree_par VARCHAR(64),
    -- Un administrateur l'est de toute l'instance.
    CONSTRAINT habilitation_admin_globale CHECK (role <> 'ADMIN' OR edition_id IS NULL)
);

CREATE INDEX idx_habilitation_compte ON habilitation (compte_id);

-- Un même droit ne s'accorde pas deux fois tant qu'il est en vigueur (non
-- retiré) : l'octroi double-cliqué ne laisse pas deux lignes à retirer.
CREATE UNIQUE INDEX habilitation_en_vigueur_unique
    ON habilitation (compte_id, role, COALESCE(edition_id, '')) WHERE retiree_le IS NULL;
