-- Covoiturage simplifié : une demande est validée ou ne l'est pas. Les demandes
-- écartées ou annulées n'étaient que la trace d'un refus (l'ajustement qu'une
-- annulation supprimait ne revient pas) : elles quittent la table, et la
-- colonne `motif`, qui ne servait qu'à elles, avec.
--
-- Rien n'est perdu pour autant : ces lignes, motif compris, sont d'abord
-- recopiées dans `declaration_coequipier_avant_4_0`, une archive que
-- l'application ne lit ni n'exporte et que la 5.0 supprimera (retour à la 3.x
-- possible d'ici là). Le motif est un texte libre écrit sur une personne :
-- l'archive garde les deux clés étrangères de la table d'origine, si bien
-- qu'elle part avec l'édition (purge annuelle) ou avec la fiche du demandeur.
-- `contrainte_id` n'est pas recopiée : elle est vide sur ces lignes (un écart
-- n'en crée pas, l'annulation supprimait l'ajustement, V126 vide la colonne).
CREATE TABLE declaration_coequipier_avant_4_0 (
    edition_id VARCHAR(64) NOT NULL REFERENCES edition(id) ON DELETE CASCADE,
    id VARCHAR(64) NOT NULL,
    animateur_id VARCHAR(64) NOT NULL,
    nature VARCHAR(32) NOT NULL,
    coequipiers TEXT NOT NULL,
    statut VARCHAR(32) NOT NULL,
    cree_le TIMESTAMP WITH TIME ZONE NOT NULL,
    decide_le TIMESTAMP WITH TIME ZONE,
    motif TEXT,
    PRIMARY KEY (edition_id, id),
    CONSTRAINT declaration_coequipier_avant_4_0_animateur_fkey
        FOREIGN KEY (edition_id, animateur_id) REFERENCES animateur (edition_id, id) ON DELETE CASCADE
);

CREATE INDEX idx_declaration_coequipier_avant_4_0_animateur
    ON declaration_coequipier_avant_4_0 (edition_id, animateur_id);

-- Un motif sur une demande encore vivante n'a jamais été écrit par la 3.x ; s'il
-- en existait un, il serait gardé lui aussi plutôt que perdu avec la colonne.
INSERT INTO declaration_coequipier_avant_4_0
    (edition_id, id, animateur_id, nature, coequipiers, statut, cree_le, decide_le, motif)
SELECT edition_id, id, animateur_id, nature, coequipiers, statut, cree_le, decide_le, motif
FROM declaration_coequipier
WHERE statut IN ('ECARTEE', 'ANNULEE') OR motif IS NOT NULL;

DELETE FROM declaration_coequipier WHERE statut IN ('ECARTEE', 'ANNULEE');

ALTER TABLE declaration_coequipier DROP COLUMN motif;

ALTER TABLE declaration_coequipier DROP CONSTRAINT declaration_coequipier_statut_connu;
ALTER TABLE declaration_coequipier
    ADD CONSTRAINT declaration_coequipier_statut_connu CHECK (statut IN ('EN_ATTENTE', 'VALIDEE'));
