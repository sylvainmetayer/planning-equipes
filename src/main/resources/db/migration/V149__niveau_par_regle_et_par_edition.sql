-- Le niveau d'une règle d'organisation devient un réglage de l'édition
-- (ADR 0106) : « obligatoire » (niveau dur) ou préférence (niveau medium, au
-- poids stocké). La colonne `obligatoire` dit le choix de l'édition pour une
-- règle : NULL = le niveau du catalogue, vrai = obligatoire, faux = préférence.
--
-- AUCUN score ne change à la migration : la colonne démarre à NULL partout,
-- et NULL veut dire « le niveau que la règle avait ». Les lignes existantes de
-- `ponderation_contrainte` (poids) et de `constraint_toggle` (interrupteurs)
-- ne sont ni réécrites ni supprimées, il n'y a donc rien à archiver. Le poids
-- devient optionnel : une ligne peut ne porter qu'un niveau.
--
-- La forme dure des jours d'affilée (`maxJoursConsecutifsTravaillesDur`) garde
-- pour l'instant son interrupteur : sa fusion avec la forme dosée, et le
-- passage « forme dure active ⇒ obligatoire » de l'ADR 0106, viennent avec
-- cette fusion.

ALTER TABLE ponderation_contrainte
    ADD COLUMN IF NOT EXISTS obligatoire BOOLEAN;

ALTER TABLE ponderation_contrainte
    ALTER COLUMN poids DROP NOT NULL;

ALTER TABLE ponderation_contrainte
    ADD CONSTRAINT ponderation_contrainte_poids_ou_niveau
    CHECK (poids IS NOT NULL OR obligatoire IS NOT NULL);
