-- Temps de trajet entre emplacements : trois seuils de « Qualité
-- d'organisation » qui transforment une distance à vol d'oiseau en minutes de
-- marche (distance × facteur de détour ÷ vitesse, arrondi à la minute
-- supérieure), lus par la règle dosable `trajetInsuffisantEntrePostes` et par
-- la vérification des enchaînements.
--
-- Chaque colonne porte son défaut : une édition existante reçoit les valeurs
-- que le code applique à une édition qui n'a jamais ouvert l'écran, et la
-- nouvelle règle, MEDIUM et dosable, ne change la validité d'aucun plan.
--
-- NUMERIC plutôt que DOUBLE PRECISION pour la vitesse et le facteur : 4,5 km/h
-- et 1,3 doivent se relire tels qu'ils ont été saisis, pas en 1,2999999.

ALTER TABLE parametres_qualite
    ADD COLUMN IF NOT EXISTS vitesse_marche_km_h NUMERIC(4, 2) NOT NULL DEFAULT 4.0,
    ADD COLUMN IF NOT EXISTS facteur_detour NUMERIC(4, 2) NOT NULL DEFAULT 1.3,
    ADD COLUMN IF NOT EXISTS tolerance_trajet_minutes INTEGER NOT NULL DEFAULT 5;
