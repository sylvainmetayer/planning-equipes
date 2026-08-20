-- Échange dirigé : le demandeur peut désigner le créneau du collègue qu'il
-- veut RECEVOIR en échange du sien (« je te laisse mon lundi, je prends ton
-- mardi »). NULL = comportement historique, l'échange se joue sur le seul
-- créneau du demandeur (croisé même créneau ou simple reprise).
ALTER TABLE demande_echange
    ADD COLUMN creneau_cible_id bigint,
    ADD COLUMN stand_cible_id character varying;
