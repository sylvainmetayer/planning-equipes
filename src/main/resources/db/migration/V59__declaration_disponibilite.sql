-- Déclaration de disponibilités et de souhaits en libre-service (issue #291) :
-- l'animateur propose depuis son espace ce qu'il déclare, l'organisation
-- applique ou refuse. C'est la première ÉCRITURE ouverte depuis l'espace, d'où
-- deux garde-fous portés par le schéma lui-même.
--
--   1. une fenêtre de collecte par édition, fermée par défaut — contrairement à
--      la foire au planning (V42) qui est ouverte tant que rien n'a été décidé.
--      Une route publique en écriture ne s'ouvre pas par omission ;
--   2. une seule proposition EN_ATTENTE par animateur, garantie par un index
--      unique partiel : renvoyer une déclaration remplace la précédente, et
--      l'admin n'arbitre jamais deux versions contradictoires de la même
--      personne. C'est aussi ce qui borne ce qu'un porteur de jeton peut écrire
--      en base — une ligne, quel que soit le nombre d'envois.

/* --------- 1. Fenêtre de collecte --------- */

-- Une ligne par édition, absente tant que l'admin n'a rien décidé : la collecte
-- est alors FERMÉE. Les deux dates bornent la fenêtre quand elles sont
-- renseignées ; l'interrupteur reste le maître, une fenêtre datée mais fermée
-- ne collecte rien.
CREATE TABLE parametres_collecte (
    edition_id VARCHAR(64) NOT NULL DEFAULT 'DEFAUT' REFERENCES edition(id) ON DELETE CASCADE,
    collecte_ouverte BOOLEAN NOT NULL DEFAULT FALSE,
    date_debut DATE,
    date_fin DATE,
    PRIMARY KEY (edition_id),
    CONSTRAINT parametres_collecte_fenetre_ordonnee CHECK (
        date_debut IS NULL OR date_fin IS NULL OR date_debut <= date_fin
    )
);

/* --------- 2. Déclarations --------- */

-- Les jours et les souhaits sont stockés en texte, une valeur par ligne, et non
-- en tables filles avec clé étrangère : une déclaration est l'instantané figé
-- de ce que l'animateur a dit, à la manière de plan_snapshot.groupe_creneau_id
-- ou de demande_echange.contraintes_violees. Une typologie supprimée entre la
-- soumission et la décision doit faire échouer l'application avec un message
-- clair, pas effacer silencieusement la proposition par cascade.
CREATE TABLE declaration_disponibilite (
    edition_id VARCHAR(64) NOT NULL DEFAULT 'DEFAUT' REFERENCES edition(id) ON DELETE CASCADE,
    id VARCHAR(64) NOT NULL,
    animateur_id VARCHAR(64) NOT NULL,
    -- Une date ISO par ligne ; vide signifie « disponible tous les jours »,
    -- ce qui est une déclaration en soi et non une absence de déclaration.
    jours_indisponibles TEXT,
    -- Un identifiant de typologie par ligne.
    souhaits TEXT,
    -- Mot libre de l'animateur à l'organisation (« je pars le dimanche midi »).
    commentaire TEXT,
    statut VARCHAR(32) NOT NULL DEFAULT 'EN_ATTENTE',
    commentaire_admin TEXT,
    cree_le TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    decide_le TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (edition_id, id),
    CONSTRAINT declaration_disponibilite_animateur_fkey
        FOREIGN KEY (edition_id, animateur_id) REFERENCES animateur (edition_id, id) ON DELETE CASCADE,
    CONSTRAINT declaration_disponibilite_statut_connu CHECK (
        statut IN ('EN_ATTENTE', 'APPLIQUEE', 'REFUSEE')
    )
);

-- La règle « une seule proposition en attente par animateur », tenue par la
-- base et pas seulement par le service.
CREATE UNIQUE INDEX idx_declaration_disponibilite_en_attente
    ON declaration_disponibilite (edition_id, animateur_id)
    WHERE statut = 'EN_ATTENTE';

-- L'écran admin liste les propositions en attente, les plus récentes d'abord.
CREATE INDEX idx_declaration_disponibilite_statut
    ON declaration_disponibilite (edition_id, statut, cree_le DESC);
