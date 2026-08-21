-- Comparateur A/B d'instantanés (issue #70) : chaque instantané emporte les
-- KPI du plan au moment de la capture.
--
-- Sans cette colonne, comparer deux instantanés obligerait à recalculer un
-- score — donc à relancer un solve, ce que le comparateur s'interdit
-- explicitement (« le comparateur ne déclenche aucun solve »). Les KPI sont
-- dénormalisés en JSONB, comme `kpi_historique.kpi` et pour la même raison :
-- la liste des indicateurs bougera, une colonne par indicateur pas.
--
-- Aucune donnée nominative : la même agrégation que #89 (dispersion des heures
-- en moyenne / écart-type / min / max), jamais un classement d'animateurs.
--
-- Un instantané capturé avant cette migration a simplement `kpi` NULL : le
-- comparateur recalcule alors, en mode dégradé, ce qui reste calculable depuis
-- `contenu` (couverture et volumétrie exactes, violations non mesurées).

ALTER TABLE plan_snapshot ADD COLUMN IF NOT EXISTS kpi JSONB;
