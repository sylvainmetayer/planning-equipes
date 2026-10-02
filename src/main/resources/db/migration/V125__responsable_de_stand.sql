-- Le responsable de stand ouvre ses routes (issue #295, ADR 0076).
--
-- Ce qu'il voit est le plan PUBLIÉ des stands de son périmètre. Deux réglages
-- décident s'il y lit des noms ou seulement des effectifs :
--
--   1. parametres_responsables.nominatif, par édition, FAUX par défaut : tant
--      que le planning bouge, « on vous garantit deux animateurs » est une
--      promesse tenable, « on vous garantit Bob et Alice » non. L'organisateur
--      l'allume quand le planning est stabilisé. Une ligne par édition,
--      absente tant que rien n'a été réglé — même convention que
--      contact_organisation : l'absence vaut « effectifs seuls ». La ligne
--      disparaît avec l'édition et ne suit PAS à la duplication : la prochaine
--      édition repart éteinte ;
--   2. habilitation.nominatif, l'exception au cas par cas : NULL suit
--      l'édition, VRAI ou FAUX l'emporte sur elle pour ce seul droit.
--
-- Nominatif veut dire prénom et nom, jamais de coordonnées : le responsable
-- ne reçoit ni e-mail ni téléphone, quel que soit le réglage.
CREATE TABLE parametres_responsables (
    edition_id VARCHAR(64) PRIMARY KEY REFERENCES edition (id) ON DELETE CASCADE,
    nominatif BOOLEAN NOT NULL DEFAULT FALSE
);

ALTER TABLE habilitation ADD COLUMN nominatif BOOLEAN;
