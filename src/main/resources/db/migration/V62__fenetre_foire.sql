-- Bornes datées de la foire au planning (retour d'usage sur #338).
--
-- `parametres_echange` (V42) ne portait qu'un interrupteur : l'organisateur
-- devait ouvrir et fermer la foire à la main, le bon jour. Les mêmes bornes
-- existent déjà pour la collecte des disponibilités (`parametres_collecte`,
-- V59) et c'est sa forme qui est reprise ici, à l'identique — deux fenêtres qui
-- se règlent sur le même écran ne doivent pas avoir deux sémantiques.
--
-- Trois choix, tous repris de V59 :
--
--   * les deux dates sont FACULTATIVES et bornent quand elles sont
--     renseignées. Une foire sans dates se comporte exactement comme avant ;
--   * `foire_ouverte` reste le MAÎTRE : une fenêtre datée mais fermée ne
--     collecte rien. L'interrupteur ferme, les dates n'ouvrent jamais seules ;
--   * l'ordre des bornes est tenu par la BASE et pas seulement par le service.
--
-- Une différence assumée avec la collecte : `foire_ouverte` reste à TRUE par
-- défaut. La collecte est une route publique en écriture qui ne doit pas
-- s'ouvrir par omission ; la foire, elle, est ouverte depuis #165 et la fermer
-- ici changerait le comportement de toute édition existante à la migration.

ALTER TABLE parametres_echange
    ADD COLUMN IF NOT EXISTS date_debut DATE,
    ADD COLUMN IF NOT EXISTS date_fin DATE;

ALTER TABLE parametres_echange
    ADD CONSTRAINT parametres_echange_fenetre_ordonnee CHECK (
        date_debut IS NULL OR date_fin IS NULL OR date_debut <= date_fin
    );
