-- Covoiturage et arrivée groupée : un animateur demande depuis l'onglet
-- Covoiturage de son espace « Je viens avec… » (un à trois coéquipiers),
-- pendant la fenêtre de collecte mais à part de sa déclaration de
-- disponibilités ; l'admin valide ou écarte, et la
-- validation crée un ajustement manuel ARRIVEE_GROUPEE que la règle douce
-- `arriveeGroupee` lit. Rien n'est appliqué d'office avec la déclaration.
--
-- 1. Le nouveau type d'ajustement ne demande rien au schéma : la colonne
--    `contrainte_ad_hoc.type` est un texte libre depuis V3, sans contrainte de
--    valeurs.

/* --------- 2. La tolérance, seuil de « Qualité d'organisation » --------- */

-- Combien de minutes peuvent séparer les premières arrivées — et les derniers
-- départs — d'un groupe avant que la règle ne compte ce qui dépasse. Trente
-- minutes par défaut ; une édition existante reçoit ce défaut, et la règle,
-- douce et sans ajustement ARRIVEE_GROUPEE saisi, ne change aucun plan.
ALTER TABLE parametres_qualite
    ADD COLUMN IF NOT EXISTS tolerance_arrivee_groupee_minutes INTEGER NOT NULL DEFAULT 30;

/* --------- 3. Les coéquipiers déclarés --------- */

-- Une table générique, séparée de declaration_disponibilite : la demande a son
-- propre envoi et sa propre décision, et appliquer ou refuser une déclaration
-- ne la touche jamais. Générique aussi parce que le
-- binôme souhaité sur un même stand (BINOME) partagera le sélecteur et la
-- validation ; seul COVOITURAGE est écrit aujourd'hui.
--
-- Les coéquipiers sont stockés en texte, un identifiant par ligne, comme les
-- jours et les souhaits d'une déclaration (V59) : une demande est l'instantané
-- de ce que l'animateur a tapé, et un coéquipier supprimé depuis doit laisser
-- une demande lisible, pas l'effacer par cascade. Le déclarant, lui, porte une
-- clé étrangère : sa demande disparaît avec sa fiche.
--
-- Une seule demande EN_ATTENTE par animateur et par nature, garantie par un
-- index unique partiel : renvoyer sa demande remplace celle en attente.
--
-- `motif` : la raison, facultative, qu'écrit l'admin en écartant une demande
-- ou en annulant une arrivée groupée déjà validée ; l'animateur la lit dans
-- son espace. Bornée par l'application (500
-- caractères), jamais recopiée dans le journal des actions.
CREATE TABLE declaration_coequipier (
    edition_id VARCHAR(64) NOT NULL DEFAULT 'DEFAUT' REFERENCES edition(id) ON DELETE CASCADE,
    id VARCHAR(64) NOT NULL,
    animateur_id VARCHAR(64) NOT NULL,
    nature VARCHAR(32) NOT NULL,
    coequipiers TEXT NOT NULL,
    statut VARCHAR(32) NOT NULL DEFAULT 'EN_ATTENTE',
    -- L'ajustement que la validation a créé ; sans clé étrangère, un
    -- ajustement supprimé depuis ne doit pas effacer la trace de la décision.
    -- ANNULEE : l'admin a annulé l'arrivée groupée validée — l'ajustement est
    -- supprimé, la demande garde son identifiant pour l'historique.
    contrainte_id VARCHAR(64),
    cree_le TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    decide_le TIMESTAMP WITH TIME ZONE,
    motif TEXT,
    PRIMARY KEY (edition_id, id),
    CONSTRAINT declaration_coequipier_animateur_fkey
        FOREIGN KEY (edition_id, animateur_id) REFERENCES animateur (edition_id, id) ON DELETE CASCADE,
    CONSTRAINT declaration_coequipier_nature_connue CHECK (nature IN ('COVOITURAGE', 'BINOME')),
    CONSTRAINT declaration_coequipier_statut_connu CHECK (statut IN ('EN_ATTENTE', 'VALIDEE', 'ECARTEE', 'ANNULEE'))
);

CREATE UNIQUE INDEX idx_declaration_coequipier_en_attente
    ON declaration_coequipier (edition_id, animateur_id, nature)
    WHERE statut = 'EN_ATTENTE';
