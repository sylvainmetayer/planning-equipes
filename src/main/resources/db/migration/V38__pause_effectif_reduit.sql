-- Troisième stratégie de couverture de pause : EFFECTIF_REDUIT, où le stand
-- reste ouvert pendant la pause repas mais à la moitié de son effectif (arrondi
-- au supérieur), au lieu de fermer (FERMETURE) ou de mobiliser une équipe de
-- relève complète (RELEVE). C'est la règle réellement appliquée par le classeur
-- source du festival — voir docs/solver-pause-effectif-reduit.md.
--
-- Deux changements indissociables :
--   1. la contrainte CHECK posée en V17 énumère les valeurs autorisées, donc
--      tout UPDATE vers EFFECTIF_REDUIT échouerait sans la recréer ;
--   2. la vacation qui couvre la pause doit être reconnaissable à la
--      génération de postes, d'où le marqueur porté par le créneau.
--
-- La colonne reste en VARCHAR(16) : 'EFFECTIF_REDUIT' fait 15 caractères. Toute
-- valeur ajoutée par la suite devra tenir dans cette largeur, ou l'élargir.

ALTER TABLE parametres_decoupage
    DROP CONSTRAINT IF EXISTS parametres_decoupage_strategie_valide;

ALTER TABLE parametres_decoupage
    ADD CONSTRAINT parametres_decoupage_strategie_valide
        CHECK (strategie_couverture_pendant_pause IN ('FERMETURE', 'RELEVE', 'EFFECTIF_REDUIT'));

-- FALSE pour tout l'existant : les créneaux déjà générés l'ont été sous
-- FERMETURE ou RELEVE, dont aucune ne réduit l'effectif.
ALTER TABLE creneau ADD COLUMN IF NOT EXISTS couverture_pause BOOLEAN NOT NULL DEFAULT FALSE;
