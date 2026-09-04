-- Effectif porté par la fenêtre horaire, et non plus seulement par le stand.
--
-- Stand ne portait qu'un couple effectif_min / effectif_max valable toute la
-- journée, alors que le besoin varie par tranche : 4 personnes le matin,
-- 4 l'après-midi, 5 le soir sur un même stand. buildPostes plaçait donc
-- effectif_min partout, ce qui sous-dote massivement les heures de pointe.
--
-- Les fenêtres d'ouverture sont déjà découpées exactement sur les changements
-- d'effectif : c'est donc le bon porteur, il n'y manquait que la valeur.
--
-- NULL = hériter de stand.effectif_min, le comportement historique. La
-- migration est strictement additive : aucune édition existante ne change de
-- comportement tant que personne ne renseigne la colonne.
--
-- L'effectif doit être strictement positif quand il est renseigné : « ouvert
-- sans personne » n'a pas de sens ailleurs dans le modèle (le stand
-- apparaîtrait ouvert dans les exports sans qu'aucun poste ne soit à pourvoir),
-- et buildPostes garantit déjà au moins une place par stand ouvert.

ALTER TABLE stand_horaire_fenetre
    ADD COLUMN effectif INTEGER;

ALTER TABLE stand_horaire_fenetre
    ADD CONSTRAINT stand_horaire_fenetre_effectif_positif
    CHECK (effectif IS NULL OR effectif > 0);

-- Symétrie avec les ouvertures datées ponctuelles, qui répondent au même
-- besoin pour un stand ouvert une seule fois : sans elle, l'effectif ne serait
-- exprimable que par une règle récurrente.
ALTER TABLE stand_ouverture
    ADD COLUMN effectif INTEGER;

ALTER TABLE stand_ouverture
    ADD CONSTRAINT stand_ouverture_effectif_positif
    CHECK (effectif IS NULL OR effectif > 0);

COMMENT ON COLUMN stand_horaire_fenetre.effectif IS
    'Places à pourvoir sur cette fenêtre. NULL = hériter de stand.effectif_min.';
COMMENT ON COLUMN stand_ouverture.effectif IS
    'Places à pourvoir sur cette ouverture datée. NULL = hériter de stand.effectif_min.';
