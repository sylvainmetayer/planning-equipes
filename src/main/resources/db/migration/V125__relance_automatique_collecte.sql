-- La relance automatique de la collecte des disponibilités : à trois jours de
-- la fin de la fenêtre, chaque animateur invité à déclarer et qui n'a rien
-- déclaré reçoit un rappel avec le lien de son espace.
--
-- Un interrupteur par édition, sur la ligne de la fenêtre qu'il concerne, et
-- ÉTEINT par défaut : une collecte déjà ouverte avant cette version ne se met
-- pas à écrire aux gens sans que personne l'ait décidé. Sans ligne (collecte
-- jamais configurée), il n'y a ni fenêtre ni relance.
--
-- Chaque envoi est réservé dans `notification_planifiee` (type
-- RELANCE_COLLECTE, clé `animateur|fin`) : un seul par personne et par date de
-- fin, et une fin déplacée rouvre la relance.
ALTER TABLE parametres_collecte
    ADD COLUMN relance_automatique BOOLEAN NOT NULL DEFAULT FALSE;
