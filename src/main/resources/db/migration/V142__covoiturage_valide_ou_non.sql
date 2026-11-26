-- Covoiturage simplifié : une demande est validée ou ne l'est pas. Les demandes
-- écartées ou annulées n'étaient que la trace d'un refus (l'ajustement qu'une
-- annulation supprimait ne revient pas) : elles sont supprimées, et la colonne
-- `motif`, qui ne servait qu'à elles, avec.
DELETE FROM declaration_coequipier WHERE statut IN ('ECARTEE', 'ANNULEE');

ALTER TABLE declaration_coequipier DROP COLUMN motif;

ALTER TABLE declaration_coequipier DROP CONSTRAINT declaration_coequipier_statut_connu;
ALTER TABLE declaration_coequipier
    ADD CONSTRAINT declaration_coequipier_statut_connu CHECK (statut IN ('EN_ATTENTE', 'VALIDEE'));
