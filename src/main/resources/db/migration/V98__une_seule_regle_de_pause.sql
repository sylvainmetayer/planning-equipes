-- Une seule règle de pause : trou ou relais (issue #32, ADR 0048).
--
-- Le système de pause s'était construit en couches : un modèle d'origine où la
-- pause est un trou entre deux vacations, une case « pause prise sur le poste »
-- (V65) pour accepter la relève de midi enchaînée à l'après-midi, puis des
-- correctifs pour boucher ce que cette case ouvrait — dont deux durées de pause
-- par tranche d'âge (V84). Quatre notions appelées « pause », deux modes dont
-- l'un éteignait des règles de l'autre.
--
-- Il n'en reste qu'une, dure : toute pause légale due est soit un trou dans la
-- grille d'au moins la durée paramétrée, soit relayée par un collègue du même
-- stand ; sinon c'est un écart dur, porté par `travailContinuMaxMajeur` et
-- `travailContinuMaxMineur`, qui gardent leur nom, leur catégorie et donc
-- leurs lignes de `constraint_toggle` et de `ponderation_contrainte`.

-- 1. La bascule de mode n'a plus de sens : la règle ne se déclare pas, elle
--    s'applique.
ALTER TABLE parametres_legaux
    DROP COLUMN IF EXISTS pause_sur_poste;

-- 2. L'écart minimal entre deux vacations d'une même journée disparaît. Il
--    contredisait la pause légale — un trou de 20 min terminait légalement une
--    séquence de travail et coûtait pourtant 10 dur — et quatorze des quinze
--    scénarios livrés le mettaient à zéro pour s'en débarrasser. Un trou plus
--    court que la pause compte comme travaillé, un trou plus long est une
--    pause : rien d'autre.
ALTER TABLE parametres_legaux
    DROP COLUMN IF EXISTS pause_minimale_entre_vacations_minutes;

-- 3. Une seule durée de pause pour l'édition. On garde la valeur des majeurs :
--    c'est celle que l'organisateur voyait et réglait, et le plancher des
--    mineurs (30 min, art. L3162-3, d'ordre public) est désormais appliqué à la
--    lecture par `ParametresLegaux.dureePauseMinutes(true)` plutôt que stocké.
--    Une édition qui avait réglé 30 pour les majeurs garde 30 ; une qui n'avait
--    rien réglé garde son 20 et n'est donc pas relevée sans le savoir, là où le
--    défaut d'une nouvelle édition passe à 30 (cadre arrêté par
--    l'organisation, issue #31).
ALTER TABLE parametres_legaux
    RENAME COLUMN duree_pause_majeur_minutes TO duree_pause_minutes;

ALTER TABLE parametres_legaux
    ALTER COLUMN duree_pause_minutes SET DEFAULT 30;

ALTER TABLE parametres_legaux
    DROP COLUMN IF EXISTS duree_pause_mineur_minutes;

COMMENT ON COLUMN parametres_legaux.duree_pause_minutes IS
    'Legal break length, one per edition; floor 20 min (L3121-16), raised to 30 for a minor at read time (L3162-3)';

-- 4. Les deux règles retirées du catalogue. Une ligne de toggle ou de poids qui
--    ne nomme plus aucune règle n'est pas inerte : `GET /api/constraints` la
--    tait, mais elle réapparaîtrait sous ce nom si un jour il revenait, avec
--    une intention vieille de plusieurs éditions. On efface (précédent V95).
--
--    `pauseSurPosteSansRelais` est absorbée par les deux règles dures, qui
--    vérifient maintenant le relais elles-mêmes. `pauseMinimaleEntreVacations`
--    n'est remplacée par rien.
DELETE FROM constraint_toggle WHERE nom IN ('pauseSurPosteSansRelais', 'pauseMinimaleEntreVacations');
DELETE FROM ponderation_contrainte WHERE nom IN ('pauseSurPosteSansRelais', 'pauseMinimaleEntreVacations');
