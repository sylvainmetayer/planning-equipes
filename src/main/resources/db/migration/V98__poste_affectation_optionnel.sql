-- Le renfort, persisté avec le siège (issue #505, ADR 0046).
--
-- Un siège optionnel est généré au-dessus de l'effectif que la fenêtre
-- déclare, jusqu'à l'`effectifMax` du stand. Il ne se distingue d'un siège
-- ordinaire que par une chose : `posteDoitEtrePourvu` l'ignore, donc le
-- laisser vide n'est jamais une violation ni un écart.
--
-- La colonne existe parce que les écrans lisent le plan **enregistré**, pas le
-- problème que le solveur a construit : sans elle, un renfort vide se relirait
-- comme un trou, et l'application signalerait un sous-effectif exactement là
-- où l'organisateur a déclaré une capacité qu'il sait ne pas toujours tenir.
-- C'est la fausse alerte que cette fonctionnalité existe pour éviter.
--
-- FALSE par défaut : tout ce qui est déjà en base a été généré sur l'effectif
-- demandé, et reste donc dû.
ALTER TABLE poste_affectation ADD COLUMN optionnel BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN poste_affectation.optionnel IS
    'Renfort : siège généré au-dessus de l''effectif demandé, que personne ne doit';
