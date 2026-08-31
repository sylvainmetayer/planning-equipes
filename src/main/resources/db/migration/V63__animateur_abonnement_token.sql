-- Jeton d'abonnement ICS, distinct du jeton d'espace.
--
-- `access_token` (V41/V52) ouvre l'espace animateur, mais il ne suffit à rien
-- seul : chaque route de l'espace exige en plus une session ouverte par code
-- e-mail. Un client d'agenda qui s'abonne à une URL ne porte aucun cookie, et
-- recevrait donc un 401 à sa première resynchronisation.
--
-- Plutôt que d'élargir ce que `access_token` permet — il deviendrait, sur une
-- route au moins, une preuve d'accès complète et durable au planning nominatif
-- sans second facteur —, l'abonnement reçoit son propre jeton. Les deux se
-- révoquent séparément : régénérer l'un ne touche pas l'autre.
--
-- Même génération que l'autre jeton (`gen_random_uuid()`, 122 bits d'aléa) et
-- même unicité GLOBALE, hors édition : le jeton arrive sur une URL publique
-- sans en-tête `X-Edition-Id` à croire, il doit donc désigner son édition à
-- lui seul.
--
-- Le défaut est volatile, donc PostgreSQL réécrit la table et attribue une
-- valeur distincte à chaque ligne existante (c'est déjà ce que faisait V41) :
-- l'index unique ci-dessous ne peut pas échouer sur des lignes déjà présentes.
--
-- Conséquence sur les dumps : `DatabaseDumpService` nomme ses colonnes une à
-- une, donc un dump PRIS AVANT cette migration se réimporte sans problème (la
-- colonne absente prend son défaut, et chaque animateur repart avec un jeton
-- d'abonnement neuf). L'inverse n'est pas vrai, comme pour toute migration.

ALTER TABLE animateur
    ADD COLUMN abonnement_token VARCHAR(64) NOT NULL DEFAULT gen_random_uuid()::text;

CREATE UNIQUE INDEX idx_animateur_abonnement_token ON animateur (abonnement_token);
