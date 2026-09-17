-- La durée de la pause qui coupe une période de travail continu était une
-- constante : 20 min pour un majeur (art. L3121-16), 30 min pour un mineur
-- (art. L3162-3). C'est pourtant la règle de cette famille qu'une organisation
-- veut allonger — une relève de 30 min s'organise plus simplement qu'une de 20
-- (issue #592).
--
-- Elle rejoint donc les autres règles de temps de travail dans
-- `parametres_legaux`. Les valeurs par défaut sont les planchers légaux : une
-- édition qui n'y touche pas se comporte exactement comme avant. Le service
-- refuse une valeur INFÉRIEURE au plancher — il est d'ordre public — et laisse
-- libre tout ce qui va au-delà.

ALTER TABLE parametres_legaux
    ADD COLUMN IF NOT EXISTS duree_pause_majeur_minutes INTEGER NOT NULL DEFAULT 20,
    ADD COLUMN IF NOT EXISTS duree_pause_mineur_minutes INTEGER NOT NULL DEFAULT 30;
